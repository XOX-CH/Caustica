package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the "雨细节" (Rain Detail) submenu of the
 * Caustica-DLSS window ({@link CausticaRainOptionsScreen}). These are the rain particle controls:
 * enable/disable toggle, particle density, alpha, fall speed, wind, and streak geometry.
 *
 * <p>Binding and update semantics are identical to {@link RtVideoOptions}: each slider reads its
 * current {@link CausticaConfig} value for the initial position and writes back through {@code set(...)}
 * on every change, so the effect lands on the next frame.
 */
public final class RtRainOptions {
    private RtRainOptions() {
    }

    /** Rain options, in display order. */
    public static ResetableOption[] rainOptions() {
        return new ResetableOption[] {
            rainEnabled(),
            maxParticles(),
            rainAlpha(),
            rainFallSpeed(),
            rainWindStrength(),
            rainHeightAbove(),
            rainHeightBelow(),
            rainHalfRange(),
            rainStreakWidth(),
            rainStreakHeight(),
            rainMieG(),
        };
    }

    private static ResetableOption rainEnabled() {
        CausticaConfig.BooleanSetting setting = CausticaConfig.Rt.Weather.RAIN_ENABLED;
        OptionInstance<Boolean> option = new OptionInstance<>(
                "caustica.options.weather.rain.enabled",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.weather.rain.enabled.tooltip")),
                OptionInstance.forOptionMonoLang(),
                OptionInstance.UNIT_TRUE,
                setting.defaultValue(),
                setting::set);
        option.set(setting.value());
        return new ResetableOption(option, setting.defaultValue());
    }

    private static ResetableOption maxParticles() {
        IntSetting setting = CausticaConfig.Rt.Weather.RAIN_MAX_PARTICLES;
        int factoryDefault = setting.defaultValue();
        OptionInstance<Integer> option = new OptionInstance<>(
                "caustica.options.weather.rain.maxParticles",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.weather.rain.maxParticles.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, Component.literal(String.valueOf(value))),
                new OptionInstance.IntRange(0, 2000),
                factoryDefault,
                hundredths -> setting.set(hundredths));
        option.set(Math.clamp(setting.value(), 0, 2000));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption rainAlpha() {
        return hundredthsSlider("caustica.options.weather.rain.alpha",
                CausticaConfig.Rt.Weather.RAIN_PARTICLE_ALPHA, 0, 100, 35);
    }

    private static ResetableOption rainFallSpeed() {
        FloatSetting setting = CausticaConfig.Rt.Weather.RAIN_FALL_SPEED;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue()), 0, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
                "caustica.options.weather.rain.fallSpeed",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.weather.rain.fallSpeed.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.1f", value / 1.0f))),
                new OptionInstance.IntRange(0, 100),
                factoryDefault,
                v -> setting.set((float) v));
        option.set(Math.clamp(Math.round(setting.value()), 0, 100));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption rainWindStrength() {
        return hundredthsSlider("caustica.options.weather.rain.windStrength",
                CausticaConfig.Rt.Weather.RAIN_WIND_STRENGTH, 0, 500, 100);
    }

    private static ResetableOption rainHeightAbove() {
        FloatSetting setting = CausticaConfig.Rt.Weather.RAIN_HEIGHT_ABOVE;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue()), 1, 64);
        OptionInstance<Integer> option = new OptionInstance<>(
                "caustica.options.weather.rain.heightAbove",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.weather.rain.heightAbove.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.0f", value / 1.0f))),
                new OptionInstance.IntRange(1, 64),
                factoryDefault,
                v -> setting.set((float) v));
        option.set(Math.clamp(Math.round(setting.value()), 1, 64));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption rainHeightBelow() {
        FloatSetting setting = CausticaConfig.Rt.Weather.RAIN_HEIGHT_BELOW;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue()), 0, 32);
        OptionInstance<Integer> option = new OptionInstance<>(
                "caustica.options.weather.rain.heightBelow",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.weather.rain.heightBelow.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.0f", value / 1.0f))),
                new OptionInstance.IntRange(0, 32),
                factoryDefault,
                v -> setting.set((float) v));
        option.set(Math.clamp(Math.round(setting.value()), 0, 32));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption rainHalfRange() {
        FloatSetting setting = CausticaConfig.Rt.Weather.RAIN_HALF_RANGE;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue()), 1, 128);
        OptionInstance<Integer> option = new OptionInstance<>(
                "caustica.options.weather.rain.halfRange",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.weather.rain.halfRange.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.0f", value / 1.0f))),
                new OptionInstance.IntRange(1, 128),
                factoryDefault,
                v -> setting.set((float) v));
        option.set(Math.clamp(Math.round(setting.value()), 1, 128));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption rainStreakWidth() {
        return hundredthsSlider("caustica.options.weather.rain.streakWidth",
                CausticaConfig.Rt.Weather.RAIN_STREAK_WIDTH, 1, 50, 8);
    }

    private static ResetableOption rainStreakHeight() {
        return hundredthsSlider("caustica.options.weather.rain.streakHeight",
                CausticaConfig.Rt.Weather.RAIN_STREAK_HEIGHT, 50, 2000, 500);
    }

    private static ResetableOption rainMieG() {
        return hundredthsSlider("caustica.options.weather.rain.mieG",
                CausticaConfig.Rt.Weather.RAIN_MIE_G, 0, 99, 90);
    }

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
}