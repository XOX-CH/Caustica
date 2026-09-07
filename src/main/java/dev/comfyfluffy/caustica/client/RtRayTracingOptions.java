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
            dlssQuality(),
            dlssRrEnabled(),
            dlssRrPreset(),
            dlssUpscalePreset()
        };
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