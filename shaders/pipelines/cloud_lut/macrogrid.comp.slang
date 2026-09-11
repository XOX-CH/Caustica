// Macrogrid bake, once per frame: reduces the weather coverage field to a 32×32 grid of per-cell
// maxima. This is the tracking accelerator — a column whose cell maximum is ~0 cannot hold cloud
// density anywhere along it (density is gated by coverage), so delta tracking and ratio tracking
// hop cell-to-cell and skip whole columns with one fetch. Not a strict per-voxel majorant: inside
// a covered cell the tracker falls back to the global CLOUD_MAX_SIGMA bound, which density never
// exceeds (the coverage remap never amplifies past it).
import world_common;
import cloud_field;

import cloud_lut_bindings;

[[vk::push_constant]] PushAddr pcAddr;

// Keep in sync with cloud.CLOUD_MACROGRID_CELLS and RtCloudLut.MACROGRID_SIZE.
static const int MACROGRID_SIZE = 32;
static const int WEATHER_TEXELS_PER_CELL = 4; // 128 weather texels / 32 cells

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= uint(MACROGRID_SIZE) || tid.y >= uint(MACROGRID_SIZE)) {
        return;
    }
    WorldPush push = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0];
    float2 windOffset = cloudWindDirStatic() * max(push.cloudLook0.y, 0.0) * push.cloudLook0.z;
    float bias = saturate(push.cloudLook0.x);
    float maxCoverage = 0.0;
    for (int y = 0; y < WEATHER_TEXELS_PER_CELL; y++) {
        for (int x = 0; x < WEATHER_TEXELS_PER_CELL; x++) {
            float2 sub = float2(float(x), float(y)) + 0.5;
            float2 uv = (float2(tid.xy) * WEATHER_TEXELS_PER_CELL + sub)
                    / float(WEATHER_TEXELS_PER_CELL * MACROGRID_SIZE);
            maxCoverage = max(maxCoverage, weatherCoverageField(uv, bias, windOffset));
        }
    }
    macroGridImage[tid.xy] = maxCoverage;
}
