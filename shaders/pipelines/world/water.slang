// Water surface: the wave spectrum (with its analytic time derivative), the normal perturbation it
// drives, and the caustic term that differentiates the same field. Depends on core.


// Animated surface waves — a directional wave spectrum, gradient-only (the surface stays geometrically
// flat; only the normal tilts). Three things make it read as water rather than animated glass:
//  * Sharp-crested profile: each component is h = a·exp(S·(sin φ − 1)) — peaked crests, wide flat
//    troughs (pure sines look like rolling jelly). Still C∞ smooth with an exact analytic gradient.
//  * Real deep-water dispersion ω = √(g·k): long swell strides past (λ=14 m → ~4.7 m/s) while short
//    chop flutters nearly in place — wrong relative speeds are the biggest tell of fake water.
//  * Wind + meander: headings spread around a fixed wind direction (tight for the swell, wide for the
//    chop), and the largest components' phase is modulated along their crest (chain-ruled into the
//    gradient) so wavefronts wander instead of ruling straight periodic lines across the sea.
// The domain is world-anchored (see applyWaterWaves) so the pattern is pinned in world space, and the
// caustics (waterCaustic) differentiate this same field, so they stay in sync with the visible ripples.

import world_common;
import world_core;
import math;

public static const int   WAVE_COUNT = 16;            // λ = 14 m … 0.032 m (each octave 1.5× shorter); dynamic count via push constant
public static const float WAVE_G = 9.81;
public static const float2 WAVE_WIND = normalize(float2(1.0, 0.35)); // dominant travel direction (preset 0)
public static const float2 WAVE_WIND_SWELL = normalize(float2(1.0, 0.20)); // preset 1: narrow swell
public static const float2 WAVE_WIND_CHOP = normalize(float2(1.0, 0.50));  // preset 2: broad wind chop
public static const float WAVE_MEANDER = 1.1;         // crest phase-wobble amplitude (rad) on the long components

// Preset parameter arrays for the wave spectrum. Each preset defines a different energy distribution
// across wavelengths, crest sharpness, directional spread, and meander behaviour.
// Preset 0 (默认): balanced spectrum, moderate meander on long waves
public static const float Q_DEFAULT[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038, 0.030, 0.024, 0.019, 0.015, 0.012, 0.010 };
// Preset 1 (涌浪): long-wave dominant, strong meandering, narrow spread, gentle crests
public static const float Q_SWELL[WAVE_COUNT] = { 0.060, 0.055, 0.045, 0.035, 0.025, 0.020, 0.015, 0.010, 0.008, 0.005, 0.004, 0.003, 0.002, 0.002, 0.001, 0.001 };
// Preset 2 (疾风): short-wave dominant, no meander, wide spread, sharp crests
public static const float Q_CHOP[WAVE_COUNT] = { 0.010, 0.015, 0.025, 0.035, 0.050, 0.065, 0.080, 0.085, 0.075, 0.060, 0.050, 0.040, 0.032, 0.025, 0.020, 0.015 };
// Preset 3 (随机波): no wind direction, each wave travels independently across the full circle,
// creating a multi-dimensional "confused sea" with waves crossing from all angles.
public static const float Q_RANDOM[WAVE_COUNT] = { 0.045, 0.050, 0.055, 0.060, 0.055, 0.050, 0.045, 0.040, 0.035, 0.030, 0.025, 0.020, 0.016, 0.012, 0.010, 0.008 };
// Preset 4 (涟漪): circular ripple waves radiating from point sources.  Each wave is a concentric
// ring expanding from a fixed world-space origin, creating a pond-ripple interference pattern.
public static const float Q_RIPPLE[WAVE_COUNT] = { 0.035, 0.045, 0.055, 0.060, 0.055, 0.045, 0.035, 0.028, 0.022, 0.018, 0.015, 0.012, 0.010, 0.008, 0.006, 0.005 };

// Deterministic world-space source positions for the circular ripple preset (preset 4).
// Each wave index maps to a fixed point spread across a 100×100 block, so the pattern is
// stable across frames and looks like a complex interference of overlapping pond ripples.
public float2 circularSource(int index) {
    float a = float(index) * 2.399963f;          // golden angle rad (~137.5°)
    float r = 5.0f + float(index) * 3.5f;         // radial distance grows with index
    return float2(cos(a), sin(a)) * r;
}

public float waterWaveLodWeight(float wavelength, float footprint) {
    float cyclesPerPixel = max(footprint, 0.0) / max(wavelength, 1.0e-4);
    return 1.0 - smoothstep(0.25, 0.5, cyclesPerPixel);
}

// One walk of the wave spectrum, shared by every consumer so the spectrum constants exist once. WITH_DT
// is a generic so the derivative terms are compiled out entirely for callers that only want the
// gradient — the trigonometry is shared, but the chain-rule work is not free.
public void waterWaveSpectrum<let WITH_DT : int>(float2 p, float t, float footprint,
                                          out float2 grad, out float2 gradDt) {
    // Read wave preset from push constant (0=默认, 1=涌浪, 2=疾风, 3=随机波, 4=涟漪). The uniform branch is
    // compiled once per draw call so the per-wave conditionals are free.
    int preset = int(worldPush.waterDetailParams2.z + 0.5);

    // Select preset parameters: crest sharpness, directional spread, meander, and wind direction.
    float S_base, S_step;
    float spreadBase, spreadStep;
    float meanderAmp;
    int meanderLimit;
    float2 windDir;
    bool useWindDir = true;  // false for random wave (each wave uses its own direction)
    bool useCircular = false; // true for circular ripple (preset 4)

    if (preset == 0) {
        S_base = 1.2; S_step = 0.12;
        spreadBase = 0.15; spreadStep = 0.11;
        meanderAmp = WAVE_MEANDER; meanderLimit = 4;
        windDir = WAVE_WIND;
    } else if (preset == 1) {
        // 涌浪 (Swell): gentler crests, narrow spread, strong meandering on 6 components
        S_base = 1.0; S_step = 0.08;
        spreadBase = 0.08; spreadStep = 0.08;
        meanderAmp = 2.5; meanderLimit = 6;
        windDir = WAVE_WIND_SWELL;
    } else if (preset == 2) {
        // 疾风 (Wind Chop): sharper crests, wide spread, no meander
        S_base = 1.5; S_step = 0.15;
        spreadBase = 0.20; spreadStep = 0.15;
        meanderAmp = 0.0; meanderLimit = 0;
        windDir = WAVE_WIND_CHOP;
    } else if (preset == 3) {
        // 随机波 (Random Wave): no wind direction, each wave travels independently
        // across the full circle, creating a multi-dimensional "confused sea"
        S_base = 1.2; S_step = 0.12;
        spreadBase = 3.0; spreadStep = 0.0;  // Full circle spread
        meanderAmp = 0.8; meanderLimit = 3;   // Light meander on longest waves
        useWindDir = false;
    } else {
        // 涟漪 (Circular Ripple): point-source concentric rings radiating from fixed world-space
        // origins.  Each wave is a circular ripple expanding outward, creating a complex
        // interference pattern of overlapping pond ripples — no wind direction, no meander.
        S_base = 1.0; S_step = 0.10;
        spreadBase = 0.0; spreadStep = 0.0;
        meanderAmp = 0.0; meanderLimit = 0;
        useWindDir = false;
        useCircular = true;
    }

    // Custom meander override: if the push constant meander value > 0, it replaces
    // the preset's meander amplitude.  This lets the user dial in any amount of
    // wave-front bending regardless of the current preset.
    float customMeander = worldPush.waterDetailParams3.y;
    if (customMeander > 0.0) {
        meanderAmp = customMeander;
    }

    // Dynamic wave count from the push constant (4..16).  Limits the number of
    // wave components actually evaluated, so the user can trade detail for
    // performance or force a simpler spectrum.
    int waveCount = int(worldPush.waterDetailParams3.x + 0.5);
    waveCount = clamp(waveCount, 4, WAVE_COUNT);

    // Per-wave steepness a·k
    // The Q array is selected per wavelength inside the loop below.
    t *= worldPush.waterDetailParams.z;
    grad = float2(0.0, 0.0);
    gradDt = float2(0.0, 0.0);
    float lambda = 14.0;
    for (int i = 0; i < waveCount; i++) {
        float lodWeight = waterWaveLodWeight(lambda, footprint);
        if (lodWeight <= 0.0) break;
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);   // deep-water dispersion: phase speed √(g/k)

        // Phase and gradient: directional vs circular wave type
        float ph, phDt;
        float2 dph, dphDt = float2(0.0, 0.0);
        if (useCircular) {
            // Circular ripple: each wave is a concentric ring radiating from a fixed point source.
            // The phase is k * distance(source, p) - w * t, and the gradient is the radial
            // direction from source to p.  No meander — circular waves are already curved.
            float2 sourcePos = circularSource(i);
            float2 delta = p - sourcePos;
            float r = length(delta);
            if (r < 1.0e-4) r = 1.0e-4; // guard against division by zero
            float2 dir = delta / r;      // radial direction
            ph = k * r - w * t + 1.7 * float(i) * float(i);
            dph = k * dir;
            phDt = -w;
        } else {
            // Heading: deterministic irregular spread about the wind, widening down the spectrum.
            // For random wave preset, the spread angle is used directly as the wave direction
            // (full circle), giving each wave its own independent travel direction.
            float spread = (spreadBase + spreadStep * float(i)) * sin(float(i) * 2.4 + 1.3);
            float cs = cos(spread), sn = sin(spread);
            float2 d;
            if (useWindDir) {
                d = float2(windDir.x * cs - windDir.y * sn, windDir.x * sn + windDir.y * cs);
            } else {
                d = float2(cs, sn);  // no wind direction, each wave travels independently
            }
            ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
            dph = k * d;
            phDt = -w;
            if (i < meanderLimit) { // meander only the components whose fronts are long enough to read as lines
                float2 pd = float2(-d.y, d.x);
                float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
                ph += meanderAmp * sin(wph);
                dph += (meanderAmp * cos(wph) * 0.4 * k) * pd;
                if (WITH_DT != 0) {
                    float wphDt = 0.5 * w;
                    phDt += meanderAmp * cos(wph) * wphDt;
                    dphDt = (-meanderAmp * sin(wph) * wphDt * 0.4 * k) * pd;
                }
            }
        }
        float S = S_base + S_step * float(i); // crest sharpness
        float sinPh = sin(ph);
        float cosPh = cos(ph);
        float e = exp(S * (sinPh - 1.0));
        // a = Q/k; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ  (the Q value varies by preset)
        float q;
        if (preset == 0) q = Q_DEFAULT[i];
        else if (preset == 1) q = Q_SWELL[i];
        else if (preset == 2) q = Q_CHOP[i];
        else if (preset == 3) q = Q_RANDOM[i];
        else q = Q_RIPPLE[i];
        // Per-band amplitude multiplier from push constant sliders
        float bandMul = 1.0;
        if (i < 4) {
            bandMul = worldPush.waterDetailParams4[i];
        } else if (i < 8) {
            bandMul = worldPush.waterDetailParams5[i - 4];
        } else if (i < 11) {
            bandMul = worldPush.waterDetailParams6[i - 8];
        }
        q *= bandMul;
        float amplitude = lodWeight * (q / k) * S * worldPush.waterDetailParams3.z;
        grad += amplitude * cosPh * e * dph;
        if (WITH_DT != 0) {
            // ∂/∂t of the term above, chain-ruled through φ and ∇φ.
            gradDt += amplitude * e
                    * (phDt * (-sinPh + S * cosPh * cosPh) * dph + cosPh * dphDt);
        }
        lambda /= 1.5;
    }
    grad *= worldPush.waterDetailParams.y;
    gradDt *= worldPush.waterDetailParams.y;
}

public float2 waterWaveGrad(float2 p, float t, float footprint) {
    float2 grad, gradDt;
    waterWaveSpectrum<0>(p, t, footprint, grad, gradDt);
    return grad;
}

public float2 waterWaveGrad(float2 p, float t) {
    return waterWaveGrad(p, t, 0.0);
}

// Evaluate the current gradient and linearly rewind it to the previous phase in one spectrum walk.
// Frame deltas are bounded on the CPU, so this avoids a second set of trigonometric/exponential work
// while remaining accurate over the sub-frame interval used by reflection reprojection.
public void waterWaveGradTemporal(float2 p, float currentT, float previousT, float footprint,
                           out float2 currentGrad, out float2 previousGrad) {
    float2 gradDt;
    waterWaveSpectrum<1>(p, currentT, footprint, currentGrad, gradDt);
    previousGrad = currentGrad - (currentT - previousT) * worldPush.waterDetailParams.z * gradDt;
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
public float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t, float footprint) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t, footprint);
    float3 up = normalize(float3(-grad.x, 1.0, -grad.y));
    return nGeo.y >= 0.0 ? up : -up;
}

// ---- Water caustics. Sunlight refracting through the waved surface converges/diverges before it
// reaches an underwater receiver; the analytic wave field makes the true focusing factor computable
// instead of faked with a scrolling texture. Where the shadow ray of an underwater NEE vertex crossed
// water (returned by visibility() as VisibilityResult.waterHitT), evaluate the horizontal landing
// position of the refracted sun ray as a function of surface position and finite-difference it. The
// caustic intensity is the inverse Jacobian determinant of that surface→floor mapping (area compression = brightening, real
// fold caustics where det → 0). Because this uses the SAME wave field as the visible surface normals,
// the caustic pattern stays in sync with the ripples, and the per-sample sun-quad jitter (sampleSquare)
// shifts the pattern per sample → caustics physically blur with depth under DLSS-RR accumulation.
public static const float CAUSTIC_EPS = 0.25; // finite-difference step (m); also low-passes sub-step wave detail

// Horizontal landing offset of a refracted sun ray entering the surface at world-stable xz, intersected
// with the receiver plane h metres below. inc = sunlight travel direction (downward, unit).
// The ior parameter enables chromatic dispersion: different wavelengths take different paths.
// refrDir returns the refracted ray direction so callers can correct the Jacobian for the receiver's
// own surface orientation (see causticTiltFactor).
public float2 causticLanding(float2 xz, float t, float3 inc, float h, float ior, out float3 refrDir) {
    float2 g = waterWaveGrad(xz, t);
    float3 nw = normalize(float3(-g.x, 1.0, -g.y));
    float3 rdw = refract(inc, nw, 1.0 / ior); // air→water: never TIR
    refrDir = rdw;
    return xz + rdw.xz * (h / max(-rdw.y, 0.05));
}

// The Jacobian measured on the horizontal reference plane must be refocused onto the receiver's own
// tangent plane. The refracted light arrives as a near-parallel bundle, so its footprint on a plane
// with normal n scales by the parallel-projection area ratio |dot(d, n)|/|d.y|: a slope facing the
// beam focuses brighter, an edge-on surface dims toward zero. Exactly 1 for a horizontal receiver,
// so flat water bodies are unaffected.
public float causticTiltFactor(float3 refrDir, float3 receiverNormal) {
    return abs(dot(refrDir, receiverNormal)) / max(abs(refrDir.y), 0.05);
}

// Reflection-side landing point: sunlight bounces off the waved surface (a distorting mirror) and
// lands h metres ABOVE the surface on an air-side receiver. Mirrors causticLanding with reflect()
// in place of refract(); the reflected ray travels upward, so a wave-tilted normal that would send
// it sideways or down just defocuses the landing (the max() guard stretches the path).
public float2 causticLandingReflect(float2 xz, float t, float3 inc, float h, out float3 reflDir) {
    float2 g = waterWaveGrad(xz, t);
    float3 nw = normalize(float3(-g.x, 1.0, -g.y));
    float3 rdw = reflect(inc, nw); // inc travels downward; the bounce returns skyward
    reflDir = rdw;
    return xz + rdw.xz * (h / max(rdw.y, 0.05));
}

// exitPos is where the receiver's shadow ray crossed the water surface; receiverNormal is the shading
// normal of the NEE vertex receiving the light, used to refocus the Jacobian onto that surface's own
// orientation (gated by waterDetailParams3.w; neutral for horizontal receivers).
public float3 waterCaustic(float3 exitPos, float3 lightDir, float waterDist, float3 receiverNormal) {
    // Grazing light: the receiver-plane intersection degenerates and caustics vanish in reality anyway
    // (long slant paths absorb + the pattern smears out) — fade the effect to neutral 1.0.
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) return float3(1.0, 1.0, 1.0);
    float h = max(waterDist * lightDir.y, 0.05); // vertical depth of the receiver below the exit point
    float t = worldPush.waterParams.w;
    float3 inc = -lightDir;
    float2 base = exitPos.xz + worldPush.waterAnchor.xy;  // world-stable wave domain (same as applyWaterWaves)

    // Chromatic dispersion: the refractive index of water varies slightly with wavelength,
    // causing the R, G, and B caustic patterns to land at slightly different positions.
    // The dispersion strength controls how much the IORs separate.
    float dispersion = worldPush.waterDetailParams2.w;

    // RGB wavelength IORs: red has lower IOR (less bending), blue has higher (more bending).
    // Physical water dispersion (Abbe ~55) gives n_diff ≈ 0.005, which is too small to
    // produce visible separation in the caustic Jacobian (the landing-point shift is far
    // smaller than CAUSTIC_EPS = 0.25 m).  We scale the IOR separation up by 20× for a
    // visible decorative effect.  At dispersion = 0 all three IORs are equal (disabled).
    float iorR = 1.333 - 0.1 * dispersion;
    float iorG = 1.333;                    // green channel baseline
    float iorB = 1.333 + 0.1 * dispersion;

    // Refocus each channel's Jacobian from the horizontal reference plane onto the receiver's tangent
    // plane, so slopes and walls get correctly shaped light bands.
    bool tiltOn = worldPush.waterDetailParams3.w > 0.5;

    // ---- Evaluate three separate caustic Jacobians, one per wavelength
    // Red channel
    float3 refrR;
    float3 fdR;
    float2 p0r = causticLanding(base, t, inc, h, iorR, refrR);
    float2 pxr = causticLanding(base + float2(CAUSTIC_EPS, 0.0), t, inc, h, iorR, fdR);
    float2 pzr = causticLanding(base + float2(0.0, CAUSTIC_EPS), t, inc, h, iorR, fdR);
    float detr = abs((pxr.x - p0r.x) * (pzr.y - p0r.y) - (pxr.y - p0r.y) * (pzr.x - p0r.x));
    float focusR = (CAUSTIC_EPS * CAUSTIC_EPS) / max(detr, 1.0e-5);
    if (tiltOn) focusR *= causticTiltFactor(refrR, receiverNormal);
    float causticR = lerp(1.0, min(focusR, worldPush.waterDetailParams.w * 300.0), fade);
    // Green channel
    float3 refrG;
    float3 fdG;
    float2 p0g = causticLanding(base, t, inc, h, iorG, refrG);
    float2 pxg = causticLanding(base + float2(CAUSTIC_EPS, 0.0), t, inc, h, iorG, fdG);
    float2 pzg = causticLanding(base + float2(0.0, CAUSTIC_EPS), t, inc, h, iorG, fdG);
    float detg = abs((pxg.x - p0g.x) * (pzg.y - p0g.y) - (pxg.y - p0g.y) * (pzg.x - p0g.x));
    float focusG = (CAUSTIC_EPS * CAUSTIC_EPS) / max(detg, 1.0e-5);
    if (tiltOn) focusG *= causticTiltFactor(refrG, receiverNormal);
    float causticG = lerp(1.0, min(focusG, worldPush.waterDetailParams.w * 300.0), fade);
    // Blue channel
    float3 refrB;
    float3 fdB;
    float2 p0b = causticLanding(base, t, inc, h, iorB, refrB);
    float2 pxb = causticLanding(base + float2(CAUSTIC_EPS, 0.0), t, inc, h, iorB, fdB);
    float2 pzb = causticLanding(base + float2(0.0, CAUSTIC_EPS), t, inc, h, iorB, fdB);
    float detb = abs((pxb.x - p0b.x) * (pzb.y - p0b.y) - (pxb.y - p0b.y) * (pzb.x - p0b.x));
    float focusB = (CAUSTIC_EPS * CAUSTIC_EPS) / max(detb, 1.0e-5);
    if (tiltOn) focusB *= causticTiltFactor(refrB, receiverNormal);
    float causticB = lerp(1.0, min(focusB, worldPush.waterDetailParams.w * 300.0), fade);

    // Compose the RGB caustic and apply the color tint blend
    float3 chromatic = float3(causticR, causticG, causticB);
    float3 tinted = chromatic * lerp(float3(1.0, 1.0, 1.0), worldPush.waterColorTint.xyz, worldPush.waterColorTint.w);
    return tinted;
}

// Reflection caustic for receivers in air above the water: the waved surface acts as a distorting
// mirror, so sunlight bounces off it and focuses onto cave ceilings, overhangs and hulls — the
// shimmering bands seen on the underside of anything above water. The refraction caustic above can
// never light a downward-facing surface (refracted sunlight always bends downward), so this is a
// separate, purely reflective light path. surfacePos is the point where the receiver's mirror-sun
// shadow ray crossed the water; waterDist is that crossing distance, so the receiver sits
// waterDist·lightDir.y metres above the reflection point. Fresnel reflectance scales the term to
// physical brightness (≈2% at a high sun, rising steeply toward sunset). No chromatic dispersion:
// geometric reflection is wavelength-independent. Returns 0 when the sun is too grazing.
public float3 waterCausticReflect(float3 surfacePos, float3 lightDir, float waterDist, float3 receiverNormal) {
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) return float3(0.0, 0.0, 0.0);
    // Long throws stretch the finite-difference Jacobian into razor-thin stripes that alias and
    // noise under accumulation — fade the term out with throw distance. Near-waterline bands
    // (the common case) keep full strength.
    float throwFade = 1.0 - smoothstep(8.0, 24.0, waterDist);
    if (throwFade <= 0.0) return float3(0.0, 0.0, 0.0);
    float h = max(waterDist * lightDir.y, 0.05); // receiver height above the reflection point
    float t = worldPush.waterParams.w;
    float3 inc = -lightDir;
    float2 base = surfacePos.xz + worldPush.waterAnchor.xy; // world-stable wave domain
    float F = fresnelDielectric(clamp(lightDir.y, 0.0, 1.0), 1.0, 1.333);

    float3 reflDir;
    float3 fd;
    float2 p0 = causticLandingReflect(base, t, inc, h, reflDir);
    float2 px = causticLandingReflect(base + float2(CAUSTIC_EPS, 0.0), t, inc, h, fd);
    float2 pz = causticLandingReflect(base + float2(0.0, CAUSTIC_EPS), t, inc, h, fd);
    float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
    float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5);
    // Refocus onto the receiver's own tangent plane (same gate as the refraction caustic).
    if (worldPush.waterDetailParams3.w > 0.5) {
        focus *= causticTiltFactor(reflDir, receiverNormal);
    }
    float caustic = F * throwFade * lerp(1.0, min(focus, worldPush.waterDetailParams.w * 300.0), fade);
    // Slight water-colour cast: on real wave flanks part of the bouncing light grazes through the
    // crest briefly before escaping, picking up a hint of the body's colour. Keeps the bands
    // visually anchored to the water's tint (biome colour blended with the custom RGB mix).
    float3 waterTint = lerp(worldPush.waterParams.xyz, worldPush.waterColorTint.xyz,
                            worldPush.waterColorTint.w);
    return caustic * lerp(float3(1.0, 1.0, 1.0), waterTint, 0.25);
}
