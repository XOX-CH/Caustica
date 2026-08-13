package dev.comfyfluffy.caustica.client;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CausticaWaterOptionsScreen extends CausticaOptionsScreen {
    public CausticaWaterOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.water.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.water.header"));
        for (ResetableOption row : RtWaterOptions.waterOptions()) {
            addOptionRow(row);
        }
    }
}