package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Inject(method = "render", at = @At("HEAD"))
	private void caustica$allowVanillaRender(
			GraphicsResourceAllocator resourceAllocator,
			DeltaTracker deltaTracker,
			boolean renderOutline,
			CameraRenderState cameraState,
			Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog,
			Vector4f fogColor,
			boolean shouldRenderSky,
			CallbackInfo ci) {
		// We always let the vanilla LevelRenderer.render() run to completion so that
		// compileSections(), uploadTerrainBuffersToGpu(), and updateSectionOcclusion()
		// execute every frame. Without this, chunks loaded while PT is active never get
		// their vanilla meshes compiled, making them invisible when PT is toggled off.
		//
		// The RT composite (WorldRenderScaler.end() -> RtComposite.composite()) runs
		// afterward and overwrites the main target with the path-traced output, so the
		// vanilla rendering is never presented — it only serves chunk compilation.
		Runnable playerCompiledSectionCallback = this.levelRenderState.playerCompiledSectionCallback;
		if (VanillaRenderController.rtRuntimeWorkRequested() && playerCompiledSectionCallback != null) {
			if (RtTerrain.isSectionReady(cameraState.blockPos)) {
				playerCompiledSectionCallback.run();
				VanillaRenderController.INSTANCE.markRtPlayerSectionReady();
			}
		}
	}

	/**
	 * Skip the expensive GPU frame graph execution when PT is active. The vanilla
	 * world rendering is never presented (the RT composite overwrites it), so the
	 * frame graph's GPU work is pure waste. The chunk compilation phases
	 * (compileSections, uploadTerrainBuffersToGpu, updateSectionOcclusion) that
	 * run after the execute() call in the render method are unaffected.
	 */
	@WrapOperation(
			method = "render",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V")
	)
	private void caustica$skipFrameGraphExecute(FrameGraphBuilder frameGraph, GraphicsResourceAllocator allocator,
			FrameGraphBuilder.Inspector inspector, Operation<Void> original) {
		if (!VanillaRenderController.rtRuntimeWorkRequested()) {
			original.call(frameGraph, allocator, inspector);
		}
		// When PT is active, skip the frame graph execution entirely.
	}
}
