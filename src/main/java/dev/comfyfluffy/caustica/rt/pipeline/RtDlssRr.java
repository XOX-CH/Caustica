package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.ngx.NgxLibrary;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * DLSS backend for the RT renderer. Supports two modes depending on the {@code ENABLED} toggle:
 * <ul>
 *   <li><b>RR mode</b> ({@code ENABLED = true}): runs the DLSSD (Ray Reconstruction) feature,
 *       denoising and upscaling the path-traced color + guide buffers in one pass.</li>
 *   <li><b>SR mode</b> ({@code ENABLED = false}): runs the DLSS Super Resolution feature,
 *       upscaling only the path-traced color to display resolution.</li>
 * </ul>
 * Both modes share the same render-scale percentage ({@link CausticaConfig.Rt.DlssRr#QUALITY}).
 */
public final class RtDlssRr {
    public static final RtDlssRr INSTANCE = new RtDlssRr();

    /**
     * Returns true when DLSS rendering is active — either as Ray Reconstruction or as plain
     * Super Resolution upscaling. Active whenever the RR toggle is on, or the render scale
     * is below 100% (SR upscaling), or the render scale is 100% with RR off (DLAA).
     * To disable DLSS entirely, set RR off and turn off the DLSS super resolution toggle.
     */
    public static boolean enabled() {
        return CausticaConfig.Rt.DlssRr.ENABLED.value() || quality() <= 100;
    }

    /** True when the RR toggle is specifically on (Ray Reconstruction mode). */
    private static boolean isRrMode() {
        return CausticaConfig.Rt.DlssRr.ENABLED.value();
    }

    /** Render scale percentage (1-100). */
    public static int quality() {
        return CausticaConfig.Rt.DlssRr.QUALITY.value();
    }

    /** RR preset value (used when {@link #isRrMode()} is true). */
    private static int renderPreset() {
        return CausticaConfig.Rt.DlssRr.PRESET.value();
    }

    /** Upscale/SR preset value (used when {@link #isRrMode()} is false). */
    private static int upscalePreset() {
        return CausticaConfig.Rt.DlssRr.UPSCALE_PRESET.value();
    }

    // DLSS feature flags. IsHDR (bit 0): color is scene-linear ACEScg HDR (rgba16f) — RR requires it ("HDR Color
    // required"). MVLowRes (bit 1): motion vectors are at render/input resolution, not display — RR
    // requires it ("Low resolution Motion Vectors required"). DepthInverted (bit 3): the depth guide is
    // HW reversed-Z (near=1, far=0). AutoExposure (bit 6): in HDR mode DLSS needs the scene exposure
    // (exposure texture or auto-estimate); without it the output is black, so let DLSS estimate exposure
    // from the color itself. MVs are unjittered, so no MV_JITTERED.
    private static final int FEATURE_FLAG_IS_HDR = 1 << 0;
    private static final int FEATURE_FLAG_MV_LOW_RES = 1 << 1;
    private static final int FEATURE_FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int FEATURE_FLAG_AUTO_EXPOSURE = 1 << 6;
    private static final int FEATURE_FLAGS = FEATURE_FLAG_IS_HDR | FEATURE_FLAG_MV_LOW_RES
            | FEATURE_FLAG_DEPTH_INVERTED | FEATURE_FLAG_AUTO_EXPOSURE;

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean loggedRrAvailable;
    private boolean loggedSrAvailable;

    private boolean featureIsRr;
    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;
    private int featurePreset = Integer.MIN_VALUE;

    private boolean resetHistory;
    private long lastFrameNanos;

    private RtDlssRr() {
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /**
     * Record a DLSS evaluation. In RR mode this runs the Ray Reconstruction feature (denoise +
     * upscale with all guide buffers). In SR mode it runs the Super Resolution feature (plain
     * upscale using only color, depth and motion vectors). Returns false on failure, which
     * causes the caller to fall back to a linear blit upscale.
     */
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion,
                            RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                            RtImage specularMotion, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc;
            if (isRrMode()) {
                rc = evaluateRr(cmd, color, depth, motion, diffuseAlbedo, specularAlbedo,
                        normals, specularMotion, out,
                        renderWidth, renderHeight, displayWidth, displayHeight,
                        jitterX, jitterY, worldToView, viewToClip, frameMs);
            } else {
                rc = evaluateSr(cmd, color, depth, motion, out,
                        renderWidth, renderHeight, displayWidth, displayHeight,
                        jitterX, jitterY, frameMs);
            }
            resetHistory = false;
            if (NgxRuntime.ngxFailed(rc)) {
                String tag = isRrMode() ? "dlssd" : "dlss";
                throw new IllegalStateException("ngxshim_evaluate_" + tag + " failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("DLSS evaluate failed; RT composite continues without it", t);
            return false;
        }
    }

    private int evaluateRr(long cmd, RtImage color, RtImage depth, RtImage motion,
                           RtImage diffuseAlbedo, RtImage specularAlbedo, RtImage normals,
                           RtImage specularMotion, RtImage out,
                           int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                           float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip,
                           float frameMs) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment worldToViewMatrix = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
            MemorySegment viewToClipMatrix = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
            putNgxLeftMultiplyMatrix(worldToView, worldToViewMatrix);
            putNgxLeftMultiplyMatrix(viewToClip, viewToClipMatrix);
            return lib.evaluateDlssd(cmd, feature,
                    color.view, color.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    depth.view, depth.image, VK10.VK_FORMAT_R32_SFLOAT,
                    motion.view, motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    diffuseAlbedo.view, diffuseAlbedo.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specularAlbedo.view, specularAlbedo.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    normals.view, normals.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specularMotion.view, specularMotion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    0L, 0L, 0,
                    out.view, out.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    // jitter in render pixels; MVs are already in render-pixel units, so MV scale = 1.
                    jitterX, jitterY, 1.0f, 1.0f, resetHistory ? 1 : 0, frameMs,
                    worldToViewMatrix, viewToClipMatrix);
        }
    }

    private int evaluateSr(long cmd, RtImage color, RtImage depth, RtImage motion,
                           RtImage out,
                           int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                           float jitterX, float jitterY, float frameMs) {
        return lib.evaluate(cmd, feature,
                color.view, color.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                depth.view, depth.image, VK10.VK_FORMAT_R32_SFLOAT,
                motion.view, motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                out.view, out.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                renderWidth, renderHeight, displayWidth, displayHeight,
                jitterX, jitterY, 1.0f, 1.0f, resetHistory ? 1 : 0, frameMs);
    }

    /**
     * Computes the render resolution from the current render-scale percentage, bypassing the
     * NVSDK quality enum. Returns {@code null} only when DLSS is inactive (disabled or failed)
     * — in that state the caller should trace at full resolution.
     */
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return null;
        }
        int scale = quality();
        int renderWidth = (int) (displayWidth * scale / 100.0);
        int renderHeight = (int) (displayHeight * scale / 100.0);
        return new int[] { renderWidth, renderHeight };
    }

    /**
     * Maps the render-scale percentage to the closest NVSDK_NGX_PerfQuality_Value enum value
     * for the native shim. The actual render size is computed directly from the percentage;
     * this is only a hint for NGX's internal feature configuration.
     */
    private static int nvSdkQualityForPercent(int percent) {
        if (percent >= 100) return 5;  // DLAA
        if (percent >= 67) return 2;   // Quality
        if (percent >= 58) return 1;   // Balanced
        if (percent >= 50) return 0;   // Performance
        return 3;                       // Ultra Performance
    }

    /**
     * Ensure NGX is initialized and a DLSS feature exists for the given resolutions, creating it
     * into the supplied recording command buffer. Creates a Ray Reconstruction feature when RR mode
     * is active, or a Super Resolution feature otherwise. Returns false (and disables itself) on any
     * failure so the caller falls back to the non-DLSS path.
     */
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        try {
            ensureInitialized(device);
            int scalePercent = quality();
            int nvSdkQuality = nvSdkQualityForPercent(scalePercent);
            boolean rrMode = isRrMode();
            int preset = rrMode ? renderPreset() : upscalePreset();
            if (featureIsRr != rrMode
                    || featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || featureQuality != scalePercent || featurePreset != preset
                    || isNull(feature)) {
                // Mode switch (RR ↔ SR) resets the failure flag so a previously failed mode
                // can be retried when the user toggles back.
                if (featureIsRr != rrMode) {
                    failed = false;
                }
                releaseFeature(device);
                feature = rrMode
                        ? lib.createDlssd(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
                                nvSdkQuality, FEATURE_FLAGS, preset)
                        : lib.createDlss(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
                                nvSdkQuality, FEATURE_FLAGS, preset);
                if (isNull(feature)) {
                    String tag = rrMode ? "dlssd" : "dlss";
                    throw new IllegalStateException("ngxshim_create_" + tag + " failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureIsRr = rrMode;
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                featureQuality = scalePercent;
                featurePreset = preset;
                resetHistory = true; // a fresh feature has no temporal history
                String tag = rrMode ? "RR" : "SR";
                CausticaMod.LOGGER.info("DLSS {} feature created: {}x{} -> {}x{} (scale={}%, nvQuality={}, preset={})",
                        tag, renderWidth, renderHeight, displayWidth, displayHeight, scalePercent, nvSdkQuality, preset);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("DLSS feature setup failed; RT composite continues without it", t);
            return false;
        }
    }

    private void ensureInitialized(VulkanDevice device) {
        if (initialized) {
            return;
        }
        // NGX init/shutdown is owned by the shared NgxRuntime so RR and Frame Generation can coexist
        // (releasing the DLSS feature must not tear NGX down while FG still holds a handle).
        lib = NgxRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            throw new IllegalStateException("NGX runtime unavailable; DLSS cannot initialize");
        }
        boolean rrAvail = lib.dlssdAvailable();
        if (!loggedRrAvailable) {
            loggedRrAvailable = true;
            CausticaMod.LOGGER.info("DLSS Ray Reconstruction available: {}", rrAvail);
        }
        boolean srAvail = lib.dlssAvailable();
        if (!loggedSrAvailable) {
            loggedSrAvailable = true;
            CausticaMod.LOGGER.info("DLSS Super Resolution available: {}", srAvail);
        }
        boolean rrMode = isRrMode();
        if (rrMode && !rrAvail) {
            throw new IllegalStateException("DLSS Ray Reconstruction is not available on this system");
        }
        if (!rrMode && !srAvail) {
            throw new IllegalStateException("DLSS Super Resolution is not available on this system");
        }
        initialized = true;
    }

    /**
     * Release the DLSS feature. Does NOT shut down NGX — that is the shared {@link NgxRuntime}'s job at device
     * teardown ({@code NgxRuntime.shutdown()} in {@code CausticaClient.shutdownRt}), so FG can keep using NGX.
     */
    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseFeature(device);
        }
        initialized = false;
        lib = null;
    }

    private void releaseFeature(VulkanDevice device) {
        if (!isNull(feature)) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null && ctx.device() == device) {
                ctx.waitIdle();
            } else {
                VK10.vkDeviceWaitIdle(device.vkDevice());
            }
            lib.release(feature);
            feature = MemorySegment.NULL;
        }
        featureIsRr = false;
        featureRenderWidth = -1;
        featureRenderHeight = -1;
        featureDisplayWidth = -1;
        featureDisplayHeight = -1;
        featureQuality = Integer.MIN_VALUE;
        featurePreset = Integer.MIN_VALUE;
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }

    private static void putNgxLeftMultiplyMatrix(Matrix4fc m, MemorySegment dst) {
        // NGX wants row-major matrices used with left-multiplied row vectors. Our JOML/GLSL matrices are
        // used with column vectors, so the equivalent NGX matrix is the transpose; JOML's normal storage
        // order is exactly row-major storage of that transpose.
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 0, m.m00());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 1, m.m01());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 2, m.m02());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 3, m.m03());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 4, m.m10());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 5, m.m11());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 6, m.m12());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 7, m.m13());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 8, m.m20());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 9, m.m21());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 10, m.m22());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 11, m.m23());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 12, m.m30());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 13, m.m31());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 14, m.m32());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 15, m.m33());
    }
}