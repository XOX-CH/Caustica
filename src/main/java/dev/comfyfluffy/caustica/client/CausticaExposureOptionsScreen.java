package dev.comfyfluffy.caustica.client;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "曝光" (Exposure) submenu of the Caustica-DLSS window. It holds the exposure mode selector,
 * manual EV slider, and min/max EV bounds, each with its own reset button.
 *
 * <p>Extends {@link CausticaOptionsScreen} so the transparent backdrop, the per-row reset-button
 * layout ({@link CausticaOptionsScreen#addOptionRow}) and the config-persisting {@code removed()}
 * behaviour are inherited unchanged.
 */
public final class CausticaExposureOptionsScreen extends CausticaOptionsScreen {
    public CausticaExposureOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.exposure.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.exposure.header"));
        for (ResetableOption row : RtExposureOptions.exposureOptions()) {
            addOptionRow(row);
        }
    }
}