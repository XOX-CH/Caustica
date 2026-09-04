package dev.comfyfluffy.caustica.client;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "太阳位置" (Sun Position) submenu of the Caustica-DLSS window. It holds the sun angle override
 * toggle and the degree slider, each with its own reset button that restores the factory default
 * immediately.
 *
 * <p>Extends {@link CausticaOptionsScreen} so the transparent backdrop, the per-row reset-button
 * layout ({@link CausticaOptionsScreen#addOptionRow}) and the config-persisting {@code removed()}
 * behaviour are inherited unchanged.
 */
public final class CausticaSunPositionOptionsScreen extends CausticaOptionsScreen {
    public CausticaSunPositionOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.sunPosition.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.sunPosition.header"));
        for (ResetableOption row : RtSunPositionOptions.sunPositionOptions()) {
            addOptionRow(row);
        }
    }
}