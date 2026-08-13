package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the "细节调整" (Detail Tuning) submenu of the
 * Caustica-DLSS window ({@link CausticaDetailOptionsScreen}). These are the artistic numeric knobs:
 * roughness/reflection scales, sun colour temperature, indirect-lighting strength, bloom strength,
 * hue shift, and saturation.
 *
 * <p>Binding and update semantics are identical to {@link RtVideoOptions}: each slider reads its
 * current {@link CausticaConfig} value for the initial position and writes back through {@code set(...)}
 * on every change, so the effect lands on the next frame.
 *
 * <p>Each option is constructed with the CausticaConfig factory default as its {@code initialValue},
 * then immediately synced to the current runtime value. That lets the per-option reset button restore
 * the factory default ({@link ResetableOption#factoryDefault()}) and re-sync the widget.
 */
public final class RtDetailOptions {
    private RtDetailOptions() {
    }

    /** Detail options, in display order. */
    public static ResetableOption[] detailOptions() {
        return new ResetableOption[] {
            roughnessScale(),
            reflectionScale(),
            sunColorTemp(),
            giStrength(),
            bloomStrength(),
            hueShift(),
            saturation()
        };
    }

    // All of these multiply an artistic quantity where 1.0 is neutral. They share one slider shape:
    // an integer hundredths domain mapped onto the config's float range. Bounds match the config clamps.
    // The factory default (hundredthsDefault) is the initialValue so resetOption can restore it.
    private static ResetableOption hundredthsSlider(String captionKey, FloatSetting setting,
                                                    int hundredthsMin, int hundredthsMax, int hundredthsDefault) {
        OptionInstance<Integer> option = new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, hundredths) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "×%.2f", hundredths / 100.0f))),
                new OptionInstance.IntRange(hundredthsMin, hundredthsMax),
                hundredthsDefault,
                hundredths -> setting.set(hundredths / 100.0f));
        // Sync to actual runtime value so the slider shows the user's current setting, not the default.
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), hundredthsMin, hundredthsMax));
        return new ResetableOption(option, hundredthsDefault);
    }

    private static ResetableOption roughnessScale() {
        FloatSetting setting = CausticaConfig.Rt.Composite.ROUGHNESS_SCALE;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 100.0f), 10, 300);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.roughnessScale",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.roughnessScale.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "×%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(10, 300),
            factoryDefault,
            hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 10, 300));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption reflectionScale() {
        FloatSetting setting = CausticaConfig.Rt.Composite.REFLECTION_SCALE;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 100.0f), 0, 300);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.reflectionScale",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.reflectionScale.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "×%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(0, 300),
            factoryDefault,
            hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 0, 300));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption sunColorTemp() {
        FloatSetting setting = CausticaConfig.Rt.Lighting.SUN_COLOR_TEMP;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 100.0f), 10, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.sunColorTemp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunColorTemp.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "×%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(10, 100),
            factoryDefault,
            hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 10, 100));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption giStrength() {
        FloatSetting setting = CausticaConfig.Rt.Composite.GI_STRENGTH;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 100.0f), 10, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.giStrength",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.giStrength.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "×%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(10, 100),
            factoryDefault,
            hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 10, 100));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption bloomStrength() {
        FloatSetting setting = CausticaConfig.Rt.Bloom.STRENGTH;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 100.0f), 10, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.bloomStrength",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.bloomStrength.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "×%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(10, 100),
            factoryDefault,
            hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 10, 100));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption hueShift() {
        FloatSetting setting = CausticaConfig.Rt.Tonemap.HUE_SHIFT;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 100.0f), 10, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.hueShift",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hueShift.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "×%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(10, 100),
            factoryDefault,
            hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 10, 100));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption saturation() {
        FloatSetting setting = CausticaConfig.Rt.Tonemap.SATURATION;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 100.0f), 10, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.saturation",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.saturation.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "×%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(10, 100),
            factoryDefault,
            hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 10, 100));
        return new ResetableOption(option, factoryDefault);
    }
}