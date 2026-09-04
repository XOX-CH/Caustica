package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the "太阳位置" (Sun Position) submenu of the
 * Caustica-DLSS window ({@link CausticaSunPositionOptionsScreen}). The toggle enables overriding
 * Minecraft's day/night cycle with a fixed sun angle, and the slider sets the angle in degrees.
 *
 * <p>Binding and update semantics are identical to {@link RtDetailOptions}: each slider reads its
 * current {@link CausticaConfig} value for the initial position and writes back through {@code set(...)}
 * on every change, so the effect lands on the next frame.
 */
public final class RtSunPositionOptions {
    private RtSunPositionOptions() {
    }

    /** Sun position options, in display order. */
    public static ResetableOption[] sunPositionOptions() {
        return new ResetableOption[] {
            sunAngleOverride(),
            sunAngleSlider()
        };
    }

    /**
     * Maps a sun angle (0-360 degrees) to a time-of-day label.
     * 0 = sunrise, 90 = noon, 180 = sunset, 270 = midnight.
     */
    private static Component timeOfDayLabel(int degrees) {
        if (degrees < 23 || degrees >= 338) {
            return Component.translatable("caustica.options.sunPosition.time.sunrise");
        } else if (degrees < 68) {
            return Component.translatable("caustica.options.sunPosition.time.morning");
        } else if (degrees < 113) {
            return Component.translatable("caustica.options.sunPosition.time.noon");
        } else if (degrees < 158) {
            return Component.translatable("caustica.options.sunPosition.time.afternoon");
        } else if (degrees < 203) {
            return Component.translatable("caustica.options.sunPosition.time.sunset");
        } else if (degrees < 248) {
            return Component.translatable("caustica.options.sunPosition.time.dusk");
        } else if (degrees < 293) {
            return Component.translatable("caustica.options.sunPosition.time.midnight");
        } else {
            return Component.translatable("caustica.options.sunPosition.time.dawn");
        }
    }

    private static ResetableOption sunAngleOverride() {
        var setting = CausticaConfig.Rt.Lighting.SUN_ANGLE_OVERRIDE;
        boolean factoryDefault = setting.defaultValue();
        OptionInstance<Boolean> option = OptionInstance.createBoolean(
            "caustica.options.sunPosition.override",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.sunPosition.override.tooltip")),
            factoryDefault,
            enabled -> setting.set(enabled));
        option.set(setting.value());
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption sunAngleSlider() {
        var setting = CausticaConfig.Rt.Lighting.SUN_ANGLE_DEGREES;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue()), 0, 360);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.sunPosition.angle",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.sunPosition.angle.tooltip")),
            (caption, degrees) -> Options.genericValueLabel(caption,
                    Component.literal(degrees + "\u00B0").append(" ").append(timeOfDayLabel(degrees))),
            new OptionInstance.IntRange(0, 360),
            factoryDefault,
            degrees -> setting.set(degrees.floatValue()));
        option.set(Math.clamp(Math.round(setting.value()), 0, 360));
        return new ResetableOption(option, factoryDefault);
    }
}