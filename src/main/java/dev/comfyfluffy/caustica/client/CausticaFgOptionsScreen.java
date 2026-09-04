package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "DLSS插帧-支持40系" (DLSS Frame Generation) submenu of the Caustica-DLSS window. It holds all
 * runtime-tunable Frame Generation options from {@link RtVideoOptions#fgOptions()}, each with its own
 * reset button that restores the factory default immediately.
 *
 * <p>Extends {@link CausticaOptionsScreen} so the transparent backdrop, the per-row reset-button
 * layout ({@link CausticaOptionsScreen#addOptionRow}) and the config-persisting {@code removed()}
 * behaviour are inherited unchanged.
 */
public final class CausticaFgOptionsScreen extends CausticaOptionsScreen {
    public CausticaFgOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.fg.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.fg.header"));
        boolean capLocked = CausticaConfig.Rt.SYNC_FRAME_CAP.value();
        for (ResetableOption row : RtVideoOptions.fgOptions()) {
            boolean disabled = capLocked && row.option() == RtVideoOptions.fpsCapOption();
            addOptionRow(row, disabled);
        }
        // Let FG-related listeners (sync toggle, FG enable, multiplier) refresh the derived cap row live.
        RtVideoOptions.setUiRefresh(this::refreshFpsCapRow);
    }

    /**
     * Re-syncs the FG Sync toggle and cap slider widgets after a related setting changed while the menu
     * is open. The cap slider's displayed value derives from those settings (refresh rate ÷ multiplier
     * while synced), so its model and widget must follow without reopening the window. The slider (and
     * its reset button) is locked while FG Sync is on.
     */
    private void refreshFpsCapRow() {
        boolean capLocked = CausticaConfig.Rt.SYNC_FRAME_CAP.value();
        if (this.fpsCapControl != null) {
            this.fpsCapControl.active = !capLocked;
        }
        if (this.fpsCapReset != null) {
            this.fpsCapReset.active = !capLocked;
        }
        RtVideoOptions.syncCapDisplayValue();
        OptionInstance<Boolean> sync = RtVideoOptions.fgSyncOption();
        if (sync != null) {
            this.list.resetOption(sync);
        }
        OptionInstance<Double> cap = RtVideoOptions.fpsCapOption();
        if (cap != null) {
            this.list.resetOption(cap);
        }
    }
}