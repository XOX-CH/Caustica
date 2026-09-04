package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;

import net.minecraft.client.Minecraft;

import java.util.concurrent.locks.LockSupport;

/**
 * Fractional render-rate cap — vanilla {@code FramerateLimiter} semantics at 0.1 fps resolution.
 *
 * <p>Vanilla's Max Framerate caps the render loop in whole 10 fps steps and cannot express the base
 * rates Frame Generation divisors imply (160 Hz / 6 = 26.67). External present-count limiters such as
 * RTSS cap {@code vkQueuePresentKHR} calls, so under FG they cap the generated frames too — locking 26
 * there yields a 26 fps slideshow. This cap sits where vanilla's does (end of {@code renderFrame},
 * after present): it limits real rendered frames only, and Frame Generation multiplies on top — 26.7
 * at 6x paces rendering while ~160 fps is presented.
 *
 * <p>The waiting algorithm mirrors vanilla's: relative anchoring (target = previous frame end +
 * interval; an overrun frame re-anchors instead of bursting to catch up), {@code parkNanos} for the
 * bulk of the wait minus a learned average-overshoot correction (EMA, capped at 2 ms), and
 * {@code onSpinWait} for the final 0.5 ms. Composes with vanilla's own limiter — both are end-of-frame
 * waits, so the stricter cap simply dominates.
 *
 * <p>With FG Sync enabled the cap follows refresh rate ÷ FG multiplier while Frame Generation is
 * presenting (160 Hz at 6x → 26.667 rendered, ~160 presented), keeping rendered frames on whole vblank
 * multiples; frames without FG presenting (menus, loading, FG disabled) run uncapped. With FG Sync off
 * the manual {@code fps-cap} value applies.
 */
public final class RtFpsCap {
    public static final RtFpsCap INSTANCE = new RtFpsCap();

    /** Sentinel: values at/above the slider maximum disable the cap. */
    private static final float DISABLED_FPS = 260.0f;

    private static final long SPIN_THRESHOLD_NS = 500_000L;
    private static final long OVERSHOOT_EMA_LIMIT_NS = 2_000_000L;
    private static final long MAX_USEFUL_OVERSHOOT_NS = 25_000_000L;
    private static final long REFRESH_RECHECK_NS = 2_000_000_000L;

    private long lastFrameEndNs;
    private long averageOvershootNs;
    private float lastCap = Float.NaN;

    private int cachedRefreshRate;
    private long refreshRateCheckedNs;

    private RtFpsCap() {
    }

    /**
     * Called once per frame at the end of {@code renderFrame}, the same spot vanilla applies its own
     * framerate limit. No-op while the setting is at the unlimited sentinel.
     */
    public void endFrame() {
        float cap = effectiveCap();
        if (cap >= DISABLED_FPS) {
            lastFrameEndNs = 0L;
            lastCap = Float.NaN;
            return;
        }
        long now = System.nanoTime();
        if (lastFrameEndNs == 0L || cap != lastCap) {
            // First frame after enabling or a target change: anchor the timeline, pace from the next
            // frame. Resetting the overshoot EMA matches vanilla's behavior on a limit change.
            lastFrameEndNs = now;
            lastCap = cap;
            averageOvershootNs = 0L;
            return;
        }
        waitUntil(lastFrameEndNs + intervalNs(cap));
        lastFrameEndNs = System.nanoTime();
    }

    private static long intervalNs(float fps) {
        return (long) (1_000_000_000L / fps);
    }

    /**
     * The cap in force this frame: with FG Sync on, refresh rate ÷ multiplier while Frame Generation is
     * actually presenting (whole-vblank alignment for the FG pacing), and no cap at all otherwise
     * (menus, loading, FG off — without interpolation running a synced rate would be a plain
     * slideshow). With FG Sync off, the manual {@code fps-cap} value.
     */
    private float effectiveCap() {
        if (CausticaConfig.Rt.SYNC_FRAME_CAP.value()) {
            if (RtFramePresenter.INSTANCE.isActive()) {
                int refreshRate = cachedRefreshRate();
                if (refreshRate > 0) {
                    return refreshRate / (float) (CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value() + 1);
                }
            }
            return DISABLED_FPS;
        }
        return CausticaConfig.Rt.FPS_CAP.value();
    }

    private int cachedRefreshRate() {
        long now = System.nanoTime();
        if (cachedRefreshRate == 0 || now - refreshRateCheckedNs > REFRESH_RECHECK_NS) {
            refreshRateCheckedNs = now;
            int rate = Minecraft.getInstance().getWindow().getRefreshRate();
            if (rate > 0) {
                cachedRefreshRate = rate;
            }
        }
        return cachedRefreshRate;
    }

    private void waitUntil(long targetNs) {
        while (true) {
            long remaining = targetNs - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            if (remaining > averageOvershootNs + SPIN_THRESHOLD_NS) {
                long parkNs = remaining - averageOvershootNs - SPIN_THRESHOLD_NS;
                long parkStartNs = System.nanoTime();
                LockSupport.parkNanos(parkNs);
                long oversleptNs = System.nanoTime() - parkStartNs - parkNs;
                if (oversleptNs > 0L && oversleptNs < MAX_USEFUL_OVERSHOOT_NS) {
                    averageOvershootNs = (long) (0.1 * oversleptNs + 0.9 * averageOvershootNs);
                    averageOvershootNs = Math.min(averageOvershootNs, OVERSHOOT_EMA_LIMIT_NS);
                }
            } else {
                Thread.onSpinWait();
            }
        }
    }
}
