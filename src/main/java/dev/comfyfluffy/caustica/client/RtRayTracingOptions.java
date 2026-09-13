package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the "RTX 光追" (Ray Tracing) submenu of the
 * Caustica-DLSS window ({@link CausticaRayTracingOptionsScreen}). Controls path tracing, sampling,
 * bounces, entities, particles, render scale, and DLSS models.
 */
public final class RtRayTracingOptions {
    private RtRayTracingOptions() {
    }

    /** Caption key for the DLSS Ray Reconstruction toggle, used to identify it for disabled-state logic. */
    public static final String DLSS_RR_CAPTION = "caustica.options.rt.dlssRr";

    /** Saved RR state restored when path tracing is turned back on. */
    private static boolean savedRrState = true;
    /** The most recently created RR toggle OptionInstance, used for disabled-state identification. */
    private static OptionInstance<?> lastRrToggleInstance;

    /** Returns true when the given option is the DLSS Ray Reconstruction toggle. */
    public static boolean isRrToggle(OptionInstance<?> option) {
        return option == lastRrToggleInstance;
    }

    /** Ray Tracing options, in display order. */
    public static ResetableOption[] rayTracingOptions() {
        return new ResetableOption[] {
            pathTracingEnabled(),
            spp(),
            maxBounces(),
            entities(),
            particles(),
            cloudEnabled(),
            cloudShapeOctaves(),
            cloudErosion(),
            cloudPathSteps(),
            cloudShadowSteps(),
            cloudStrideScale(),
            cloudExitFloor(),
            cloudEventShadow(),
            cloudCoverage(),
            cloudDensity(),
            cloudWindSpeed(),
            dlssQuality(),
            dlssRrEnabled(),
            dlssRrPreset(),
            dlssUpscalePreset()
        };
    }

    private static ResetableOption cloudEnabled() {
        return boolResetable("caustica.options.rt.cloud", CausticaConfig.Rt.Cloud.ENABLED);
    }

    /** Three-level option bound to an int setting; label keys are {@code captionKey.0} .. {@code .2}. */
    private static ResetableOption threeLevelOption(String captionKey, IntSetting setting) {
        int factoryDefault = Math.clamp(setting.defaultValue(), 0, 2);
        OptionInstance<Integer> option = new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, level) -> Options.genericValueLabel(caption,
                    Component.translatable(captionKey + "." + level)),
            new OptionInstance.IntRange(0, 2),
            factoryDefault,
            setting::set);
        option.set(Math.clamp(setting.value(), 0, 2));
        return new ResetableOption(option, factoryDefault);
    }

    /** Integer slider bound directly to an int setting, labelled with the raw count. */
    private static ResetableOption intSlider(String captionKey, IntSetting setting, int min, int max, int fallback) {
        int factoryDefault = Math.clamp(setting.defaultValue(), min, max);
        OptionInstance<Integer> option = new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.literal(Integer.toString(value))),
            new OptionInstance.IntRange(min, max),
            fallback,
            setting::set);
        option.set(Math.clamp(setting.value(), min, max));
        return new ResetableOption(option, factoryDefault);
    }

    /** Slider with three decimal places (value × 1000). */
    private static ResetableOption thousandthsSlider(String captionKey, dev.comfyfluffy.caustica.CausticaConfig.FloatSetting setting,
                                                     int thousandthsMin, int thousandthsMax, int thousandthsDefault) {
        OptionInstance<Integer> option = new OptionInstance<>(
                captionKey,
                OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
                (caption, thousandths) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.3f", thousandths / 1000.0f))),
                new OptionInstance.IntRange(thousandthsMin, thousandthsMax),
                thousandthsDefault,
                thousandths -> setting.set(thousandths / 1000.0f));
        option.set(Math.clamp(Math.round(setting.value() * 1000.0f), thousandthsMin, thousandthsMax));
        return new ResetableOption(option, thousandthsDefault);
    }

    // One control per pushed cloud parameter. The shape knobs change the converged image and the
    // estimator knobs only its noise, so they are deliberately kept separate rather than bundled
    // into presets.

    private static ResetableOption cloudShapeOctaves() {
        return intSlider("caustica.options.rt.cloudShapeOctaves", CausticaConfig.Rt.Cloud.SHAPE_OCTAVES, 1, 6, 3);
    }

    private static ResetableOption cloudErosion() {
        return threeLevelOption("caustica.options.rt.cloudErosion", CausticaConfig.Rt.Cloud.EROSION);
    }

    private static ResetableOption cloudPathSteps() {
        return intSlider("caustica.options.rt.cloudPathSteps", CausticaConfig.Rt.Cloud.PATH_STEPS, 4, 64, 24);
    }

    private static ResetableOption cloudShadowSteps() {
        return intSlider("caustica.options.rt.cloudShadowSteps", CausticaConfig.Rt.Cloud.SHADOW_STEPS, 1, 48, 12);
    }

    private static ResetableOption cloudStrideScale() {
        return hundredthsSlider("caustica.options.rt.cloudStrideScale", CausticaConfig.Rt.Cloud.STRIDE_SCALE, 10, 100, 40);
    }

    private static ResetableOption cloudExitFloor() {
        return thousandthsSlider("caustica.options.rt.cloudExitFloor", CausticaConfig.Rt.Cloud.EXIT_FLOOR, 1, 200, 20);
    }

    private static ResetableOption cloudEventShadow() {
        return threeLevelOption("caustica.options.rt.cloudEventShadow", CausticaConfig.Rt.Cloud.EVENT_SHADOW);
    }

    private static ResetableOption cloudCoverage() {
        return hundredthsSlider("caustica.options.rt.cloudCoverage",
                CausticaConfig.Rt.Cloud.COVERAGE, 0, 100, 42);
    }

    private static ResetableOption cloudDensity() {
        return hundredthsSlider("caustica.options.rt.cloudDensity",
                CausticaConfig.Rt.Cloud.DENSITY, 0, 100, 50);
    }

    private static ResetableOption cloudWindSpeed() {
        return tenthsSlider("caustica.options.rt.cloudWindSpeed",
                CausticaConfig.Rt.Cloud.WIND_SPEED, 0, 300, 60);
    }

    /** Slider with one decimal place (value × 10). */
    private static ResetableOption tenthsSlider(String captionKey, dev.comfyfluffy.caustica.CausticaConfig.FloatSetting setting,
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

    /** Slider with two decimal places (value × 100). */
    private static ResetableOption hundredthsSlider(String captionKey, dev.comfyfluffy.caustica.CausticaConfig.FloatSetting setting,
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

    private static ResetableOption pathTracingEnabled() {
        BooleanSetting setting = CausticaConfig.Rt.ENABLED;
        boolean factoryDefault = setting.defaultValue();
        OptionInstance<Boolean> option = OptionInstance.createBoolean(
            "caustica.options.rt.pathTracing",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.pathTracing.tooltip")),
            factoryDefault,
            enabled -> {
                if (setting.value() != enabled) {
                    if (enabled) {
                        // Directly sync FG, FG Sync, and Reflex when PT turns on
                        CausticaConfig.Rt.SYNC_FRAME_CAP.set(true);
                        CausticaConfig.Rt.Reflex.ENABLED.set(true);
                        CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.set(true);
                        CausticaConfig.Rt.Fg.ENABLED.set(true);
                        CausticaConfig.Rt.DlssRr.ENABLED.set(savedRrState);
                        setting.set(true);
                    } else {
                        // Save RR state for restore when PT turns back on
                        savedRrState = CausticaConfig.Rt.DlssRr.ENABLED.value();
                        CausticaConfig.Rt.DlssRr.ENABLED.set(false);
                        // Set render cap to unlimited since FG is about to turn off
                        CausticaConfig.Rt.FPS_CAP.set(260.0F);
                        // Directly sync FG, FG Sync, and Reflex when PT turns off
                        CausticaConfig.Rt.SYNC_FRAME_CAP.set(false);
                        CausticaConfig.Rt.Reflex.ENABLED.set(false);
                        CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.set(false);
                        CausticaConfig.Rt.Fg.ENABLED.set(false);
                        setting.set(false);
                    }
                    Minecraft.getInstance().invalidateSurfaceConfiguration();
                    // Recreate the options screen to refresh the RR toggle's disabled state
                    Minecraft mc = Minecraft.getInstance();
                    Screen current = mc.gui.screen();
                    if (current instanceof CausticaRayTracingOptionsScreen rtScreen) {
                        mc.gui.setScreen(new CausticaRayTracingOptionsScreen(rtScreen.getParentScreen(), mc.options));
                    }
                }
            });
        option.set(setting.value());
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

    private static ResetableOption dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int factoryDefault = Math.clamp(setting.defaultValue(), 1, 100);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, percent) -> {
                Component label = switch (percent) {
                    case 33 -> Component.translatable("caustica.options.rt.dlssQuality.33");
                    case 50 -> Component.translatable("caustica.options.rt.dlssQuality.50");
                    case 58 -> Component.translatable("caustica.options.rt.dlssQuality.58");
                    case 67 -> Component.translatable("caustica.options.rt.dlssQuality.67");
                    case 100 -> Component.translatable("caustica.options.rt.dlssQuality.100");
                    default -> Component.literal(percent + "%");
                };
                return Options.genericValueLabel(caption, label);
            },
            new OptionInstance.IntRange(1, 100),
            factoryDefault,
            setting::set);
        option.set(Math.clamp(setting.value(), 1, 100));
        return new ResetableOption(option, factoryDefault);
    }

    private static ResetableOption dlssRrEnabled() {
        BooleanSetting setting = CausticaConfig.Rt.DlssRr.ENABLED;
        boolean factoryDefault = setting.defaultValue();
        OptionInstance<Boolean> option = OptionInstance.createBoolean(
            DLSS_RR_CAPTION,
            OptionInstance.cachedConstantTooltip(Component.translatable(DLSS_RR_CAPTION + ".tooltip")),
            factoryDefault,
            enabled -> {
                if (CausticaConfig.Rt.ENABLED.value()) {
                    setting.set(enabled);
                }
            });
        option.set(setting.value());
        lastRrToggleInstance = option;
        return new ResetableOption(option, factoryDefault);
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

    private static ResetableOption dlssUpscalePreset() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.UPSCALE_PRESET;
        // NVSDK_NGX_DLSS_Hint_Render_Preset values:
        //   11  = Preset K (1st gen Transformer, DLAA default)
        //   12  = Preset L (2nd gen Transformer, Ultra Perf default)
        //   13  = Preset M (2nd gen Transformer, Perf default)
        List<Integer> presets = List.of(11, 12, 13);
        int factoryPosition = positionOf(presets, setting.defaultValue());
        int initialPosition = presets.indexOf(presets.contains(setting.value()) ? setting.value() : 0);
        OptionInstance<Integer> option = new OptionInstance<>(
            "caustica.options.rt.dlssUpscalePreset",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssUpscalePreset.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssUpscalePreset." + presets.get(position))),
            new OptionInstance.IntRange(0, presets.size() - 1),
            factoryPosition,
            position -> setting.set(presets.get(position)));
        option.set(Math.max(initialPosition, 0));
        return new ResetableOption(option, Math.max(factoryPosition, 0));
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