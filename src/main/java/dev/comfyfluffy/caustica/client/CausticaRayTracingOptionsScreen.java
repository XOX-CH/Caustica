package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "RTX 光追" (Ray Tracing) submenu of the Caustica-DLSS window. It holds path tracing, sampling,
 * bounces, entities, particles, render scale, and DLSS model options, each with its own reset button
 * that restores the factory default immediately.
 *
 * <p>Extends {@link CausticaOptionsScreen} so the transparent backdrop, the per-row reset-button
 * layout ({@link CausticaOptionsScreen#addOptionRow}) and the config-persisting {@code removed()}
 * behaviour are inherited unchanged.
 */
public final class CausticaRayTracingOptionsScreen extends CausticaOptionsScreen {
    public CausticaRayTracingOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.raytracing.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.raytracing.header"));
        boolean ptEnabled = CausticaConfig.Rt.ENABLED.value();
        for (ResetableOption row : RtRayTracingOptions.rayTracingOptions()) {
            boolean disabled = !ptEnabled && RtRayTracingOptions.isRrToggle(row.option());
            addOptionRow(row, disabled);
        }
    }
}