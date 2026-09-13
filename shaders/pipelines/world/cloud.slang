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
    public float erosionStrength; // 0..1 carve amount of the Worley field (0 = none)
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
    t.erosionStrength = clamp(worldPush.cloudOrigin.z, 0.0, 1.0);
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
// origin, and the rebase origin (worldPush.cloudOrigin.x/.y/.w = the player's block X/Z/Y at the
// last terrain rebuild) moves each time the terrain rebuilds. A rebased coordinate is therefore
// NOT world-stable: without compensation the whole cloud field would shift with the origin.
// Reconstruct the world position here for every domain that must stay pinned to the world — the
// weather texture, the macrogrid, the shape/detail noise and the column crossing test.
//
// Y is re-anchored like XZ, and it is the axis that matters most in practice: the rebase origin Y
// is the player's own block Y, so a rebuild snaps it to wherever the player now stands. A vertical
// shift of the origin moves the aperiodic shape fBm along its Y axis (the whole cloud pattern
// translates up or down in the shell, and re-rolls wherever the lattice cell index changes) and
// re-seeds the erosion lattice outright — its cell is 15 blocks, so a modest altitude change lands
// on a different Worley cell and the wispy detail changes wholesale. Only the reconstructed world
// coordinate keeps both lattices sampled at the same place before and after any rebase.
//
// cloudOrigin is the UNFOLDED rebase origin, so the reconstruction equals the true world coordinate
// and stays continuous across every rebase and over any walk distance. A periodic wrap of this
// anchor is deliberately avoided — weather is tile-periodic and survives a wrap through its
// cell/local UV, but a wrap of the aperiodic shape noise would sample a shifted lattice and
// teleport the cloud shape, so the anchor must never be wrapped.
//
// Magnitudes differ per axis: world XZ can reach millions of blocks (hence cloudWeatherUv's
// cell/local split), but the vertical noise input is only ever sampled inside the shell, so its
// world value stays within a few hundred blocks and needs no split.
public float2 cloudWorldXz(float3 p) {
    return p.xz + worldPush.cloudOrigin.xy;
}

public float cloudWorldY(float yRebased) {
    return yRebased + worldPush.cloudOrigin.w;
}

// ---- Density field ------------------------------------------------------------------------------

// The weather tile's UV for a world position: one wrapping CLOUD_WEATHER_SPAN tile across XZ. The
// weather map and the macrogrid are both indexed by it, so the wrap lives here once.
//
// The rebase origin (cloudOrigin) can sit millions of blocks from the float origin. Summing it with
// the rebased p.xz and THEN dividing by SPAN would drop the low bits of p.xz (weeks of accumulated
// position become a rounding error), and near a tile boundary a crossed cell would jump the wrap back
// to the far side of the tile — the two half-texel-seam classes of the A2 anchor fix. Reconstruct the
// wrap cell/local (floor→cell, subtract→[0,SPAN) local) so no large intermediate is ever formed.
public float2 cloudWeatherUv(float3 p) {
    float2 world = cloudWorldXz(p);
    float2 cell = floor(world / CLOUD_WEATHER_SPAN);
    float2 local = world - cell * CLOUD_WEATHER_SPAN; // ∈ [0, SPAN) regardless of sign or magnitude
    return local / CLOUD_WEATHER_SPAN;
}

// Weather lookup: the bake already advected and biased the field (weatherCoverageField in
// cloud_field), so the runtime lookup is a plain wrap.
// R = coverage 0..1, G = cloud type 0..1 (0 = stratus .. 1 = cumulus), B = vertical relief 0..1
// (~0.5 is the neutral shell, so the mapped lift is zero at mid-field).
public float3 sampleWeather(float3 p) {
    return cloudWeatherTex.SampleLevel(cloudWeatherUv(p), 0.0).rgb;
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
    float3 weather = sampleWeather(p);
    float coverage = weather.r;
    if (coverage <= 0.003) {
        return 0.0;
    }
    float type = weather.g;
    float lift = weather.b;
    float hf = (p.y - bottom) / max(top - bottom, 1.0);
    float profile = heightProfile(hf, type, lift);
    if (profile <= 0.003) {
        return 0.0;
    }

    float k = worldPush.cloudLook1.z;

    // Shape: 3D fBm gated by coverage (Nubis' threshold remap), morphed by the density knob's
    // fullness curve and the adiabatic LWC ramp, then eroded by Worley. Coverage controls WHERE
    // clouds exist; peak density inside a cloud is independent of coverage, which is why the
    // macrogrid majorant is the global CLOUD_MAX_SIGMA × sigmaScale(k) for any covered column.
    // The power morph and the ramp never exceed 1, so that bound holds at every knob position.
    float3 sp = float3(cloudWorldXz(p) * CLOUD_SHAPE_FREQ, cloudWorldY(p.y) * CLOUD_SHAPE_FREQ);
    // Shape advects with the RAW integrated wind shaft (cloud_field's cloudWindShaft, cloudLook0.z):
    // the shape fBm is aperiodic, so the weather offset's SPAN-fold would teleport it. It shares the
    // same accrued distance as the folded weather offset, so the fine structure tracks the broad
    // coverage mask under wind and a wind-speed change re-scales both drift rates together.
    float2 shaft = cloudWindShaft(worldPush.cloudLook0.z);
    float3 windShear = float3(shaft.x, 0.0, shaft.y) * CLOUD_SHAPE_FREQ;
    float shape = fbm3(sp + windShear, tune.shapeOctaves);
    float density = pow(cloudRemap(shape * profile, 1.0 - coverage, 1.0), cloudDensityShapePow(k));
    density *= lerp(cloudDensityLwcBase(k), 1.0, hf);
    if (density > 0.0 && tune.erosion >= 1) {
        float2 eolXz = cloudWorldXz(p) * CLOUD_EROSION_FREQ;
        // Erosion advects with the SAME raw wind shaft as the shape field (frequency-scaled), so its
        // wispy detail tracks the cloud body under wind instead of drifting on an unbounded session
        // clock: a raw-time offset would scroll even at zero wind and grow with uptime, self-boiling
        // wisps long after the wind died. Static at zero wind, coherent with shape otherwise.
        float2 windShearE = shaft * CLOUD_EROSION_FREQ;
        float3 ep = float3(eolXz.x + windShearE.x, cloudWorldY(p.y) * CLOUD_EROSION_FREQ,
                eolXz.y + windShearE.y);
        float erosion = tune.erosion >= 2 ? worley3x27(ep) : worley3x8(ep);
        density = cloudRemap(density, erosion * tune.erosionStrength * (1.0 - density), 1.0);
    }

    // C1 detail: an optional high-frequency octave carved into the eroded density, evaluated IN
    // SHAPE SPACE (the same shaft carries the wind offset's shear, so the detail advects with the
    // cloud body instead of crawling against it) and faded out past a viewer distance so it cannot
    // alias into shimmer far away. The carve is scaled to stay ∈ [0.55, 1.0] — detail only erodes,
    // never densifies — which preserves the macrogrid majorant bound for the tuned σt ceiling, so a
    // non-zero detail strength cannot push-density past what the empty-space skip expected. Default
    // strength 0 leaves the reference look untouched.
    float detail = clamp(worldPush.cloudLook2.w, 0.0, 1.0);
    if (detail > 0.0) {
        float lod = saturate(1.0 - length(p - worldPush.camOffset) * (1.0 / 14000.0));
        float3 detailP = (sp + windShear) * float3(2.6, 1.7, 2.6);
        float det = fbm3(detailP, 1); // single octave: near-parity with fbm3 up close at half the cost
        density *= 1.0 - (0.45 * (1.0 - det)) * detail * lod;
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
    // B3: jitter the quadrature LATTICE start (not just the per-stride lengths) by up to one stride,
    // so the shadow estimate's placement decorrelates frame-to-frame and DLSS-RR integrates the
    // residual like any other estimator noise. Capped below a stride so it can never overshoot a
    // column boundary into a hop it was not meant to take, AND to a quarter of the shell interval:
    // a near or thin cloud has a short tExit - tEnter that fits only a couple of strides, so an
    // uncapped offset would park those few samples at random positions within the slice every frame
    // — with the camera closing in, RR's reprojection cannot settle it and the cloud shimmers
    // randomly. Far clouds have a large interval, so the quarter-interval bound stays above the
    // half-stride cap and decorrelation is unchanged there.
    float jitterMax = min(cloudShadowStride(tune, tExit - tEnter) * 0.5,
            (tExit - tEnter) * 0.25);
    t += rndf(seed) * jitterMax;
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
    float3 dome = skyViewLut.SampleLevel(sunUv, 0.0).rgb + skyViewLut.SampleLevel(moonUv, 0.0).rgb;
    // B4: the sky-view LUT carries only the bodies' scattering, so on a moonless night it reads ~0 and
    // a cloud would go darker than the airglow the camera sees silhouetted against it. Airglow peaks at
    // the oxygen 557.7 nm (green) and 630 nm (red) lines — tint it green/red rather than grey — and
    // unresolved starlight adds a neutral floor. Both are isotropic (emitted by the atmosphere itself,
    // not scattered from a body), matching the two floors the miss shader adds to the sky it draws.
    float3 airglow = sky.airglowLuminance * float3(1.10, 1.00, 0.74);
    float3 starFloor = float3(sky.starLuminance * 0.35);
    return dome + airglow + starFloor;
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

    // B1: the dominant light reaching THIS cloud point has passed through the atmosphere down to the
    // cloud's altitude, not down to the viewer. A high cloud — or one grazing the horizon — intercepts
    // a shorter and differently-tinted (ozone-stained) path than the ground. The LUT already folded the
    // viewer's path into celestialLight.illuminance, so shift it to this point by the ratio of
    // cloud-to-space over viewer-to-space transmittance toward the light; the ozone reddening and
    // low-sun extinction fall out per channel. Sampled at the event (events are sparse — a handful per
    // cloud crossing) rather than stepped, so no dedicated cloud sub-LUT is warranted.
    float cloudAltitudeKm = worldPush.cloudLook0.w + (p.y - worldPush.cloudLook1.x) / 100.0;
    float cloudRadiusKm = clamp(ATMOS_BOTTOM_KM + cloudAltitudeKm,
            ATMOS_BOTTOM_KM + MIN_VIEWER_ALTITUDE_KM, ATMOS_TOP_KM);
    float3 cloudToSpace = sampleTransmittance(transmittanceLut, cloudRadiusKm, celestialLight.dir.y);
    float3 viewerToSpace = sampleTransmittance(transmittanceLut, sky.viewerRadiusKm, celestialLight.dir.y);
    float3 atmo = clamp(cloudToSpace / max(viewerToSpace, float3(1.0e-6)), 0.0, 8.0);

    bool exactShadow = cloudExactShadow(tune.eventShadow, firstEvent);
    // B3 (order-1 estimate only): the Beer shadow must undercut pure self-opacity by the multiple
    // scattering the MS LUT supplies above order 1 — higher-order light bleeds AROUND the direct lobe,
    // so the direct term's penetration rises toward the horizon, where slant optical paths are long and
    // multi-scatter dominates. A light-zenith-linear coefficient replaces the old fixed 0.35.
    float kBeer = lerp(0.18, 0.42, saturate(0.5 + 0.5 * celestialLight.dir.y));
    float3 shadowTerm = exactShadow
            ? cloudTransmittance(p + celestialLight.dir * 2.0, celestialLight.dir, 10000.0, seed)
            : exp(-tau * kBeer).xxx;

    float first = firstEvent ? 1.0 : 0.0;
    // Fluence → source: the LUT stores φ/(4π) per unit illuminance/radiance; the isotropic
    // diffuse re-emission at this event scales by the local extinction σt. CLOUD_MS_STRENGTH is
    // the LUT's calibration factor, so it scales only the two LUT terms — the order-1 NEE term
    // above carries the exact phase and transmittance and stays outside that calibration. The
    // altitude-shift `atmo` scales the sun contribution (order-1 and MS sun fluence alike) but not
    // the sky-ambient term, whose radiance is already altitude-sampled in cloudSkyAmbient.
    float3 luminance = celestialLight.illuminance * atmo * phase * shadowTerm;
    luminance += CLOUD_MS_STRENGTH * celestialLight.illuminance * atmo * (ms.rgb * (sigmaT * first));
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
