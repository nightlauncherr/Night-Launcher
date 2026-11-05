package com.kestrel.menu;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Night's menu backdrop, the same purple nebula as the launcher: a deep violet
 * gradient with soft purple and magenta clouds, faint stars, a purple light
 * behind the character, a soft vignette, and a column of slowly flowing
 * lavender ribbons on the right with a few drifting sparkles. Drawn with plain
 * coloured geometry (no textures), allocation-free, and it puts back the GL
 * state it changes.
 *
 * <p>Per frame: each ribbon's centre line is evaluated once and shared by its
 * fill and its highlight, all ribbons go out in one draw call and all sparkles
 * in another (same primitives in the same order as before, so the blending
 * and the look are unchanged), and the shade model is forced only once.
 */
public final class MenuBackground {
    private static final int RIBBONS = 7;
    private static final int STEPS = 48;
    private static final int SPARKS = 12;
    private static final int STARS = 70;
    private static final float[] SPARK_DX = {0f, 1f, 0f, -1f};
    private static final float[] SPARK_DY = {-1f, 0f, 1f, 0f};
    /** The vertical part of each centre-line phase, which never changes. */
    private static final double[] PHASE_Y = new double[STEPS + 1];
    /** One ribbon's centre line for this frame (reused, game thread only). */
    private static final float[] CENTRE = new float[STEPS + 1];
    /** Whether this frame's real glShadeModel call has been made (see {@link #smooth()}). */
    private static boolean shadeSynced;

    static {
        for (int i = 0; i <= STEPS; i++) PHASE_Y[i] = i / (double) STEPS * Math.PI * 2.1;
    }

    private MenuBackground() {
    }

    private static void v(WorldRenderer wr, double x, double y, int rgb, float a) {
        wr.pos(x, y, 0).color((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, a).endVertex();
    }

    private static void begin() {
        // Flat 2D shapes: winding varies (strips, fans), so never cull them.
        GlStateManager.disableCull();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        smooth();
    }

    /**
     * Smooth shading, forced: GlStateManager skips the GL call when its cached
     * value already says SMOOTH, and in the menu that cache can be stale, which
     * leaves every gradient flat (and our fades, whose last vertex is clear,
     * invisible). Toggling through FLAT always issues the real call.
     */
    static void smooth() {
        if (shadeSynced) {
            // Already forced this frame: from here the cache is right and this is free when unchanged.
            GlStateManager.shadeModel(GL11.GL_SMOOTH);
            return;
        }
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        shadeSynced = true;
    }

    private static void end() {
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /**
     * @param time     seconds (wall clock) for the slow flow
     * @param ribbonIn 0..1 slide-in of the ribbons from the right
     * @param glowX    x of the warm light (the character), glowY its centre
     */
    public static void draw(int w, int h, float time, float ribbonIn, float glowX, float glowY, float glowR) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        shadeSynced = false; // first thing each frame: force the real shade-model call once
        begin();

        // Base gradient: deep violet at the top left, midnight at the bottom right.
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        v(wr, w, 0, 0x120B20, 1f);
        v(wr, 0, 0, 0x1A102B, 1f);
        v(wr, 0, h, 0x120B20, 1f);
        v(wr, w, h, 0x0B0714, 1f);
        t.draw();

        // Nebula clouds: a few very soft purple and magenta lights drifting slowly.
        float big = Math.max(w, h);
        radial(wr, t, w * (0.30f + 0.02f * (float) Math.sin(time * 0.021f)),
                h * (0.34f + 0.02f * (float) Math.cos(time * 0.016f)), big * 0.55f, 0x8652D9, 0.11f, 40);
        radial(wr, t, w * (0.78f + 0.02f * (float) Math.cos(time * 0.018f)),
                h * (0.76f + 0.02f * (float) Math.sin(time * 0.013f)), big * 0.40f, 0xC05AE8, 0.07f, 40);
        radial(wr, t, w * 0.55f, h * 0.10f, big * 0.30f, 0xA16BEF, 0.05f, 32);
        stars(wr, t, w, h, time);

        // Purple light behind the character.
        radial(wr, t, glowX, glowY, glowR, 0x8652D9, 0.16f, 40);
        // Soft lavender light upper left behind the logo.
        radial(wr, t, w * 0.16f, h * 0.30f, big * 0.45f, 0xC6A4FF, 0.05f, 40);

        // Ribbons: each a band whose centre line sways; the fill is light on the leading edge and
        // fades across, which reads as folded silk.
        float slide = (1f - ribbonIn) * w * 0.45f;
        if (ribbonIn > 0f) {
            // All ribbons in one draw; per ribbon the fill, then its highlight (the original order).
            wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int k = 0; k < RIBBONS; k++) {
                float base = w * (0.70f + 0.052f * k) + slide;
                float amp = h * (0.035f + 0.008f * k);
                float width = w * (0.05f + 0.006f * (k % 3));
                float speed = 0.22f + 0.04f * k;
                float alpha = (0.16f + 0.04f * (k % 2)) * ribbonIn;
                double phase = time * speed + k * 0.8;
                for (int i = 0; i <= STEPS; i++) {
                    double ph = PHASE_Y[i] + phase;
                    CENTRE[i] = base + amp * (float) Math.sin(ph) + amp * 0.35f * (float) Math.sin(ph * 2.3 + k);
                }
                float half = width * 0.5f;
                float hi = alpha * 1.6f;
                for (int i = 0; i < STEPS; i++) {
                    float y0 = h * i / (float) STEPS;
                    float y1 = h * (i + 1) / (float) STEPS;
                    float c0 = CENTRE[i], c1 = CENTRE[i + 1];
                    v(wr, c0 - half, y0, 0xE6DCFF, alpha);
                    v(wr, c0 + half, y0, 0xA16BEF, 0f);
                    v(wr, c1 + half, y1, 0xA16BEF, 0f);
                    v(wr, c1 - half, y1, 0xE6DCFF, alpha);
                }
                // A thin highlight along the leading edge.
                for (int i = 0; i < STEPS; i++) {
                    float y0 = h * i / (float) STEPS;
                    float y1 = h * (i + 1) / (float) STEPS;
                    float e0 = CENTRE[i] - half, e1 = CENTRE[i + 1] - half;
                    v(wr, e0 - 1.2f, y0, 0xFFFFFF, 0f);
                    v(wr, e0 + 0.6f, y0, 0xFFFFFF, hi);
                    v(wr, e1 + 0.6f, y1, 0xFFFFFF, hi);
                    v(wr, e1 - 1.2f, y1, 0xFFFFFF, 0f);
                }
            }
            t.draw();

            // A few sparkles drifting up along the ribbons, twinkling; all in one draw.
            wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
            for (int s = 0; s < SPARKS; s++) {
                float seed = s * 12.9898f;
                float fx = frac((float) Math.sin(seed) * 43758.545f);
                float fy = frac((float) Math.sin(seed * 1.7f) * 24634.634f);
                float x = w * (0.72f + 0.26f * fx) + slide;
                float y = h - frac(fy + time * (0.015f + 0.01f * (s % 3))) * h * 1.1f + h * 0.05f;
                float tw = 0.5f + 0.5f * (float) Math.sin(time * (1.3f + 0.3f * (s % 4)) + s);
                float r = 1.4f + (s % 3) * 0.6f;
                sparkle(wr, x, y, r, 0.55f * tw * ribbonIn);
            }
            t.draw();
        }

        // Vignette: darken the edges.
        edge(wr, t, w, h);
        end();
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    /** Faint fixed stars (positions from a hash, so they never move); a few twinkle. One draw call. */
    private static void stars(WorldRenderer wr, Tessellator t, int w, int h, float time) {
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int s = 0; s < STARS; s++) {
            float fx = frac((float) Math.sin(s * 12.9898f + 3.1f) * 43758.545f);
            float fy = frac((float) Math.sin(s * 78.233f + 1.7f) * 24634.634f);
            float fb = frac((float) Math.sin(s * 39.425f) * 15731.743f);
            float x = w * fx;
            float y = h * fy;
            float a = 0.18f + 0.30f * fb;
            if (s % 7 == 0) a *= 0.6f + 0.4f * (float) Math.sin(time * (0.8f + 0.2f * (s % 3)) + s);
            float r = s % 11 == 0 ? 1.0f : 0.5f;
            v(wr, x + r, y - r, 0xE6DCFF, a);
            v(wr, x - r, y - r, 0xE6DCFF, a);
            v(wr, x - r, y + r, 0xE6DCFF, a);
            v(wr, x + r, y + r, 0xE6DCFF, a);
        }
        t.draw();
    }

    /** Soft round light: centre alpha a, fading to nothing at radius r. */
    public static void radial(WorldRenderer wr, Tessellator t, float x, float y, float r, int rgb, float a, int segs) {
        if (a <= 0.002f || r <= 0f) return;
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        v(wr, x, y, rgb, a);
        for (int i = 0; i <= segs; i++) {
            double an = i * Math.PI * 2 / segs;
            v(wr, x + Math.cos(an) * r, y + Math.sin(an) * r, rgb, 0f);
        }
        t.draw();
    }

    /** Four-pointed sparkle, added to a GL_TRIANGLES batch that is already begun. */
    private static void sparkle(WorldRenderer wr, float x, float y, float r, float a) {
        if (a <= 0.01f) return;
        float[] dx = SPARK_DX;
        float[] dy = SPARK_DY;
        r *= 2.6f;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            v(wr, x, y, 0xFFFFFF, a);
            v(wr, x + dx[i] * r, y + dy[i] * r, 0xFFFFFF, 0f);
            v(wr, x + (dx[i] + dx[j]) * 0.18f * r, y + (dy[i] + dy[j]) * 0.18f * r, 0xFFFFFF, a * 0.4f);
            v(wr, x, y, 0xFFFFFF, a);
            v(wr, x + (dx[i] + dx[j]) * 0.18f * r, y + (dy[i] + dy[j]) * 0.18f * r, 0xFFFFFF, a * 0.4f);
            v(wr, x + dx[j] * r, y + dy[j] * r, 0xFFFFFF, 0f);
        }
    }

    private static void edge(WorldRenderer wr, Tessellator t, int w, int h) {
        float e = Math.min(w, h) * 0.22f;
        int c = 0x000000;
        float a = 0.45f;
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // top
        v(wr, w, 0, c, a);
        v(wr, 0, 0, c, a);
        v(wr, 0, e, c, 0f);
        v(wr, w, e, c, 0f);
        // bottom
        v(wr, w, h - e, c, 0f);
        v(wr, 0, h - e, c, 0f);
        v(wr, 0, h, c, a);
        v(wr, w, h, c, a);
        // left
        v(wr, e, 0, c, 0f);
        v(wr, 0, 0, c, a);
        v(wr, 0, h, c, a);
        v(wr, e, h, c, 0f);
        // right
        v(wr, w, 0, c, a);
        v(wr, w - e, 0, c, 0f);
        v(wr, w - e, h, c, 0f);
        v(wr, w, h, c, a);
        t.draw();
    }

    /** Soft elliptical floor shadow under the character. */
    public static void shadow(float x, float y, float rx, float ry, float a) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        begin();
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        v(wr, x, y, 0x000000, a);
        for (int i = 0; i <= 36; i++) {
            double an = i * Math.PI * 2 / 36;
            v(wr, x + Math.cos(an) * rx, y + Math.sin(an) * ry, 0x000000, 0f);
        }
        t.draw();
        end();
    }

    /** Plain rectangle with a vertical gradient (alpha per end). */
    public static void gradientRect(float x0, float y0, float x1, float y1, int rgb, float aTop, float aBottom) {
        Tessellator t = Tessellator.getInstance();
        WorldRenderer wr = t.getWorldRenderer();
        begin();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        v(wr, x1, y0, rgb, aTop);
        v(wr, x0, y0, rgb, aTop);
        v(wr, x0, y1, rgb, aBottom);
        v(wr, x1, y1, rgb, aBottom);
        t.draw();
        end();
    }
}
