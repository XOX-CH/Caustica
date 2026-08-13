package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "细节调整" (Detail Tuning) submenu of the Caustica-DLSS window. It holds all runtime-tunable
 * Ray Tracing settings from {@link RtVideoOptions#runtimeOptions()}, each with its own reset button
 * that restores the factory default immediately.
 *
 * <p>Extends {@link CausticaOptionsScreen} so the transparent backdrop, the per-row reset-button
 * layout ({@link CausticaOptionsScreen#addOptionRow}) and the config-persisting {@code removed()}
 * behaviour are inherited unchanged.
 */
public final class CausticaDetailOptionsScreen extends CausticaOptionsScreen {
    public CausticaDetailOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.detail.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.detail.header"));
        for (ResetableOption row : RtDetailOptions.detailOptions()) {
            addOptionRow(row);
        }
    }
}