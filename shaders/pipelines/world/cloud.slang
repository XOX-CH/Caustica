// Volumetric clouds — the Tier S path-traced heterogeneous medium.
//
// Shape follows the Nubis weather-map recipe (Schneider, HZD 2015 / Nubis 2017 / Nubis Evolved
// 2022): a 2D weather texture (R = coverage, G = cloud type) drives a 3D shape fBm under a height
// profile that yields flat eroded bases and rounded tops, eroded by high-frequency Worley. Wind
// advects the weather domain and shears the sample position. Density is evaluated by position —
// clouds never enter the TLAS — so the path integral, the shadow rays and the LUT bakes all share
// one implementation.
//
// Transport follows the Volumetric ReSTIR baseline family (Lin/Wyman/Yüksel 2021 and the tracking
// papers it builds on — Kutz 2017, Novák 2014, PBRT-v4 vol. scattering):
//   * primary medium walk — jittered midpoint stride quadrature of the Beer integral over the
//     whole shell interval, with scattering events drawn from the analog collision process on
//     the quadrature optical-depth field (target = −ln ξ). Exact Woodcock tracking costs
//     interval × σ̄ free flights, so any step cap becomes a distance cap that drops every cloud
//     beyond the reach limit; strides renormalise against the remaining interval each step, so
//     N strides span the crossing at any density and any distance. The estimator is unbiased
//     given the quadrature field, and the field's own error arrives as per-frame noise DLSS-RR
//     integrates away;
//   * NEE transmittance    — the same deterministic stride quadrature for shadow rays (see
//     cloudTransmittance), so the primary walk and the shadow march share one estimator family
//     and one macrogrid skip structure;
//   * phase                — HG+Draine approximate Mie (Jendersie & d'Eon, SIGGRAPH 2023), fitted
//     droplet-diameter parameterisation, fully analytic evaluation and CDF-inverted sampling;
//   * multiple scattering  — real path bounces (Russian roulette on the cloud albedo, capped) plus
//     a Wrenninge-style multiple-scattering LUT baked by cloud_lut/mslut.comp from a small Monte
//     Carlo of this exact phase, charged only at the first event of each medium encounter.
//
// Photometrics: extinction is per BLOCK (renderer lengths); the shell's world altitude is pushed
// from Java in rebased blocks (100 blocks = 1 km, the atmosphere's own exaggerated scale). The
// illuminance comes from the existing dominantCelestialLight() — clouds and terrain share one
// lighting truth — and radiance returns in cd/m² exactly like every surface term.

import world_common;
import world_core;
import bindings;
import math;
import sky;
import cloud_field;

// ---- Per-parameter tuning -----------------------------------------------------------------------
//
// Every cloud knob is an individual number pushed each frame — there are no presets to keep
// coherent, and any combination is valid. The parameters come in two kinds, and that distinction is
// what makes them independently tunable:
//
//   shape     — octave count and erosion flavour. Changes the density field itself, therefore the
//               converged image: silhouettes, eroded edges, wispy bases. No amount of temporal
//               accumulation reconstructs it, so budget spent here is the only kind that survives
//               into the final frame.
//   estimator — step caps, stride scale, early-out floor and event-shadow precision. Changes
//               estimator variance and the thick-core truncation only, so its error arrives as
//               per-frame noise that the temporal denoiser integrates away.
//
// Physical transport (phase, tracking estimators, MS LUT) is never affected, and neither is any
// bake, so every knob can move mid-session.
public struct CloudTuning {
    public int shapeOctaves;  // fBm octaves of the shape field
    public int erosion;       // 0 = none, 1 = 8-cell Worley, 2 = 27-cell Worley
    public int eventShadow;   // 0 = Beer everywhere, 1 = exact at the first event, 2 = exact everywhere
    public int pathSteps;     // strides per shell crossing of the primary walk (renormalised to
                              // always span the interval — cost is linear, density- and
                              // distance-independent, and the walk can never truncate)
    public int shadowSteps;   // strides per transmittance walk
    public float strideScale; // stride length as a fraction of the remaining interval
    public float exitFloor;   // running-product floor that ends a walk early
};

public CloudTuning cloudTuning() {
    CloudTuning t;
    t.shapeOctaves = clamp(int(round(worldPush.cloudLook2.x)), 1, 8);
    t.erosion = clamp(int(round(worldPush.cloudLook2.y)), 0, 2);
    t.eventShadow = clamp(int(round(worldPush.cloudLook2.z)), 0, 2);
    t.pathSteps = clamp(int(round(worldPush.cloudLook3.x)), 1, 512);
    t.shadowSteps = clamp(int(round(worldPush.cloudLook3.y)), 1, 512);
    t.strideScale = clamp(worldPush.cloudLook3.z, 0.0, 1.0);
    t.exitFloor = clamp(worldPush.cloudLook3.w, 0.0, 1.0);
    return t;
}

// Transmittance stride length for one step; the caller applies the per-stride jitter. Both clamp
// bounds scale with the tuning's stride fraction so a single number stays physical across its whole
// range: 0.40 reproduces the 12–64 block window the tracker was tuned against.
public float cloudShadowStride(CloudTuning tune, float remaining) {
    return clamp(remaining * tune.strideScale, tune.strideScale * 30.0, tune.strideScale * 160.0);
}

// Whether this scattering event runs the exact transmittance walk rather than the tau-based Beer
// estimate. Mode 1 keeps each encounter's first event exact and estimates the deeper ones; the MS
// LUT's ambient term already carries the higher-order fill those events contribute.
public bool cloudExactShadow(int eventShadow, bool firstEvent) {
    return eventShadow >= 2 || (eventShadow == 1 && firstEvent);
}

// ---- World anchor --------------------------------------------------------------------------------
//
// Every ray origin and terrain instance transform is rebased so the player sits near the float
// origin, and the rebase origin (worldPush.cloudOrigin.xy = (blockX, blockZ)) moves each time the
// terrain rebuilds. A rebased coordinate is therefore NOT world-stable: without compensation the
// whole cloud field would shift with the origin. Reconstruct the world XZ here for every domain
// that must stay pinned to the world — the weather texture, the macrogrid, the shape/detail noise
// and the column crossing test. Only the horizontal axes are re-anchored; Y stays rebased relative
// to the (equally rebased) pushed shell, which keeps the noise-domain magnitudes small and precise.
public float2 cloudWorldXz(float3 p) {
    return p.xz + worldPush.cloudOrigin.xy;
}

// ---- Density field ------------------------------------------------------------------------------

// The weather tile's UV for a world position: one wrapping CLOUD_WEATHER_SPAN tile across XZ. The
// weather map and the macrogrid are both indexed by it, so the wrap lives here once.
public float2 cloudWeatherUv(float3 p) {
    return frac(cloudWorldXz(p) / CLOUD_WEATHER_SPAN);
}

// Weather lookup: the bake already advected and biased the field (weatherCoverageField in
// cloud_field), so the runtime lookup is a plain wrap.
// R = coverage 0..1, G = cloud type 0..1 (0 = stratus .. 1 = cumulus).
public float2 sampleWeather(float3 p) {
    return cloudWeatherTex.SampleLevel(cloudWeatherUv(p), 0.0).rg;
}

// Empty-column gate for hop skipping: the baked per-cell max coverage answers whether the column
// can hold any cloud at all (density is coverage-gated, so a ~0 cell maximum means vacuum along
// the whole column). Both the primary walk and the shadow march hop cell-to-cell through empty
// space with one fetch instead of evaluating σt stride by stride.
public bool cloudColumnCovered(float3 p) {
    return cloudMacroGrid.SampleLevel(cloudWeatherUv(p), 0.0).r > 0.003;
}

// Density sample: returns σt in 1/blocks at p (rebased world space). A zero return means p is
// outside the shell, in an empty column, or under the height profile's floor — the caller's cue
// to treat the point as vacuum.
public float cloudSigma(float3 p, CloudTuning tune) {
    float bottom = worldPush.cloudLook1.x;
    float top = worldPush.cloudLook1.y;
    if (p.y < bottom || p.y > top) {
        return 0.0;
    }
    float2 weather = sampleWeather(p);
    float coverage = weather.r;
    if (coverage <= 0.003) {
        return 0.0;
    }
    float type = weather.g;
    float hf = (p.y - bottom) / max(top - bottom, 1.0);
    float profile = heightProfile(hf, type);
    if (profile <= 0.003) {
        return 0.0;
    }

    float k = worldPush.cloudLook1.z;

    // Shape: 3D fBm gated by coverage (Nubis' threshold remap), morphed by the density knob's
    // fullness curve and the adiabatic LWC ramp, then eroded by Worley. Coverage controls WHERE
    // clouds exist; peak density inside a cloud is independent of coverage, which is why the
    // macrogrid majorant is the global CLOUD_MAX_SIGMA × sigmaScale(k) for any covered column.
    // The power morph and the ramp never exceed 1, so that bound holds at every knob position.
    float3 sp = float3(cloudWorldXz(p) * CLOUD_SHAPE_FREQ, p.y * CLOUD_SHAPE_FREQ);
    float2 wo = cloudWindOffset(worldPush.cloudLook0.yz);
    float shape = fbm3(sp + float3(wo.x, 0.0, wo.y) * CLOUD_SHAPE_FREQ, tune.shapeOctaves);
    float density = pow(cloudRemap(shape * profile, 1.0 - coverage, 1.0), cloudDensityShapePow(k));
    density *= lerp(cloudDensityLwcBase(k), 1.0, hf);
    if (density > 0.0 && tune.erosion >= 1) {
        float2 eolXz = cloudWorldXz(p) * CLOUD_EROSION_FREQ;
        float3 ep = float3(eolXz.x + worldPush.cloudLook0.z * 0.01, p.y * CLOUD_EROSION_FREQ, eolXz.y);
        float erosion = tune.erosion >= 2 ? worley3x27(ep) : worley3x8(ep);
        density = cloudRemap(density, erosion * CLOUD_EROSION_STRENGTH * (1.0 - density), 1.0);
    }
    return density * CLOUD_MAX_SIGMA * cloudDensitySigmaScale(k);
}

// ---- Shell geometry ------------------------------------------------------------------------------

// Distance (in t) until the ray's XZ column crosses into the next macrogrid cell, or tMax when
// it never will (near-vertical rays stay in one column; that column's majorant answers for the
// whole remaining interval). Both the macrogrid and the weather field are indexed in world XZ —
// the bake advects internally, so the runtime needs no offset here.
float cloudNextColumnDistance(float3 p, float3 rd, float tMax) {
    float2 rel = cloudWorldXz(p) / CLOUD_MACRO_CELL;
    float2 d = rd.xz / CLOUD_MACRO_CELL;
    float best = tMax;
    for (int axis = 0; axis < 2; axis++) {
        float dir = axis == 0 ? d.x : d.y;
        float fracNow = axis == 0 ? frac(rel.x) : frac(rel.y);
        float dist;
        if (abs(dir) < 1.0e-7) {
            dist = tMax;
        } else if (dir > 0.0) {
            dist = (1.0 - fracNow) / dir;
        } else {
            dist = fracNow / -dir;
        }
        best = min(best, max(dist, 1.0));
    }
    return min(best, tMax);
}

// Interval of the ray inside the cloud shell, clipped to [0, tMax]. False when empty.
public bool cloudShellInterval(float3 ro, float3 rd, float tMax, out float tEnter, out float tExit) {
    tEnter = 0.0;
    tExit = 0.0;
    if (worldPush.cloudLook1.w < 0.5) {
        return false;
    }
    float bottom = worldPush.cloudLook1.x;
    float top = worldPush.cloudLook1.y;
    float dy = rd.y;
    if (abs(dy) < 1.0e-6) {
        if (ro.y < bottom || ro.y > top) {
            return false;
        }
        tEnter = 0.0;
        tExit = tMax;
        return tExit > tEnter;
    }
    float t0 = (bottom - ro.y) / dy;
    float t1 = (top - ro.y) / dy;
    tEnter = min(t0, t1);
    tExit = max(t0, t1);
    if (ro.y >= bottom && ro.y <= top) {
        tEnter = 0.0; // already inside the slab
    } else {
        tEnter = max(tEnter, 0.0);
    }
    tExit = min(tExit, tMax);
    return tExit > tEnter;
}

// ---- Shadow transmittance: deterministic midpoint quadrature -------------------------------------

/**
 * Transmittance of the cloud shell along [origin, origin + dir·tMax] for NEE shadow rays.
 *
 * Every stride contributes exp(−σt·dt) evaluated at its midpoint, so the walk is a Riemann sum of
 * the Beer integral and its only error is the quadrature truncation — which shrinks with the
 * stride fraction. The midpoint keeps the local truncation O(dt²) instead of O(dt): the height
 * profile spans the whole shell, so one stride's far end can be far denser than its near end and
 * an endpoint rule would bias dense cores dark. Strides carry a blue-noise-ish jitter so DLSS-RR
 * integrates the residual quadrature error away over frames.
 *
 * σt is analytic at any point (cloudSigma), so the walk needs no stochastic estimator and no
 * control/residual split: any such split would have to hold a control component that is provably
 * below σt, and σt is gated by the shape noise, which nothing above it can bound. Empty columns
 * still cost one macrogrid fetch (plus cell-boundary hops) and return 1.
 *
 * Cost rides the estimator knobs: stride count, stride fraction and the early-exit floor are each
 * pushed individually. The early exit is the classic production transmittance cut — once the
 * running product drops below the threshold the remaining strides could only darken it further by
 * that fraction, so walking them buys nothing visible; products below 0.001 clamp to 0 so thick
 * cores stay opaque instead of leaking a few percent of sky.
 */
public float3 cloudTransmittance(float3 origin, float3 dir, float tMax, inout uint seed) {
    float tEnter, tExit;
    if (!cloudShellInterval(origin, dir, tMax, tEnter, tExit)) {
        return float3(1.0, 1.0, 1.0);
    }
    CloudTuning tune = cloudTuning();
    int maxSteps = tune.shadowSteps;
    float exitFloor = tune.exitFloor;
    float transmittance = 1.0;
    float t = tEnter;
    for (int i = 0; i < maxSteps && t < tExit; i++) {
        float3 p = origin + dir * t;
        if (!cloudColumnCovered(p)) {
            float jump = cloudNextColumnDistance(p, dir, tExit);
            if (jump >= tExit - t) {
                break; // this column never ends before the shell exit: nothing left to cross
            }
            t += jump;
            continue;
        }
        // Jittered stride: the deterministic lattice would freeze a quadrature bias into every
        // frame; the jitter turns it into noise DLSS-RR integrates out. A larger stride fraction
        // takes fewer, longer strides over the same interval. Clamped to the interval so the last
        // stride ends exactly at the shell exit rather than overshooting it.
        float dt = min(cloudShadowStride(tune, tExit - t) * (0.75 + 0.5 * rndf(seed)),
                tExit - t);
        float3 mid = origin + dir * (t + 0.5 * dt);
        float sigmaT = cloudSigma(mid, tune);
        if (sigmaT > 0.0) {
            transmittance *= exp(-sigmaT * dt);
            if (transmittance < exitFloor) {
                break; // remaining strides could not change this visibly
            }
        }
        t += dt;
    }
    transmittance = saturate(transmittance);
    return (transmittance < 0.001 ? 0.0 : transmittance).xxx;
}

// ---- In-scatter lighting ------------------------------------------------------------------------

// Zenith sky luminance (both celestial slices) at the cloud point's absolute altitude — the
// ambient field the MS LUT's ambient channel is normalised against. cloudLook0.w carries the
// shell base's altitude in km; 100 rebased blocks are one km.
float3 cloudSkyAmbient(float3 p, SkyState sky) {
    float bottom = worldPush.cloudLook1.x;
    float altitudeKm = worldPush.cloudLook0.w + (p.y - bottom) / 100.0;
    // The clamp's lower bound already floors the altitude, so no separate max() is needed here.
    float radiusKm = clamp(ATMOS_BOTTOM_KM + altitudeKm,
            ATMOS_BOTTOM_KM + MIN_VIEWER_ALTITUDE_KM, ATMOS_TOP_KM);
    float3 up = float3(0.0, 1.0, 0.0);
    float2 sunUv = skyViewLutUv(radiusKm, 1.0, lightViewCosine(up, sky.sunDir), SKY_VIEW_BODY_SUN);
    float2 moonUv = skyViewLutUv(radiusKm, 1.0, lightViewCosine(up, sky.moonDir), SKY_VIEW_BODY_MOON);
    return skyViewLut.SampleLevel(sunUv, 0.0).rgb + skyViewLut.SampleLevel(moonUv, 0.0).rgb;
}

/**
 * One scattering event's local lighting, per unit of post-scatter throughput:
 * order-1 sun/moon NEE through the cloud's own transmittance (exact self-shadowing — the silver
 * lining and cloud shadowing emerge here), plus the MS LUT's higher-order response and the
 * zenith-sky ambient, both charged only at the first event of an encounter (the capped real
 * bounces carry part of orders ≥ 2 themselves; charging the LUT everywhere would double-count
 * that fraction — the documented Tier S trade, research doc §7.2 "有限反弹 + LUT 兜底").
 *
 * The event's shadow ray is the most expensive single term (a full transmittance walk),
 * so the convergence axis decides how often it runs: at every event, only at each encounter's
 * first event, or never. The estimate is a Beer term on the same tau the MS LUT lookup uses, scaled
 * by an empirical half-slant factor so a dense interior lands near the exact walk's answer; the
 * LUT's ambient term already covers the higher-order fill, so the estimate only steers the direct
 * lobe.
 */
public float3 cloudInscatter(float3 p, float3 rd, MieParams mie, CloudTuning tune, SkyState sky,
                             bool firstEvent, inout uint seed) {
    float cosLight = dot(rd, celestialLight.dir);
    float phase = miePhaseEval(cosLight, mie);
    // Optical depth toward the celestial light: local density × slant distance to the shell top.
    // A column estimate — the exact shadowing above carries the truth; this only steers the LUT.
    float top = worldPush.cloudLook1.y;
    float slant = max((top - p.y) / max(celestialLight.dir.y, 0.15), 0.0);
    float sigmaT = cloudSigma(p, tune);
    float tau = sigmaT * slant;
    float2 lutUv = float2(saturate(cosLight * 0.5 + 0.5), saturate(tau / CLOUD_MS_LUT_TAU_MAX));
    float4 ms = cloudMultiScatterLut.SampleLevel(lutUv, 0.0);

    bool exactShadow = cloudExactShadow(tune.eventShadow, firstEvent);
    float3 shadowTerm = exactShadow
            ? cloudTransmittance(p + celestialLight.dir * 2.0, celestialLight.dir, 10000.0, seed)
            : exp(-tau * 0.35).xxx;

    float first = firstEvent ? 1.0 : 0.0;
    // Fluence → source: the LUT stores φ/(4π) per unit illuminance/radiance; the isotropic
    // diffuse re-emission at this event scales by the local extinction σt. CLOUD_MS_STRENGTH is
    // the LUT's calibration factor, so it scales only the two LUT terms — the order-1 NEE term
    // above carries the exact phase and transmittance and stays outside that calibration.
    float3 luminance = celestialLight.illuminance * phase * shadowTerm;
    luminance += CLOUD_MS_STRENGTH * celestialLight.illuminance * (ms.rgb * (sigmaT * first));
    luminance += CLOUD_MS_STRENGTH * cloudSkyAmbient(p, sky) * (ms.a * (sigmaT * first));
    return luminance;
}

// ---- Segment integration ------------------------------------------------------------------------

/**
 * Outcome of integrating the cloud shell along one path segment.
 *   PASSTHROUGH — crossed without interaction; the segment's transmittance scales what it was
 *                 heading toward (surface hit or sky).
 *   SCATTERED   — a real interaction consumed the path inside the shell; resume the bounce loop
 *                 from the segment's segOrigin/segDir (one cloud event = one bounce-loop
 *                 iteration).
 *   TERMINATED  — the path died inside the shell (bounce cap / roulette); nothing more to trace.
 */
public enum CloudOutcome { PASSTHROUGH, SCATTERED, TERMINATED };

// The mutable state a segment advances. Bundled so the caller's accumulators travel as one value
// instead of four positional in/out parameters.
public struct CloudPathState {
    public float3 throughput;
    public float3 luminance;
    public uint seed;
    public int bounceBudget;
};

// What one segment did. transmittance is meaningful only for PASSTHROUGH, segOrigin/segDir only
// for SCATTERED.
public struct CloudSegment {
    public CloudOutcome outcome;
    public float3 transmittance;
    public float3 segOrigin;
    public float3 segDir;
};

public CloudPathState cloudPathState(float3 throughput, float3 luminance, uint seed,
                                     int bounceBudget) {
    CloudPathState state;
    state.throughput = throughput;
    state.luminance = luminance;
    state.seed = seed;
    state.bounceBudget = bounceBudget;
    return state;
}

/**
 * Primary medium walk through [tEnter, tExit] of the shell: jittered midpoint stride quadrature
 * of the Beer integral, with scattering events drawn from the analog collision process on the
 * quadrature optical-depth field.
 *
 * Stride quadrature instead of Woodcock: exact delta tracking costs interval × σ̄ free flights,
 * so any step cap is a distance cap that silently drops every cloud beyond the reach limit —
 * the visible cutoff line across the sky. Strides renormalise against the remaining interval
 * every iteration (remaining interval / remaining strides), so N strides always span the whole
 * crossing at any density and any distance; the walk cannot truncate, and its cost is exactly
 * one σt evaluation per stride, independent of the density knob.
 *
 * Events: with target = −ln ξ, ξ ~ U(0,1), P(no event) = P(τ < target) = e^{−τ} exactly, and
 * the event position follows the collision density σ(t)·e^{−τ(t)} of the quadrature field — the
 * analog estimator, unbiased given the field. The field's own quadrature error is the only bias,
 * and DLSS-RR integrates it away over frames: the same trade the shadow march makes. Thick
 * clouds self-terminate (most targets are small, so τ crosses them early), which is why this
 * walk needs no transmittance floor.
 *
 * Empty macrogrid columns are hopped at one macrogrid fetch per column; hops are free (they do
 * not consume stride slots, so a hop can never leave the interval's tail unwalked) and bounded
 * by the column count of a shell crossing. The final stride always ends exactly at tExit.
 *
 * On a real event: weight ×= albedo, NEE + (first event) MS LUT + ambient, roulette, sample the
 * next direction from the blended Mie phase, and hand the path back to the bounce loop.
 * `continuingCloudMass` must be true when the caller is already inside the same cloud mass (the
 * previous segment ended in a cloud scatter) so the LUT is charged once per encounter. The
 * state's bounceBudget is the caller's remaining bounce budget (in-only); cloud events consume
 * it, so deep clouds cannot extend the whole path for free.
 */
public CloudSegment cloudIntegrate(float3 ro, float3 rd, float tEnter, float tExit,
                                   bool continuingCloudMass, inout CloudPathState state) {
    CloudSegment seg;
    seg.outcome = CloudOutcome.PASSTHROUGH;
    seg.transmittance = float3(1.0, 1.0, 1.0);
    seg.segOrigin = ro;
    seg.segDir = rd;
    SkyState sky = skyState(worldPush);
    MieParams mie = mieParams(CLOUD_DROPLET_UM);
    CloudTuning tune = cloudTuning();
    // The MS LUT is charged at the encounter's first event only; a segment that begins inside the
    // same mass has already spent that budget.
    bool firstEvent = !continuingCloudMass;
    // Collision target on the quadrature optical-depth field.
    float target = -log(max(rndf(state.seed), 1.0e-7));
    int strides = tune.pathSteps;
    float t = tEnter;
    float tau = 0.0;

    int hops = 0;
    for (int i = 0; i < strides && t < tExit; i++) {
        float3 p = ro + rd * t;
        if (!cloudColumnCovered(p)) {
            float jump = cloudNextColumnDistance(p, rd, tExit);
            if (jump >= tExit - t) {
                break; // empty from here to the shell exit: τ is complete
            }
            t += jump;
            // Hops cost one macrogrid fetch and no σt evaluation, so they must not consume a
            // stride slot — a hop that spent the final slot would leave the tail of the
            // interval unwalked. Bounded independently of the stride count; a shell crossing
            // spans at most 10000 / CLOUD_MACRO_CELL (≈ 40) columns.
            hops++;
            i--;
            if (hops > 48) {
                break;
            }
            continue;
        }
        // Remaining interval over remaining strides; jittered so the deterministic lattice does
        // not freeze a quadrature bias into every frame. The last stride closes the interval
        // exactly (a jittered one could stop short).
        float dt = (tExit - t) / float(strides - i);
        if (i + 1 < strides) {
            dt = min(dt * (0.75 + 0.5 * rndf(state.seed)), tExit - t);
        }
        float sigmaT = cloudSigma(ro + rd * (t + 0.5 * dt), tune);
        if (sigmaT > 0.0) {
            float tauNext = tau + sigmaT * dt;
            if (tauNext >= target) {
                // Collision inside this stride: place it by linear τ interpolation (the midpoint
                // model holds σ constant within a stride).
                float3 q = ro + rd * (t + (target - tau) / sigmaT);
                if (state.bounceBudget <= 0) {
                    seg.outcome = CloudOutcome.TERMINATED;
                    return seg;
                }
                state.throughput *= CLOUD_ALBEDO;
                state.luminance += state.throughput
                        * cloudInscatter(q, rd, mie, tune, sky, firstEvent, state.seed);
                firstEvent = false;
                // Russian roulette on the cloud's own transport
                float qRr = clamp(max(state.throughput.r, max(state.throughput.g, state.throughput.b)),
                        0.02, 1.0);
                if (rndf(state.seed) > qRr) {
                    seg.outcome = CloudOutcome.TERMINATED;
                    return seg;
                }
                state.throughput /= qRr;
                seg.outcome = CloudOutcome.SCATTERED;
                seg.segOrigin = q;
                seg.segDir = sampleMieDirection(rd, mie, state.seed);
                return seg;
            }
            tau = tauNext;
        }
        t += dt;
    }
    float passthrough = exp(-tau);
    seg.transmittance = (passthrough < 0.001 ? 0.0 : passthrough).xxx;
    return seg;
}
