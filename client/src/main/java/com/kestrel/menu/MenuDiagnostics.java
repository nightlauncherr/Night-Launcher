package com.kestrel.menu;

import com.kestrel.KestrelMod;
import com.kestrel.performance.FrameStats;
import net.minecraft.client.Minecraft;

/**
 * Optional frame-time measurement for the Kestrel menus, for checking
 * performance ({@code -Dkestrel.menuStats=true}). Off by default: then it does
 * nothing. When on, every 5 seconds it logs the menu's average FPS, 1% low,
 * worst frame, the time spent drawing the menu itself and the frame limit the
 * game applied.
 */
public final class MenuDiagnostics {
    static final boolean ENABLED = Boolean.getBoolean("kestrel.menuStats");
    private static final long REPORT_NS = 5_000_000_000L;

    private final String name;
    private final FrameStats frames = new FrameStats(1200);
    private long windowStart;
    private long drawNanos;
    private long drawMax;
    private int draws;

    MenuDiagnostics(String name) {
        this.name = name;
    }

    /** Call at the start of drawing; returns a timestamp for {@link #end}. */
    long begin() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Call at the end of drawing. */
    void end(long start) {
        if (!ENABLED) return;
        long now = System.nanoTime();
        long d = now - start;
        drawNanos += d;
        if (d > drawMax) drawMax = d;
        draws++;
        frames.frame(now);
        if (windowStart == 0) windowStart = now;
        if (now - windowStart >= REPORT_NS) {
            frames.update(System.currentTimeMillis()); // reports are 5 s apart, past its 0.5 s throttle
            Minecraft mc = Minecraft.getMinecraft();
            KestrelMod.LOG.info(String.format(
                    "[Kestrel] menu stats (%s): avg %d fps, 1%% low %d fps, worst frame %.1f ms, menu draw avg %.2f ms max %.2f ms,"
                            + " frame limit %d (max framerate setting %d), vsync %s, %dx%d",
                    name, frames.avgFps(), frames.onePercentLowFps(), frames.maxFrameMs(), drawNanos / 1e6 / Math.max(1, draws),
                    drawMax / 1e6, mc.getLimitFramerate(), mc.gameSettings.limitFramerate, mc.gameSettings.enableVsync, mc.displayWidth, mc.displayHeight));
            frames.reset();
            windowStart = now;
            drawNanos = 0;
            drawMax = 0;
            draws = 0;
        }
    }
}
