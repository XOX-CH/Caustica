// Weather-map bake, once per frame. Rasterises cloud_field's weatherCoverageField /
// weatherTypeField — the single source of truth for the Nubis-style weather texture (R coverage,
// G type) — already advected by wind and biased by config + Minecraft rain (both arrive through
// the frame's WorldPush). The runtime samples this texture verbatim; the macrogrid bake reduces
// the same field to per-cell maxima, so the LUT, the majorant and the frame can never disagree.
import world_common;
import cloud_field;

import cloud_lut_bindings;

[[vk::push_constant]] PushAddr pcAddr;

// Keep in sync with RtCloudLut.WEATHER_SIZE.
static const int WEATHER_SIZE = 128;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= uint(WEATHER_SIZE) || tid.y >= uint(WEATHER_SIZE)) {
        return;
    }
    WorldPush push = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0];
    float2 windOffset = cloudWindOffset(push.cloudLook0.y);
    float2 uv = (float2(tid.xy) + 0.5) / float(WEATHER_SIZE);
    float coverage = weatherCoverageField(uv, saturate(push.cloudLook0.x), windOffset);
    float type = weatherTypeField(uv, windOffset);
    float lift = weatherHeightLiftField(uv, windOffset); // B channel → heightProfile relief
    weatherImage[tid.xy] = float4(coverage, type, lift, 1.0);
}
