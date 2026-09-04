package dev.comfyfluffy.caustica.client;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "天气细节" (Weather Detail) submenu of the Caustica-DLSS window. It contains submenu buttons for
 * weather-related settings, currently the rain detail submenu. Future weather features (snow, fog, etc.)
 * would be added here as additional submenu buttons.
 *
 * <p>Extends {@link CausticaOptionsScreen} so the transparent backdrop, and the config-persisting
 * {@code removed()} behaviour are inherited unchanged.
 */
public final class CausticaWeatherOptionsScreen extends CausticaOptionsScreen {
    public CausticaWeatherOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("caustica.options.weather.title"));
    }

    @Override
    protected void addOptions() {
        this.list.addHeader(Component.translatable("caustica.options.weather.header"));
        this.list.addBig(Button.builder(
                Component.translatable("caustica.options.weather.rain.open"),
                button -> this.minecraft.gui.setScreen(new CausticaRainOptionsScreen(this, this.options)))
                .build());
    }
}