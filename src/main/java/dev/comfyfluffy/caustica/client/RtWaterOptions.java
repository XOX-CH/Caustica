package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the "水体细节" (Water Detail) submenu of the
 * Caustica-DLSS window ({@link CausticaWaterOptionsScreen}). These control the animated wave field,
 * volumetric absorption, caustic brightness, water color mixing, and underwater shadow.
 *
 * <p>Binding and update semantics are identical to {@link RtDetailOptions}: each slider reads its
 * current {@link CausticaConfig} value for the initial position and writes back through {@code set(...)}
 * on every change, so the effect lands on the next frame.
 *
 * <p>Each option is constructed with the CausticaConfig factory default as its {@code initialValue},
 * then immediately synced to the current runtime value. That lets the per-option reset button restore
 * the factory default ({@link ResetableOption#factoryDefault()}) and re-sync the widget.
 */
public final class RtWaterOptions {
    private RtWaterOptions() {
    }

    /** Water detail options, in display order. */
    public static ResetableOption[] waterOptions() {
        return new ResetableOption[] {
            waveStrength(),
            waveSpeed(),
            causticBrightness(),
            waterDensity(),
            waterShadowTint(),
            waterOpacity(),
            // Advanced RGB color mixing
            waterColorR(),
            waterColorG(),
            waterColorB(),
            waterColorBlend()
        };
    }

    /** Slider with one decimal place, no suffix (e.g. "0.3"). */
    private static ResetableOption tenthsSlider(String captionKey, FloatSetting setting,
                                                int tenthsMin, int tenthsMax, int tenthsDefault) {
        OptionInstance<Integer> option = new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, tenths) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.1f", tenths / 10.0f))),
                new OptionInstance.IntRange(tenthsMin, tenthsMax),
                tenthsDefault,
                tenths -> setting.set(tenths / 10.0f));
        option.set(Math.clamp(Math.round(setting.value() * 10.0f), tenthsMin, tenthsMax));
        return new ResetableOption(option, tenthsDefault);
    }

    /** Slider with two decimal places, no suffix (e.g. "0.25"). */
    private static ResetableOption hundredthsSlider(String captionKey, FloatSetting setting,
                                                    int hundredthsMin, int hundredthsMax, int hundredthsDefault) {
        OptionInstance<Integer> option = new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, hundredths) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.2f", hundredths / 100.0f))),
                new OptionInstance.IntRange(hundredthsMin, hundredthsMax),
                hundredthsDefault,
                hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), hundredthsMin, hundredthsMax));
        return new ResetableOption(option, hundredthsDefault);
    }

    private static ResetableOption waveStrength() {
        return tenthsSlider("caustica.options.rt.waveStrength",
                CausticaConfig.Rt.Water.WAVE_STRENGTH, 0, 10, 3);
    }

    private static ResetableOption waveSpeed() {
        return tenthsSlider("caustica.options.rt.waveSpeed",
                CausticaConfig.Rt.Water.WAVE_SPEED, 0, 10, 8);
    }

    private static ResetableOption causticBrightness() {
        return tenthsSlider("caustica.options.rt.causticMax",
                CausticaConfig.Rt.Water.CAUSTIC_BRIGHTNESS, 0, 50, 50);
    }

    private static ResetableOption waterDensity() {
        return tenthsSlider("caustica.options.rt.waterDensity",
                CausticaConfig.Rt.Water.WATER_DENSITY, 0, 5, 1);
    }

    private static ResetableOption waterShadowTint() {
        return tenthsSlider("caustica.options.rt.waterShadowTint",
                CausticaConfig.Rt.Water.WATER_SHADOW_TINT, 0, 10, 5);
    }

    private static ResetableOption waterOpacity() {
        return tenthsSlider("caustica.options.rt.waterOpacity",
                CausticaConfig.Rt.Water.WATER_OPACITY, 0, 10, 10);
    }

    private static ResetableOption waterColorR() {
        return hundredthsSlider("caustica.options.rt.waterColorR",
                CausticaConfig.Rt.Water.WATER_COLOR_R, 0, 100, 25);
    }

    private static ResetableOption waterColorG() {
        return hundredthsSlider("caustica.options.rt.waterColorG",
                CausticaConfig.Rt.Water.WATER_COLOR_G, 0, 100, 46);
    }

    private static ResetableOption waterColorB() {
        return hundredthsSlider("caustica.options.rt.waterColorB",
                CausticaConfig.Rt.Water.WATER_COLOR_B, 0, 100, 90);
    }

    private static ResetableOption waterColorBlend() {
        return hundredthsSlider("caustica.options.rt.waterColorBlend",
                CausticaConfig.Rt.Water.WATER_COLOR_BLEND, 10, 100, 10);
    }
}