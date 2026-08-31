package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the Caustica-DLSS options window
 * ({@link CausticaOptionsScreen}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the value-update listener writes back through {@code set(...)} so changes take
 * effect on the next frame.
 *
 * <p>Each option is constructed with the CausticaConfig factory default as its {@code initialValue},
 * then immediately synced to the current runtime value. That lets the per-option reset button restore
 * the factory default ({@link ResetableOption#factoryDefault()}) and re-sync the widget.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /**
     * Runtime-tunable RT options shown in the main Caustica-DLSS window (the ones that are NOT part of
     * the "细节调整" submenu). These are the core RT controls: exposure, sampling, toggles, and DLSS.
     */
    public static ResetableOption[] mainOptions() {
        List<ResetableOption> options = new ArrayList<>(List.of(
            exposureMode(),
            manualEv(),
            gamma(),
            spp(),
            maxBounces(),
            entities(),
            particles(),
            waterWaves(),
            dlssQuality(),
            dlssRrEnabled(),
            dlssRrPreset(),
            dlssFgEnabled(),
            dlssFgMultiFrame(),
            reflexEnabled(),
            reflexBoost()
        ));
        if (CausticaConfig.Rt.Hdr.swapchainPqAvailable()) {
            options.add(hdrEnabled());
            options.add(hdrUiBrightness());
            options.add(hdrPeak());
        }
        options.add(debugView());
        return options.toArray(ResetableOption[]::new);
    }

    /**
     * All runtime-tunable RT options, in display order. Includes both the main options and the
     * "细节调整" submenu options.
     */
    public static ResetableOption[] runtimeOptions() {
        List<ResetableOption> options = new ArrayList<>(List.of(
            exposureMode(),
            manualEv(),
            gamma(),
            spp(),
            maxBounces(),
            entities(),
            particles(),
            waterWaves(),
            dlssQuality()
        ));
        if (CausticaConfig.Rt.Hdr.swapchainPqAvailable()) {
            options.add(hdrEnabled());
            options.add(hdrUiBrightness());
            options.add(hdrPeak());
        }
        options.add(roughnessScale());
        options.add(reflectionScale());
        options.add(sunColorTemp());
        options.add(giStrength());
        options.add(bloomStrength());
        options.add(hueShift());
        options.add(saturation());
        options.add(debugView());
        return options.toArray(ResetableOption[]::new);
    }

    private static ResetableOption exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        String factoryDefault = setting.defaultValue();
        OptionInstance<String> option = new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
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

    private static ResetableOption gamma() {
        FloatSetting setting = CausticaConfig.Rt.Tonemap.GAMMA;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 100.0f), 50, 150);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.gamma",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.gamma.tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(50, 150),
            factoryDefault,
            hundredths -> setting.set(hundredths / 100.0f));
        option.set(Math.clamp(Math.round(setting.value() * 100.0f), 50, 150));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        int factoryDefault = Math.clamp(setting.defaultValue(), 1, 8);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            factoryDefault,
            setting::set);
        option.set(Math.clamp(setting.value(), 1, 8));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        int factoryDefault = Math.clamp(setting.defaultValue(), 2, 8);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            factoryDefault,
            setting::set);
        option.set(Math.clamp(setting.value(), 2, 8));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption entities() {
        return boolResetable("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static ResetableOption particles() {
        return boolResetable("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static ResetableOption waterWaves() {
        return boolResetable("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    private static ResetableOption dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int factoryDefault = Math.clamp(setting.defaultValue(), 1, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption,
                    Component.literal(percent + "%")),
            new OptionInstance.IntRange(1, 100),
            factoryDefault,
            setting::set);
        option.set(Math.clamp(setting.value(), 1, 100));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption dlssRrEnabled() {
        return boolResetable("caustica.options.rt.dlssRr", CausticaConfig.Rt.DlssRr.ENABLED);
    }

    private static ResetableOption dlssRrPreset() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.PRESET;
        // NVSDK_NGX_RayReconstruction_Hint_Render_Preset values:
        //   4   = Preset D (transformer, current default)
        //   5   = Preset E (latest transformer, required for DoF guide)
        //   6   = Preset F
        List<Integer> presets = List.of(4, 5, 6);
        int factoryPosition = positionOf(presets, setting.defaultValue());
        int initialPosition = presets.indexOf(presets.contains(setting.value()) ? setting.value() : 0);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.dlssRrPreset",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssRrPreset.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssRrPreset." + presets.get(position))),
            new OptionInstance.IntRange(0, presets.size() - 1),
            factoryPosition,
            position -> setting.set(presets.get(position)));
        option.set(Math.max(initialPosition, 0));
        return new ResetableOption(option, Math.max(factoryPosition, 0));
    }

    private static ResetableOption dlssFgEnabled() {
        return boolResetable("caustica.options.rt.dlssFg", CausticaConfig.Rt.Fg.ENABLED);
    }

    private static ResetableOption dlssFgMultiFrame() {
        IntSetting setting = CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT;
        int factoryDefault = Math.clamp(setting.defaultValue(), 1, 5);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.dlssFgMultiFrame",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssFgMultiFrame.tooltip")),
            (caption, count) -> Options.genericValueLabel(caption,
                    Component.literal((count + 1) + "x")),
            new OptionInstance.IntRange(1, 5),
            factoryDefault,
            setting::set);
        option.set(Math.clamp(setting.value(), 1, 5));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption reflexEnabled() {
        return boolResetable("caustica.options.rt.reflex", CausticaConfig.Rt.Reflex.ENABLED);
    }

    private static ResetableOption reflexBoost() {
        return boolResetable("caustica.options.rt.reflexBoost", CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST);
    }

    private static ResetableOption hdrEnabled() {
        BooleanSetting setting = CausticaConfig.Rt.Hdr.ENABLED;
        boolean factoryDefault = setting.defaultValue();
        OptionInstance<Boolean> option = OptionInstance.createBoolean(
            "caustica.options.rt.hdr",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdr.tooltip")),
            factoryDefault,
            enabled -> {
                if (setting.value() != enabled) {
                    setting.set(enabled);
                    // Reuse the framebuffer-resize path at the next safe frame boundary. GpuSurface
                    // refuses configure() while an image is acquired, so doing it directly here is unsafe.
                    Minecraft.getInstance().invalidateSurfaceConfiguration();
                }
            });
        option.set(setting.value());
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption hdrUiBrightness() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.UI_NITS;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue()), 80, 500);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.hdrUiBrightness",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrUiBrightness.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 500),
            factoryDefault,
            nits -> setting.set(nits.floatValue()));
        option.set(Math.clamp(Math.round(setting.value()), 80, 500));
        return new ResetableOption(option, factoryDefault);
    }

    // Each step selects a baked ACES HDR mastering target. Changes take effect on the next frame.
    private static ResetableOption hdrPeak() {
        IntSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        List<Integer> steps = CausticaConfig.Rt.Hdr.PEAK_NITS_STEPS;
        int factoryPosition = positionOf(steps, setting.defaultValue());
        int initialPeak = steps.contains(setting.value()) ? setting.value() : 1000;
        int initialPosition = steps.indexOf(initialPeak);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption, Component.literal(steps.get(position) + " nits")),
            new OptionInstance.IntRange(0, steps.size() - 1),
            Math.max(factoryPosition, 0),
            position -> setting.set(steps.get(position)));
        option.set(Math.max(initialPosition, 0));
        return new ResetableOption(option, Math.max(factoryPosition, 0));
    }

    private static ResetableOption debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        int factoryDefault = Math.clamp(setting.defaultValue(), 0, 9);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Codec.INT),
            factoryDefault,
            setting::set);
        option.set(Math.clamp(setting.value(), 0, 9));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption roughnessScale() {
        FloatSetting setting = CausticaConfig.Rt.Composite.ROUGHNESS_SCALE;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 10.0f), 1, 30);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.roughnessScale",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.roughnessScale.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1fx", tenths / 10.0f))),
            new OptionInstance.IntRange(1, 30),
            factoryDefault,
            tenths -> setting.set(tenths / 10.0f));
        option.set(Math.clamp(Math.round(setting.value() * 10.0f), 1, 30));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption reflectionScale() {
        FloatSetting setting = CausticaConfig.Rt.Composite.REFLECTION_SCALE;
        int factoryDefault = Math.clamp(Math.round(setting.defaultValue() * 10.0f), 0, 30);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.reflectionScale",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.reflectionScale.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1fx", tenths / 10.0f))),
            new OptionInstance.IntRange(0, 30),
            factoryDefault,
            tenths -> setting.set(tenths / 10.0f));
        option.set(Math.clamp(Math.round(setting.value() * 10.0f), 0, 30));
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

    private static ResetableOption boolResetable(String captionKey, BooleanSetting setting) {
        boolean factoryDefault = setting.defaultValue();
        OptionInstance<Boolean> option = OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            factoryDefault,
            setting::set);
        option.set(setting.value());
        return new ResetableOption(option, factoryDefault);
    }

    private static int positionOf(List<Integer> steps, int value) {
        int idx = steps.indexOf(value);
        return idx >= 0 ? idx : 0;
    }
}