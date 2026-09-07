package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the "曝光" (Exposure) submenu of the
 * Caustica-DLSS window ({@link CausticaExposureOptionsScreen}). Controls auto/manual exposure
 * mode, manual EV, and the min/max EV bounds.
 */
public final class RtExposureOptions {
    private RtExposureOptions() {
    }

    /** Exposure options, in display order. */
    public static ResetableOption[] exposureOptions() {
        return new ResetableOption[] {
            exposureMode(),
            manualEv(),
            minEvSlider(),
            maxEvSlider()
        };
    }

    private static ResetableOption exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        String factoryDefault = setting.defaultValue();
        OptionInstance<String> option = new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            factoryDefault,
            setting::set);
        option.set(setting.get());
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 10.0f), -150, 150);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-150, 150),
            factoryDefault,
            tenths -> setting.set(tenths / 10.0f));
        option.set(Math.clamp(Math.round(setting.value() * 10.0f), -150, 150));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption minEvSlider() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MIN_EV;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 10.0f), -200, 50);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.minEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.minEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                return Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-200, 50),
            factoryDefault,
            tenths -> setting.set(tenths / 10.0f));
        option.set(Math.clamp(Math.round(setting.value() * 10.0f), -200, 50));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption maxEvSlider() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MAX_EV;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 10.0f), -150, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.maxEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                return Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-150, 100),
            factoryDefault,
            tenths -> setting.set(tenths / 10.0f));
        option.set(Math.clamp(Math.round(setting.value() * 10.0f), -150, 100));
        return new ResetableOption(option, factoryDefault);
    }
}