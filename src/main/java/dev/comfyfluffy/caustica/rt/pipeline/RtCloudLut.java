package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.gen.PushAddrData;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;
import static dev.comfyfluffy.caustica.rt.pipeline.RtBindings.*;

/**
 * The volumetric cloud's three GPU-produced resources and the compute passes that bake them. See
 * {@code shaders/pipelines/world/cloud.slang} (transport) and {@code cloud_field.slang} (physics)
 * for the design — Nubis weather recipe, macrogrid majorant, Wrenninge-style multiple-scattering
 * LUT estimated by a small Monte Carlo of the exact runtime phase.
 *
 * <ul>
 *   <li><b>Weather map</b> 128x128 RGBA8 — per frame: R = coverage (config baseline + Minecraft
 *       rain, advected by wind), G = cloud type (0 stratus .. 1 cumulus). Rasterised from
 *       {@code weatherCoverageField}, the single source of truth.</li>
 *   <li><b>Macrogrid</b> 32x32 R16F — per frame: per-cell max coverage, the empty-space skip
 *       structure that lets both cloud walks hop empty columns with one fetch.</li>
 *   <li><b>Multiple-scattering LUT</b> 32x32 RGBA16F — static: RGB = higher-order (≥2)
 *       sun/moon fluence per unit illuminance, A = the same for unit-radiance sky light, both
 *       normalised by 1/(4π) so the runtime source term is σt × LUT. Depends only on droplet size
 *       and albedo, never on weather.</li>
 * </ul>
 *
 * <p>The storage views are also bound (as combined image samplers) into the world pipeline at
 * bindings 12..14 — see {@link RtPipeline#setCloudTextures}. Single-buffered with the same
 * barrier discipline as {@link RtSkyLut}: bake and trace share one submission, and the composite's
 * end-of-frame barrier orders the next overwrite against this frame's reads.
 */
public final class RtCloudLut {
    private static final String SHADER_DIR = "/caustica/shaders/pipelines/cloud_lut/";
    // Keep in lock-step with the same-named constants in shaders/pipelines/world/cloud_field.slang
    // and the cloud_lut compute passes.
    public static final int WEATHER_SIZE = 128;
    public static final int MACROGRID_SIZE = 32;
    public static final int MS_LUT_SIZE = 32;
    private static final int GROUP_SIZE = 8;

    private final RtContext ctx;
    private final RtImage weather;
    private final RtImage macroGrid;
    private final RtImage msLut;
    private final long repeatSampler;
    private final long clampSampler;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long weatherPipeline;
    private final long macroGridPipeline;
    private final long msLutPipeline;
    private boolean staticLutBaked;
    private boolean destroyed;

    private RtCloudLut(RtContext ctx, RtImage weather, RtImage macroGrid, RtImage msLut,
                       long repeatSampler, long clampSampler, long descriptorSetLayout,
                       long descriptorPool, long descriptorSet, long pipelineLayout,
                       long weatherPipeline, long macroGridPipeline, long msLutPipeline) {
        this.ctx = ctx;
        this.weather = weather;
        this.macroGrid = macroGrid;
        this.msLut = msLut;
        this.repeatSampler = repeatSampler;
        this.clampSampler = clampSampler;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.weatherPipeline = weatherPipeline;
        this.macroGridPipeline = macroGridPipeline;
        this.msLutPipeline = msLutPipeline;
    }

    public static RtCloudLut create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        RtImage weather = ctx.createStorageImage(WEATHER_SIZE, WEATHER_SIZE,
                VK10.VK_FORMAT_R8G8B8A8_UNORM, "cloud weather map");
        RtImage macroGrid = ctx.createStorageImage(MACROGRID_SIZE, MACROGRID_SIZE,
                VK10.VK_FORMAT_R16_SFLOAT, "cloud macrogrid");
        RtImage msLut = ctx.createStorageImage(MS_LUT_SIZE, MS_LUT_SIZE,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "cloud multiple-scattering LUT");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Two samplers. The weather map and the macrogrid wrap via frac() in the shader, so
            // their sampler must REPEAT: bilinear filtering across the tile boundary has to
            // blend texel N-1 with texel 0 — CLAMP would flatten a half-texel band on each side
            // and cut a vertical corridor through the cloud field at every tile edge. The MS LUT
            // is a parameter-space table whose opposite edges are unrelated, so it stays CLAMP.
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .minLod(0.0f).maxLod(0.0f);
            LongBuffer handle = stack.mallocLong(1);
            check(VK10.vkCreateSampler(vk, samplerInfo, null, handle), "vkCreateSampler(cloud repeat)");
            long repeatSampler = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, repeatSampler, "cloud repeat sampler");
            samplerInfo.addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            check(VK10.vkCreateSampler(vk, samplerInfo, null, handle), "vkCreateSampler(cloud clamp)");
            long clampSampler = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, clampSampler, "cloud clamp sampler");

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                bindings.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            check(VK10.vkCreateDescriptorSetLayout(vk, layoutInfo, null, handle),
                    "vkCreateDescriptorSetLayout(cloud LUT)");
            long descriptorSetLayout = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT,
                    descriptorSetLayout, "cloud LUT descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(3);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, poolInfo, null, handle),
                    "vkCreateDescriptorPool(cloud LUT)");
            long descriptorPool = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL,
                    descriptorPool, "cloud LUT descriptor pool");

            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer setHandle = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, allocateInfo, setHandle),
                    "vkAllocateDescriptorSets(cloud LUT)");
            long descriptorSet = setHandle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET,
                    descriptorSet, "cloud LUT descriptor set");

            // Same inline address block the sky LUT bakes use: each pass dereferences the frame's
            // WorldPush through pcAddr.worldPushAddr for coverage bias, wind and cloud time.
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0).size(PushAddrData.BYTE_SIZE);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, pipelineLayoutInfo, null, handle),
                    "vkCreatePipelineLayout(cloud LUT)");
            long pipelineLayout = handle.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT,
                    pipelineLayout, "cloud LUT pipeline layout");

            long weatherPipeline = createComputePipeline(ctx, stack, pipelineLayout,
                    "weather.comp.spv", "cloud weather pipeline");
            long macroGridPipeline = createComputePipeline(ctx, stack, pipelineLayout,
                    "macrogrid.comp.spv", "cloud macrogrid pipeline");
            long msLutPipeline = createComputePipeline(ctx, stack, pipelineLayout,
                    "mslut.comp.spv", "cloud multiple-scattering pipeline");

            VkDescriptorImageInfo.Buffer images = VkDescriptorImageInfo.calloc(3, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            RtImage[] storageImages = {weather, macroGrid, msLut};
            for (int i = 0; i < storageImages.length; i++) {
                images.get(i).imageView(storageImages[i].view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(images.address(i), 1));
            }
            VK10.vkUpdateDescriptorSets(vk, writes, null);

            return new RtCloudLut(ctx, weather, macroGrid, msLut, repeatSampler, clampSampler,
                    descriptorSetLayout, descriptorPool, descriptorSet, pipelineLayout,
                    weatherPipeline, macroGridPipeline, msLutPipeline);
        }
    }

    public long repeatSampler() {
        return repeatSampler;
    }

    public long clampSampler() {
        return clampSampler;
    }

    public long weatherView() {
        return weather.view;
    }

    public long msLutView() {
        return msLut.view;
    }

    public long macroGridView() {
        return macroGrid.view;
    }

    /**
     * Record this frame's cloud bake work: weather map, then the macrogrid that reduces it (the
     * macrogrid's correctness depends on the weather write completing first), then — once per
     * session — the multiple-scattering LUT. Must be recorded before the trace, with a barrier
     * after (the caller's) so raygen and visibility sample this frame's weather.
     */
    public void record(VkCommandBuffer cmd, long worldPushAddress) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "cloud LUTs")) {
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PushAddrData.BYTE_SIZE);
            new PushAddrData(worldPushAddress).write(push);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);

            dispatch(cmd, stack, weatherPipeline, WEATHER_SIZE, WEATHER_SIZE);
            dispatch(cmd, stack, macroGridPipeline, MACROGRID_SIZE, MACROGRID_SIZE);
            if (!staticLutBaked) {
                dispatch(cmd, stack, msLutPipeline, MS_LUT_SIZE, MS_LUT_SIZE);
                staticLutBaked = true;
            }
        }
    }

    private void dispatch(VkCommandBuffer cmd, MemoryStack stack, long pipeline, int width, int height) {
        VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        VK10.vkCmdDispatch(cmd, (width + GROUP_SIZE - 1) / GROUP_SIZE,
                (height + GROUP_SIZE - 1) / GROUP_SIZE, 1);
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, msLutPipeline, null);
        VK10.vkDestroyPipeline(vk, macroGridPipeline, null);
        VK10.vkDestroyPipeline(vk, weatherPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        VK10.vkDestroySampler(vk, repeatSampler, null);
        VK10.vkDestroySampler(vk, clampSampler, null);
        msLut.destroy();
        macroGrid.destroy();
        weather.destroy();
        destroyed = true;
    }

    private static long createComputePipeline(RtContext ctx, MemoryStack stack, long layout,
                                              String shader, String label) {
        VkDevice vk = ctx.vk();
        long module = loadModule(vk, stack, SHADER_DIR + shader);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, label + " module");
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                .module(module).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
        info.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer handle = stack.mallocLong(1);
        check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, info, null, handle),
                "vkCreateComputePipelines(" + shader + ")");
        VK10.vkDestroyShaderModule(vk, module, null);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, handle.get(0), label);
        return handle.get(0);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String resource) {
        byte[] bytes;
        try (InputStream input = RtCloudLut.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + resource);
            }
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + resource, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code);
            LongBuffer module = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, moduleInfo, null, module),
                    "vkCreateShaderModule(" + resource + ")");
            return module.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
