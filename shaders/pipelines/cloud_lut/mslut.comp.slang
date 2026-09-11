// Cloud multiple-scattering LUT bake. 32×32 texels: u = cos(celestial zenith), v = optical depth
// from the shell top toward the light (0..32). Channels: RGB = higher-order (≥2) sun/moon fluence
// per unit illuminance, A = the same for unit-radiance isotropic sky light (the ambient term).
//
// The field is estimated by a small Monte Carlo in a σt = 1 homogeneous half-space using the
// exact HG+Draine phase and albedo the runtime path uses — the LUT, the phase and the transport
// can never disagree about the physics. Each photon walks by delta tracking, scatters with the
// phase, and deposits its weight at every event from the SECOND one on: order 1 is carried
// exactly by the runtime NEE, and charging the LUT for it too would double-count. Fluence is
// normalised by 1/(4π) at bake time so the runtime source term is simply σt_local × LUT — the
// isotropic re-emission approximation of the diffuse field (Wrenninge's multiple-scattering
// octaves solve the same closure; the MC replaces their per-octave folklore constants).
//
// Static: depends only on droplet size and albedo, never on weather, so it bakes once per
// session (rebaked when the config changes — see RtCloudLut).
import world_common;
import cloud_field;

import cloud_lut_bindings;

[[vk::push_constant]] PushAddr pcAddr;

// Keep in sync with RtCloudLut.MS_LUT_SIZE / MS_LUT_TAU_MAX.
static const int MS_LUT_SIZE = 32;
static const float MS_LUT_TAU_MAX = 32.0;
static const int PHOTON_REPEATS = 2;
static const int PHOTONS_PER_REPEAT = 128;

// Delta tracking in the σt = 1 half-space: exponential free path. RNG comes from cloud_field's
// self-contained stream — importing math.slang would drag the world bindings into this pass.
float freePath(inout uint s) {
    return -log(max(cloudRndf(s), 1.0e-7));
}

// Cosine-weighted downward direction (+z is depth-down): the Malley sample from math.slang,
// inlined to keep this module free of the world binding graph.
float3 cosineDown(inout uint s) {
    float u1 = cloudRndf(s);
    float u2 = cloudRndf(s);
    float r = sqrt(u1);
    float phi = 2.0 * CLOUD_PI * u2;
    return normalize(float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1))));
}

// One photon walk. dir enters the medium (z = depth below the shell top, positive into it);
// deposits w at every event of order ≥ 2 into bins[tau]. Returns nothing — absorption and
// escape terminate the walk.
void walkPhoton(float3 dir, float w, inout uint seed, MieParams mie, inout float bins[MS_LUT_SIZE]) {
    float3 pos = float3(0.0, 0.0, 0.0);
    int order = 0;
    for (int hop = 0; hop < 256; hop++) {
        pos += dir * freePath(seed);
        if (pos.z < 0.0 || pos.z > MS_LUT_TAU_MAX) {
            return; // escaped through the top / left the baked domain downward
        }
        order++;
        if (order >= 2) {
            int bin = min(int(pos.z / MS_LUT_TAU_MAX * float(MS_LUT_SIZE)), MS_LUT_SIZE - 1);
            bins[bin] += w;
        }
        w *= CLOUD_ALBEDO;
        if (cloudRndf(seed) > CLOUD_ALBEDO) {
            return; // absorbed — Russian roulette consistent with the weight update
        }
        dir = sampleMieDirection(dir, mie, seed);
    }
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= uint(MS_LUT_SIZE) || tid.y >= uint(MS_LUT_SIZE)) {
        return;
    }
    ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0]; // loaded for parity with the other bakes
    uint seedMix = tid.x * 1973u + tid.y * 9277u + 26699u;
    uint seed = cloudPcg(seedMix);

    float cosZenith = ((float(tid.x) + 0.5) / float(MS_LUT_SIZE)) * 2.0 - 1.0;
    float sinZenith = sqrt(max(0.0, 1.0 - cosZenith * cosZenith));
    int tauBin = int(tid.y); // v axis = optical-depth bin
    MieParams mie = mieParams(CLOUD_DROPLET_UM);

    float sunBins[MS_LUT_SIZE];
    float skyBins[MS_LUT_SIZE];
    for (int b = 0; b < MS_LUT_SIZE; b++) {
        sunBins[b] = 0.0;
        skyBins[b] = 0.0;
    }

    if (cosZenith > 0.05) {
        // ---- Sun/moon photons: unit illuminance entering the shell top at this zenith angle.
        for (int r = 0; r < PHOTON_REPEATS; r++) {
            for (int k = 0; k < PHOTONS_PER_REPEAT; k++) {
                float phi = 2.0 * CLOUD_PI * cloudRndf(seed);
                float3 dir = float3(sinZenith * cos(phi), sinZenith * sin(phi), cosZenith);
                walkPhoton(dir, 1.0, seed, mie, sunBins);
            }
        }
    }

    // ---- Ambient photons: unit-radiance isotropic sky over the upper hemisphere. Cosine-weighted
    // direction sampling makes each photon carry unit weight (L·cos/pdf = 1 for L = 1); +z is
    // depth-down, so the hemisphere sample enters the medium.
    for (int r = 0; r < PHOTON_REPEATS; r++) {
        for (int k = 0; k < PHOTONS_PER_REPEAT; k++) {
            float3 dir = cosineDown(seed);
            walkPhoton(dir, 1.0, seed, mie, skyBins);
        }
    }

    int photonCount = PHOTON_REPEATS * PHOTONS_PER_REPEAT;
    // 1/(4π): the diffuse field re-emits isotropically, so the per-unit-σs source term is
    // σt × φ / (4π); folding it here makes the runtime a plain multiply.
    float inverse = 1.0 / (4.0 * CLOUD_PI * float(photonCount));
    float sunFluence = sunBins[tauBin] * inverse;
    float skyFluence = skyBins[tauBin] * inverse;
    cloudMsLutImage[tid.xy] = float4(sunFluence.xxx, skyFluence);
}
