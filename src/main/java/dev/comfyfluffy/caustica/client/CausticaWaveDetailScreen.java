package dev.comfyfluffy.caustica.client;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Sub-menu for per-band wave amplitude control.  Shows the wave controls
 * (strength, speed, preset, meander, dispersion, count) followed by the
 * per-band amplitude multipliers, all in a single-column centered layout.
 */
public final class CausticaWaveDetailScreen extends CausticaOptionsScreen {
    public CausticaWaveDetailScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.waveBand.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.waveBand.header"));
        for (ResetableOption row : RtWaveBandOptions.waveOptions()) {
            addOptionRow(row);
        }
        this.list.addHeader(Component.translatable("caustica.options.waveBand.amplitudeHeader"));
        for (ResetableOption row : RtWaveBandOptions.bandOptions()) {
            addOptionRow(row);
        }
    }
}