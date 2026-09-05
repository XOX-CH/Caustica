package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
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

    /** Caption key for the DLSS Ray Reconstruction toggle, used to identify it for disabled-state logic. */
    public static final String DLSS_RR_CAPTION = "caustica.options.rt.dlssRr";

    /**
     * Runtime-tunable RT options shown in the main Caustica-DLSS window (the ones that are NOT part of
     * the "细节调整" submenu). These are the core RT controls: exposure, sampling, toggles, and DLSS.
     * Frame Generation options live in the FG submenu ({@link #fgOptions()}).
     */
    public static ResetableOption[] mainOptions() {
        // Construction syncs every widget to its current runtime value; suppress the change listeners'
        // side effects (surface invalidation, UI refresh) while the widgets are being wired up.
        buildingOptions = true;
        List<ResetableOption> options;
        try {
            options = new ArrayList<>(List.of(
                pathTracingEnabled(),
                exposureMode(),
                manualEv(),
                gamma(),
                spp(),
                maxBounces(),
                entities(),
                particles(),
                waterWaves(),
                blockOutlineNeon(),
                dlssQuality(),
                dlssRrEnabled(),
                dlssRrPreset(),
                dlssUpscalePreset()
            ));
            if (CausticaConfig.Rt.Hdr.swapchainPqAvailable()) {
                options.add(hdrEnabled());
                options.add(hdrUiBrightness());
                options.add(hdrPeak());
            }
            options.add(debugView());
        } finally {
            buildingOptions = false;
        }
        return options.toArray(ResetableOption[]::new);
    }

    /**
     * Frame Generation options shown in the FG submenu ({@link CausticaFgOptionsScreen}). Includes
     * the FG toggle, multiplier, V-Sync, Reflex linkage, and the FG Sync / cap slider pair.
     */
    public static ResetableOption[] fgOptions() {
        buildingOptions = true;
        try {
            return new ResetableOption[] {
                dlssFgEnabled(),
                dlssFgMultiFrame(),
                reflexMerged(),
                fgSync(),
                fpsCap()
            };
        } finally {
            buildingOptions = false;
        }
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

    private static ResetableOption blockOutlineNeon() {
        return boolResetable("caustica.options.rt.blockOutlineNeon", CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_NEON);
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

    /** Saved RR state restored when path tracing is turned back on. */
    private static boolean savedRrState = true;
    /** The most recently created RR toggle OptionInstance, used for disabled-state identification. */
    private static OptionInstance<?> lastRrToggleInstance;

    /** Returns true when the given option is the DLSS Ray Reconstruction toggle. */
    public static boolean isRrToggle(OptionInstance<?> option) {
        return option == lastRrToggleInstance;
    }

    /** FG Sync toggle from the most recent mainOptions() build; lets the screen re-sync its widget. */
    private static OptionInstance<Boolean> lastFgSyncInstance;

    /** Cap slider from the most recent mainOptions() build; lets the screen re-sync its widget. */
    private static OptionInstance<Double> lastFpsCapInstance;

    /** Set by {@link CausticaOptionsScreen} while it is open so FG-related listeners can refresh the cap row live. */
    private static Runnable uiRefresh;

    /** True while the widget models are being built or programmatically synced; suppresses listener side effects. */
    private static boolean buildingOptions;

    /** True while the cap slider's model is being written programmatically (not by the user). */
    private static boolean syncingCapDisplay;

    public static OptionInstance<Boolean> fgSyncOption() {
        return lastFgSyncInstance;
    }

    public static OptionInstance<Double> fpsCapOption() {
        return lastFpsCapInstance;
    }

    static void setUiRefresh(Runnable refresh) {
        uiRefresh = refresh;
    }

    private static void requestUiRefresh() {
        Runnable refresh = uiRefresh;
        if (!buildingOptions && refresh != null) {
            refresh.run();
        }
    }

    /**
     * The cap value the slider should display right now: the exact synced rate while FG Sync is on and
     * Frame Generation enabled (the slider is locked then), the unlimited sentinel while FG Sync is on
     * but FG is off (matching the uncapped fallback in {@code RtFpsCap}), and otherwise the manual
     * setting. Display only — pacing reads the live value in {@code RtFpsCap}.
     */
    private static double currentDisplayCap() {
        if (CausticaConfig.Rt.SYNC_FRAME_CAP.value()) {
            if (CausticaConfig.Rt.Fg.ENABLED.value()) {
                int refreshRate = Minecraft.getInstance().getWindow().getRefreshRate();
                if (refreshRate > 0) {
                    return refreshRate / (double) (CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value() + 1);
                }
            }
            return 260.0;
        }
        return CausticaConfig.Rt.FPS_CAP.value();
    }

    /** Writes the current display cap into the slider model without triggering the manual-override path. */
    static void syncCapDisplayValue() {
        OptionInstance<Double> option = lastFpsCapInstance;
        if (option == null) {
            return;
        }
        syncingCapDisplay = true;
        try {
            option.set(currentDisplayCap());
        } finally {
            syncingCapDisplay = false;
        }
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
                        setting.set(true);
                        CausticaConfig.Rt.DlssRr.ENABLED.set(savedRrState);
                    } else {
                        savedRrState = CausticaConfig.Rt.DlssRr.ENABLED.value();
                        CausticaConfig.Rt.DlssRr.ENABLED.set(false);
                        setting.set(false);
                    }
                    // Recreate the options screen to refresh the RR toggle's disabled state
                    Minecraft mc = Minecraft.getInstance();
                    Screen current = mc.gui.screen();
                    if (current instanceof CausticaOptionsScreen cos) {
                        mc.gui.setScreen(new CausticaOptionsScreen(cos.getParentScreen(), mc.options));
                    }
                }
            });
        option.set(setting.value());
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

    private static ResetableOption dlssFgEnabled() {
        BooleanSetting setting = CausticaConfig.Rt.Fg.ENABLED;
        boolean factoryDefault = setting.defaultValue();
        OptionInstance<Boolean> option = OptionInstance.createBoolean(
            "caustica.options.rt.dlssFg",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssFg.tooltip")),
            factoryDefault,
            enabled -> {
                if (setting.value() != enabled) {
                    setting.set(enabled);
                    // Frame Generation needs the swapchain to hold generatedCount+1 images; toggling it
                    // changes that requirement, so rebuild the swapchain at the next safe boundary (same
                    // path HDR uses).
                    Minecraft.getInstance().invalidateSurfaceConfiguration();
                    warnFgAutoReflexUnavailable();
                    // The synced cap shown below derives from this toggle.
                    requestUiRefresh();
                }
            });
        option.set(setting.value());
        return new ResetableOption(option, factoryDefault);
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
            count -> {
                if (setting.value() != count) {
                    setting.set(count);
                    // The swapchain image count is sized from this multiplier at creation time; a live
                    // multiplier change must recreate the swapchain or higher multipliers silently cap back
                    // to 2x (see VulkanGpuSurfaceMixin#caustica$raiseSwapchainImageCount).
                    Minecraft.getInstance().invalidateSurfaceConfiguration();
                    // The synced cap shown below derives from this multiplier.
                    requestUiRefresh();
                }
            });
        option.set(Math.clamp(setting.value(), 1, 5));
        return new ResetableOption(option, factoryDefault);
    }

    /**
     * FG's auto-Reflex linkage can only take effect when {@code VK_NV_low_latency2} was enabled at device
     * creation — a mid-session enable with the extension absent silently does nothing until restart, so
     * surface that instead of letting the user believe Reflex is pacing the FG queue.
     */
    private static void warnFgAutoReflexUnavailable() {
        if (CausticaConfig.Rt.Fg.ENABLED.value()
                && CausticaConfig.Rt.Fg.AUTO_REFLEX.value()
                && !CausticaConfig.Rt.Reflex.ENABLED.value()
                && !RtDeviceBringup.reflexEnabled()) {
            CausticaMod.LOGGER.warn("DLSS-FG: auto-Reflex cannot engage — VK_NV_low_latency2 was not enabled at "
                    + "device creation; restart the game with Frame Generation on to let it pace the FG queue");
        }
    }

    /**
     * Merged Reflex toggle: enables both NVIDIA Reflex and Reflex Boost together.
     * When ON, both {@link CausticaConfig.Rt.Reflex#ENABLED} and
     * {@link CausticaConfig.Rt.Reflex#LOW_LATENCY_BOOST} are set to true.
     * When OFF, both are set to false.
     */
    private static ResetableOption reflexMerged() {
        boolean factoryDefault = false;
        OptionInstance<Boolean> option = OptionInstance.createBoolean(
            "caustica.options.rt.reflexMerged",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.reflexMerged.tooltip")),
            factoryDefault,
            enabled -> {
                if (CausticaConfig.Rt.Reflex.ENABLED.value() != enabled
                        || CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value() != enabled) {
                    CausticaConfig.Rt.Reflex.ENABLED.set(enabled);
                    CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.set(enabled);
                    Minecraft.getInstance().invalidateSurfaceConfiguration();
                }
            });
        boolean current = CausticaConfig.Rt.Reflex.ENABLED.value()
                && CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value();
        option.set(current);
        return new ResetableOption(option, factoryDefault);
    }

    /**
     * FG Sync: locks the render-rate cap to refresh rate ÷ Frame Generation multiplier (160 Hz at 3x →
     * 53.333) while FG is presenting, keeping rendered frames on whole vblank multiples; frames without
     * FG running are uncapped. The cap slider below is display-only (locked) while this is on; turning
     * this off also clears the manual cap to Unlimited — the manual value was unreachable while synced,
     * so any stored one predates the toggle — and the slider can then be dragged for a manual cap.
     */
    private static ResetableOption fgSync() {
        BooleanSetting setting = CausticaConfig.Rt.SYNC_FRAME_CAP;
        boolean factoryDefault = setting.defaultValue();
        OptionInstance<Boolean> option = OptionInstance.createBoolean(
            "caustica.options.rt.fgSync",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fgSync.tooltip")),
            factoryDefault,
            value -> {
                if (setting.value() != value) {
                    setting.set(value);
                    if (!value) {
                        // Turning sync off means "no cap": a stored manual value was unreachable while
                        // synced and is therefore stale (the pre-lock UI could silently leave one
                        // behind), so clear it instead of resurrecting it as a hidden cap.
                        CausticaConfig.Rt.FPS_CAP.set(CausticaConfig.Rt.FPS_CAP.defaultValue());
                    }
                    requestUiRefresh();
                }
            });
        option.set(setting.value());
        lastFgSyncInstance = option;
        return new ResetableOption(option, factoryDefault);
    }

    /**
     * Fractional render-rate cap (0.1 fps steps) with vanilla Max Framerate semantics — it caps the
     * render loop, so Frame Generation multiplies on top: at 6x, a cap of 26.7 still presents ~160 fps.
     * A present-count limiter like RTSS cannot express that (it would clamp the generated frames too).
     * Uses vanilla's own slider mapping trick (integer slider positions xmapped to fractions) and the
     * slider maximum (260.0) doubles as the unlimited sentinel, shown via vanilla's own "Unlimited"
     * label. The widget is locked (display only) while FG Sync is on and mirrors the derived rate;
     * pacing reads the live value in {@code RtFpsCap}. Composes with vanilla's Max Framerate: both wait
     * at frame end, so the lower cap wins.
     */
    private static ResetableOption fpsCap() {
        FloatSetting setting = CausticaConfig.Rt.FPS_CAP;
        float factoryDefault = setting.defaultValue();
        OptionInstance<Double> option = new OptionInstance<>(
                "caustica.options.rt.fpsCap",
                OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fpsCap.tooltip")),
                (caption, fps) -> Options.genericValueLabel(caption,
                        CausticaConfig.Rt.SYNC_FRAME_CAP.value() && CausticaConfig.Rt.Fg.ENABLED.value()
                                ? Component.literal(String.format(Locale.ROOT, "%.3f fps", fps))
                                : fps >= 260.0
                                        ? Component.translatable("options.framerateLimit.max")
                                        : Component.literal(String.format(Locale.ROOT, "%.1f fps", fps))),
                new OptionInstance.IntRange(100, 2600).xmap(
                        slider -> slider / 10.0,
                        fps -> (int) Math.round(fps * 10.0),
                        true),
                (double) factoryDefault,
                fps -> {
                    if (syncingCapDisplay) {
                        return;
                    }
                    setting.set(fps.floatValue());
                });
        syncingCapDisplay = true;
        try {
            option.set(currentDisplayCap());
        } finally {
            syncingCapDisplay = false;
        }
        lastFpsCapInstance = option;
        return new ResetableOption(option, (double) factoryDefault);
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