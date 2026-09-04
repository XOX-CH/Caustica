package dev.comfyfluffy.caustica.client;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "雨细节" (Rain Detail) submenu of the Caustica-DLSS window. It holds all rain particle controls
 * from {@link RtRainOptions#rainOptions()}, each with its own reset button that restores the factory
 * default immediately.
 *
 * <p>Extends {@link CausticaOptionsScreen} so the transparent backdrop, the per-row reset-button
 * layout ({@link CausticaOptionsScreen#addOptionRow}) and the config-persisting {@code removed()}
 * behaviour are inherited unchanged.
 */
public final class CausticaRainOptionsScreen extends CausticaOptionsScreen {
    public CausticaRainOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.weather.rain.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.weather.rain.header"));
        for (ResetableOption row : RtRainOptions.rainOptions()) {
            addOptionRow(row);
        }
    }
}