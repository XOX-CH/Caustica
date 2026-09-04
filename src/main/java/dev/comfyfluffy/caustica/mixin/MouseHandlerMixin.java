package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.RtReflex;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Input-flash record for Reflex click-to-photon diagnostics. The Vulkan spec wants the TRIGGER_FLASH
 * marker set on left mouse click inside the simulation window, so the raw GLFW callback only records the
 * press ({@link RtReflex#markInputFlash}) — the marker itself is flushed at the top of the next
 * {@code runTick} (see MinecraftMixin), right after SIMULATION_START. No Vulkan calls happen on this
 * path, so it stays valid regardless of Reflex/swapchain state.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"))
    private void caustica$reflexRecordInputFlash(long windowPointer, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (action == GLFW.GLFW_PRESS && buttonInfo.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            RtReflex.INSTANCE.markInputFlash();
        }
    }
}
