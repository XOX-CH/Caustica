package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the option widgets shown in the "水波细分" submenu
 * ({@link CausticaWaveDetailScreen}). Includes the global wave controls
 * (strength, speed, preset, meander) and the per-band amplitude multipliers.
 */
public final class RtWaveBandOptions {
    private RtWaveBandOptions() {}

    /** Global wave controls (strength, speed, preset, meander, dispersion, count). */
    public static ResetableOption[] waveOptions() {
        return new ResetableOption[] {
            waveStrength(),
            waveHeight(),
            waveSpeed(),
            wavePreset(),
            customMeander(),
            waterDispersion(),
            waveCount(),
        };
    }

    /** Per-band amplitude options, in display order (longest → shortest wave). */
    public static ResetableOption[] bandOptions() {
        return new ResetableOption[] {
            band("caustica.options.rt.waveBand0", "超长涌浪", CausticaConfig.Rt.Water.WAVE_BAND_0),
            band("caustica.options.rt.waveBand1", "长涌浪",    CausticaConfig.Rt.Water.WAVE_BAND_1),
            band("caustica.options.rt.waveBand2", "中涌浪",    CausticaConfig.Rt.Water.WAVE_BAND_2),
            band("caustica.options.rt.waveBand3", "短涌浪",    CausticaConfig.Rt.Water.WAVE_BAND_3),
            band("caustica.options.rt.waveBand4", "大碎波",    CausticaConfig.Rt.Water.WAVE_BAND_4),
            band("caustica.options.rt.waveBand5", "中碎波",    CausticaConfig.Rt.Water.WAVE_BAND_5),
            band("caustica.options.rt.waveBand6", "小碎波",    CausticaConfig.Rt.Water.WAVE_BAND_6),
            band("caustica.options.rt.waveBand7", "细碎波",    CausticaConfig.Rt.Water.WAVE_BAND_7),
            band("caustica.options.rt.waveBand8", "微碎波",    CausticaConfig.Rt.Water.WAVE_BAND_8),
            band("caustica.options.rt.waveBand9", "毛细波",    CausticaConfig.Rt.Water.WAVE_BAND_9),
            band("caustica.options.rt.waveBand10","微毛细波",  CausticaConfig.Rt.Water.WAVE_BAND_10),
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

    /** Slider 0.00–2.00, step 0.01, hundredths-based. */
    private static ResetableOption band(String captionKey, String label, FloatSetting setting) {
        OptionInstance<Integer> option = new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip",
                        Component.literal(label))),
                (caption, hundredths) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.2f×", hundredths / 100.0f))),
                new OptionInstance.IntRange(0, 200),
                100,
                hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 0, 200));
        return new ResetableOption(option, 100);
    }

    private static ResetableOption waveStrength() {
        return tenthsSlider("caustica.options.rt.waveStrength",
                CausticaConfig.Rt.Water.WAVE_STRENGTH, 0, 10, 3);
    }

    private static ResetableOption waveHeight() {
        return hundredthsSlider("caustica.options.rt.waveHeight",
                CausticaConfig.Rt.Water.WAVE_HEIGHT, 100, 200, 100);
    }

    private static ResetableOption waveSpeed() {
        return tenthsSlider("caustica.options.rt.waveSpeed",
                CausticaConfig.Rt.Water.WAVE_SPEED, 0, 20, 8);
    }

    private static ResetableOption wavePreset() {
        IntSetting setting = CausticaConfig.Rt.Water.WAVE_PRESET;
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.wavePreset",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.wavePreset.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.wavePreset." + value)),
            new OptionInstance.IntRange(0, 4),
            setting.defaultValue(),
            setting::set);
        option.set(setting.value());
        return new ResetableOption(option, setting.defaultValue());
    }

    private static ResetableOption customMeander() {
        return tenthsSlider("caustica.options.rt.customMeander",
                CausticaConfig.Rt.Water.CUSTOM_MEANDER, 0, 50, 0);
    }

    private static ResetableOption waterDispersion() {
        return hundredthsSlider("caustica.options.rt.waterDispersion",
                CausticaConfig.Rt.Water.WATER_DISPERSION, 0, 100, 0);
    }

    private static ResetableOption waveCount() {
        IntSetting setting = CausticaConfig.Rt.Water.WAVE_COUNT;
        OptionInstance<Integer> option = new OptionInstance<>(
                "caustica.options.rt.waveCount",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.waveCount.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption,
                        Component.literal(String.valueOf(value))),
                new OptionInstance.IntRange(4, 124),
                10,
                value -> CausticaConfig.Rt.Water.WAVE_COUNT.set(value));
        option.set(Math.clamp(CausticaConfig.Rt.Water.WAVE_COUNT.value(), 4, 124));
        return new ResetableOption(option, 10);
    }
}