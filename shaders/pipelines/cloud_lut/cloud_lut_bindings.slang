module cloud_lut_bindings;

// Cloud bake resources: the weather map, the per-column majorant grid, and the multiple-scattering
// LUT, all produced by compute passes in this directory. Separate descriptor set from the world
// pipeline — the world pipeline binds the SAMPLED views of these images at bindings 12..14
// (see shaders/pipelines/world/bindings.slang), while these passes write them as storage images.
// Keep formats in lock-step with RtCloudLut.java.
[[vk::binding(0, 0)]] [format("rgba8")] public RWTexture2D<float4> weatherImage;
[[vk::binding(1, 0)]] [format("r16f")] public RWTexture2D<float> macroGridImage;
[[vk::binding(2, 0)]] [format("rgba16f")] public RWTexture2D<float4> cloudMsLutImage;
