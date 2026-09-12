// Cloud field: the physics of the cloud medium that never touches a texture binding — the
// HG+Draine approximate-Mie phase, the procedural noise stack, the wind model, and the weather
// coverage/type field generation. Split from cloud.slang so the LUT bake passes (cloud_lut/*.comp)
// can import this module without pulling the world pipeline's descriptor layout into a compute
// shader (binding sets would collide with the bake's own resources).
//
// See cloud.slang for the transport design notes (Nubis shape recipe, Volumetric ReSTIR baseline,
// Tier S multiple-scattering closure).


// Self-contained: this module deliberately imports nothing, so any shader can use it without
// inheriting a descriptor layout. It carries its own PI and PCG stream (same constants as
// math.slang's, whose world_core import would drag the world bindings into compute passes).
public static const float CLOUD_PI = 3.14159265359;

public uint cloudPcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

public float cloudRndf(inout uint s) {
    return float(cloudPcg(s) >> 8u) * (1.0 / 16777216.0);
}

// ---- Tunables -----------------------------------------------------------------------------------

// Peak extinction of a fully dense cloud voxel, 1/blocks. Real cumulus is 0.2–1.0 /block at
// 1 block = 10 m; the default sits mid-range so thick cores read near-opaque while edges stay
// wispy. cloudLook1.z (densityScale) multiplies this.
public static const float CLOUD_MAX_SIGMA = 0.35;
// Single-scattering albedo of cloud water in the visible — almost 1, kept slightly under so
// Russian roulette terminates and the deepest interiors pick up a hint of grey.
public static const float CLOUD_ALBEDO = 0.96;
// Mean droplet diameter for the Mie fit, microns (10–15 µm is typical boundary-layer cumulus).
public static const float CLOUD_DROPLET_UM = 12.0;
// Weather texture spans this many blocks per tile (wraps in XZ). 8192 ≈ 82 km — the Nubis
// weather-cell order of magnitude at Minecraft scale. Keep the cell count in sync with
// RtCloudLut.MACROGRID_SIZE.
public static const float CLOUD_WEATHER_SPAN = 8192.0;
public static const float CLOUD_MACROGRID_CELLS = 32.0;
public static const float CLOUD_MACRO_CELL = CLOUD_WEATHER_SPAN / CLOUD_MACROGRID_CELLS;
// Shape/erosion noise frequencies, cycles per block.
public static const float CLOUD_SHAPE_FREQ = 1.0 / 110.0;
public static const float CLOUD_EROSION_FREQ = 1.0 / 15.0;
public static const float CLOUD_EROSION_STRENGTH = 0.42;
// Multiplier on the MS LUT's higher-order and ambient terms only. The order-1 NEE term is not
// scaled by it (bake normalisation calibration).
public static const float CLOUD_MS_STRENGTH = 1.0;
// Optical-depth ceiling of the MS LUT's v axis, in the same σt units the runtime's tau uses.
// cloud_lut/mslut.comp bins the baked fluence against it and cloud.slang normalises the runtime
// lookup by the same number, so the two can never disagree.
public static const float CLOUD_MS_LUT_TAU_MAX = 32.0;

// ---- Phase: HG + Draine approximate Mie (Jendersie & d'Eon 2023) ---------------------------------
//
// phi(theta) = (1 - wD)·HG(u, gHG) + wD·Draine(u, gD, a), parameters fitted over water-droplet
// diameters 5..50 µm (paper eqs. 4–7). The Draine sampler is a port of NVIDIA's reference
// implementation (docs/papers/draine.hlsl, MIT license).

public struct MieParams {
    public float gHg;
    public float gDraine;
    public float alpha;
    public float wDraine;
};

public MieParams mieParams(float diameterUm) {
    MieParams m;
    m.gHg = exp(-0.0990567 / pow(diameterUm, 1.67154));
    m.gDraine = exp(-2.20679 / (diameterUm + 3.91029) - 0.428934);
    m.alpha = exp(3.62489 - 8.29288 / (diameterUm + 5.52825));
    m.wDraine = exp(-0.599085 / pow(diameterUm, 0.641583) - 0.665888);
    return m;
}

public float hgPhaseEval(float u, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * CLOUD_PI * pow(max(1.0 + g2 - 2.0 * g * u, 1.0e-7), 1.5));
}

public float drainePhaseEval(float u, float g, float a) {
    float num = (1.0 - g * g) * (1.0 + a * u * u);
    float denom = 4.0 * (1.0 + a * (1.0 + 2.0 * g * g) / 3.0) * CLOUD_PI
            * pow(max(1.0 + g * g - 2.0 * g * u, 1.0e-7), 1.5);
    return num / denom;
}

public float miePhaseEval(float u, MieParams m) {
    return lerp(hgPhaseEval(u, m.gHg), drainePhaseEval(u, m.gDraine, m.alpha), m.wDraine);
}

// Exact deflection cosine of the HG lobe (inverse CDF).
public float sampleHgCos(float xi, float g) {
    float g2 = g * g;
    return (1.0 + g2 - pow((1.0 - g2) / max(1.0 - g + 2.0 * g * xi, 1.0e-7), 2.0)) / (2.0 * g);
}

// Exact deflection cosine of the Draine lobe: closed-form solution of the cubic CDF inversion.
// Port of draine.hlsl sampleDraineCos, with the numerically fragile edges guarded.
public float sampleDraineCos(float xi, float g, float a) {
    float g2 = g * g;
    float g3 = g * g2;
    float g4 = g2 * g2;
    float g6 = g2 * g4;
    float pgp12 = (1.0 + g2) * (1.0 + g2);
    float T1 = (-1.0 + g2) * (4.0 * g2 + a * pgp12);
    float T1a = -a + a * g4;
    float T1a3 = T1a * T1a * T1a;
    float T2 = -1296.0 * (-1.0 + g2) * (a - a * g2) * T1a * (4.0 * g2 + a * pgp12);
    float T3 = 3.0 * g2 * (1.0 + g * (-1.0 + 2.0 * xi))
            + a * (2.0 + g2 + g3 * (1.0 + 2.0 * g2) * (-1.0 + 2.0 * xi));
    float T4a = 432.0 * T1a3 + T2 + 432.0 * (a - a * g2) * T3 * T3;
    float T4b = -144.0 * a * g2 + 288.0 * a * g4 - 144.0 * a * g6;
    float T4b3 = T4b * T4b * T4b;
    float T4 = T4a + sqrt(max(-4.0 * T4b3 + T4a * T4a, 0.0));
    float T4p3 = pow(max(T4, 1.0e-12), 1.0 / 3.0);
    float denom = a - a * g2;
    if (abs(denom) < 1.0e-9) {
        return sampleHgCos(xi, g); // degenerate fit: the HG lobe is the whole answer
    }
    float T6 = (2.0 * T1a + (48.0 * pow(2.0, 1.0 / 3.0)
            * (-(a * g2) + 2.0 * a * g4 - a * g6)) / T4p3
            + T4p3 / (3.0 * pow(2.0, 1.0 / 3.0))) / denom;
    float T5 = 6.0 * (1.0 + g2) + T6;
    float inner = 6.0 * (1.0 + g2) - (8.0 * T3) / (a * (-1.0 + g2) * sqrt(max(T5, 1.0e-9))) - T6;
    float result = (1.0 + g2 - pow(max(-0.5 * sqrt(max(T5, 0.0)) + sqrt(max(inner, 0.0)) / 2.0, 0.0), 2.0))
            / (2.0 * g);
    return clamp(result, -1.0, 1.0);
}

// Sample the blended phase: pick a lobe by its mixture weight, invert that lobe's CDF, rotate
// into world space about the incoming travel direction.
public float3 sampleMieDirection(float3 incomingDir, MieParams m, inout uint s) {
    float cosTheta = cloudRndf(s) < m.wDraine
            ? sampleDraineCos(cloudRndf(s), m.gDraine, m.alpha)
            : sampleHgCos(cloudRndf(s), m.gHg);
    cosTheta = clamp(cosTheta, -1.0, 1.0);
    float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));
    float phi = 2.0 * CLOUD_PI * cloudRndf(s);
    float3 t = normalize(abs(incomingDir.y) < 0.99
            ? cross(incomingDir, float3(0.0, 1.0, 0.0))
            : cross(incomingDir, float3(1.0, 0.0, 0.0)));
    float3 b = cross(incomingDir, t);
    return normalize(sinTheta * cos(phi) * t + sinTheta * sin(phi) * b + cosTheta * incomingDir);
}

// ---- Noise --------------------------------------------------------------------------------------

// Hash-based 3D value noise with quintic smoothing. Not a true Perlin gradient — the coverage
// remap hides the difference and value noise costs one hash per lattice corner.
public float cloudHash13(float3 p) {
    p = frac(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return frac((p.x + p.y) * p.z);
}

public float valueNoise3(float3 p) {
    float3 i = floor(p);
    float3 f = frac(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n000 = cloudHash13(i);
    float n100 = cloudHash13(i + float3(1.0, 0.0, 0.0));
    float n010 = cloudHash13(i + float3(0.0, 1.0, 0.0));
    float n110 = cloudHash13(i + float3(1.0, 1.0, 0.0));
    float n001 = cloudHash13(i + float3(0.0, 0.0, 1.0));
    float n101 = cloudHash13(i + float3(1.0, 0.0, 1.0));
    float n011 = cloudHash13(i + float3(0.0, 1.0, 1.0));
    float n111 = cloudHash13(i + float3(1.0, 1.0, 1.0));
    return lerp(lerp(lerp(n000, n100, f.x), lerp(n010, n110, f.x), f.y),
            lerp(lerp(n001, n101, f.x), lerp(n011, n111, f.x), f.y), f.z);
}

// 27-cell Worley (full 3x3x3 search), single jitter per cell, distance to the nearest feature
// point, remapped so 0 = cell centre and 1 = far from every feature. One hash triplet per
// neighbour.
public float worley3x27(float3 p) {
    float3 i = floor(p);
    float3 f = frac(p);
    float best = 8.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                float3 cell = float3(float(x), float(y), float(z));
                float h = cloudHash13(i + cell);
                float3 jitter = float3(h, cloudHash13(i + cell + 17.17),
                        cloudHash13(i + cell + 43.43));
                float3 d = cell + jitter - f;
                best = min(best, dot(d, d));
            }
        }
    }
    return saturate(1.0 - sqrt(best) * 0.9);
}

// 8-cell Worley (2x2x2 neighbourhood): drops the far corners of the cell search, a third of the
// hashes. The field reads smoother (the nearest feature can hide in a skipped corner), which
// erosion tolerates — it only subtracts, never adds. The balanced quality tier's erosion.
public float worley3x8(float3 p) {
    float3 i = floor(p - 0.5);
    float best = 8.0;
    for (int z = 0; z <= 1; z++) {
        for (int y = 0; y <= 1; y++) {
            for (int x = 0; x <= 1; x++) {
                float3 cell = float3(float(x), float(y), float(z));
                float3 jitter = float3(cloudHash13(i + cell),
                        cloudHash13(i + cell + 17.17),
                        cloudHash13(i + cell + 43.43));
                float3 d = (i + cell + jitter) - p;
                best = min(best, dot(d, d));
            }
        }
    }
    return saturate(1.0 - sqrt(best) * 0.9);
}

public float fbm3(float3 p, int octaves) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (int i = 0; i < octaves; i++) {
        sum += amp * valueNoise3(p);
        norm += amp;
        p = p * 2.03 + float3(19.19, 7.7, 3.3);
        amp *= 0.5;
    }
    return sum / max(norm, 1.0e-6);
}

// Remap v from [lo, hi] onto [0, 1] with saturation. Every call site wants the unit range, so the
// general two-range form is not carried.
public float cloudRemap(float v, float lo, float hi) {
    return saturate((v - lo) / max(hi - lo, 1.0e-6));
}

// Constant trade-wind direction: stability across frames matters more than variety, and the
// weather bake advects with the same vector so shapes move coherently. Stored pre-normalised
// (normalize(float2(0.86, 0.51))) because Slang's normalize is not a constant expression.
public static const float2 CLOUD_WIND_DIR = float2(0.860129, 0.510077);

// Horizontal weather-domain advection in blocks, from (wind speed, blocks/s; cloud time, s).
// One implementation for the runtime shape scroll and both weather bakes, because the bake passes
// hold their own WorldPush copy and cannot read the runtime's global — the caller passes the pair
// explicitly so all three sites cannot drift apart.
public float2 cloudWindOffset(float2 windSpeedAndTime) {
    return CLOUD_WIND_DIR * max(windSpeedAndTime.x, 0.0) * windSpeedAndTime.y;
}

// ---- Weather field ------------------------------------------------------------------------------

// Tileable 2D value noise for the weather field: lattice hashes wrap at `period` so the bake's
// advected tile matches the runtime's frac() wrap exactly.
float weatherHash(int2 cell, int period) {
    int2 wrapped = ((cell % period) + period) % period;
    return cloudHash13(float3(float2(wrapped), 7.77));
}

float weatherValueNoise(float2 p, int period) {
    float2 i = floor(p);
    float2 f = frac(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n00 = weatherHash(int2(i), period);
    float n10 = weatherHash(int2(i) + int2(1, 0), period);
    float n01 = weatherHash(int2(i) + int2(0, 1), period);
    float n11 = weatherHash(int2(i) + int2(1, 1), period);
    return lerp(lerp(n00, n10, f.x), lerp(n01, n11, f.x), f.y);
}

/**
 * The weather coverage field over the tile's unit UV — THE shared source of truth. The bake
 * rasterises it into the weather texture (already advected by wind and biased by config + MC
 * rain); the macrogrid bake reduces it to per-cell maxima; the runtime only reads the texture.
 * `bias` comes in via worldPush.cloudLook0.x.
 */
public float weatherCoverageField(float2 uv, float bias, float2 windOffsetBlocks) {
    float2 advected = uv + windOffsetBlocks / CLOUD_WEATHER_SPAN;
    float2 p = advected * 8.0; // 8 tile-scale features across the weather cell
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    int period = 8;
    for (int i = 0; i < 5; i++) {
        sum += amp * weatherValueNoise(p, period);
        norm += amp;
        p = p * 2.0 + 13.7;
        period *= 2;
        amp *= 0.55;
    }
    float field = sum / norm;
    // Coverage bias lifts the whole field (stormy weather), then a soft contrast curve separates
    // clear sky from cloud masses the way the Nubis weather map's low-frequency mask does.
    field = saturate((field - (1.0 - bias * 0.85)) / max(bias * 0.85, 0.05));
    return saturate(field * 1.35);
}

// Cloud type field: very low frequency, 0 = stratus .. 1 = cumulus.
public float weatherTypeField(float2 uv, float2 windOffsetBlocks) {
    float2 advected = uv + windOffsetBlocks * 0.6 / CLOUD_WEATHER_SPAN;
    float field = weatherValueNoise(advected * 3.0, 3);
    field = weatherValueNoise(advected * 3.0 + field * 0.7, 3);
    return saturate((field - 0.25) / 0.5);
}

// Height profile: flat eroded bases, rounded tops; type stretches the vertical extent from thin
// stratus to full cumulus. 0..1 vertical support at this height fraction.
public float heightProfile(float hf, float type) {
    float base = cloudRemap(hf, 0.0, 0.04 + 0.10 * type);
    float topStart = lerp(0.28, 0.62, type);
    float topEnd = lerp(0.42, 1.0, type);
    float top = 1.0 - cloudRemap(hf, topStart, topEnd);
    return base * top;
}
