// Macrogrid bake, once per frame: reduces the weather coverage field to a 32×32 grid of per-cell
// maxima. This is the empty-space skip structure — a column whose cell maximum is ~0 cannot hold
// cloud density anywhere along it (density is gated by coverage), so the primary walk and the
// shadow march hop cell-to-cell and skip whole columns with one fetch instead of evaluating σt
// stride by stride.
import world_common;
import cloud_field;

import cloud_lut_bindings;

[[vk::push_constant]] PushAddr pcAddr;

// Keep in sync with cloud_field.CLOUD_MACROGRID_CELLS and RtCloudLut.MACROGRID_SIZE.
static const int MACROGRID_SIZE = 32;
static const int WEATHER_TEXELS_PER_CELL = 4; // 128 weather texels / 32 cells

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= uint(MACROGRID_SIZE) || tid.y >= uint(MACROGRID_SIZE)) {
        return;
    }
    WorldPush push = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0];
    float2 windOffset = cloudWindOffset(push.cloudLook0.y);
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
