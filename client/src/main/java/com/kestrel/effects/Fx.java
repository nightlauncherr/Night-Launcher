package com.kestrel.effects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Immediate-mode 3D geometry toolkit for the advanced kill effects.
 *
 * <p>Render thread only. Every primitive draws from precomputed tables and
 * caller-owned arrays, so drawing allocates nothing per frame. Effects call
 * {@link #begin} / {@link #end} around their drawing; {@code end} puts back
 * exactly the state {@code begin} changed (texture, lighting, blend, depth
 * mask, culling, alpha test, shade model, line width, matrix).
 *
 * <p>Billboarded primitives ({@link #glow}, {@link #ribbon},
 * {@link #billboardRing}) use the camera basis captured in {@code begin}, so
 * they must be drawn without extra rotations on the matrix stack.
 */
final class Fx {
    static final int SEG = 48;
    private static final float[] COS = new float[SEG + 1];
    private static final float[] SIN = new float[SEG + 1];
    private static final int LAT = 12;
    private static final int LON = 20;
    /** Unit sphere vertices, (LAT+1) x (LON+1) x xyz. */
    private static final float[] SPHERE = new float[(LAT + 1) * (LON + 1) * 3];
    /** Fixed light direction for faceted solids (normalised). */
    private static final float LX = 0.36f;
    private static final float LY = 0.86f;
    private static final float LZ = 0.36f;

    static {
        for (int i = 0; i <= SEG; i++) {
            double a = 2 * Math.PI * i / SEG;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
        int k = 0;
        for (int la = 0; la <= LAT; la++) {
            double th = Math.PI * la / LAT;
            for (int lo = 0; lo <= LON; lo++) {
                double ph = 2 * Math.PI * lo / LON;
                SPHERE[k++] = (float) (Math.sin(th) * Math.cos(ph));
                SPHERE[k++] = (float) Math.cos(th);
                SPHERE[k++] = (float) (Math.sin(th) * Math.sin(ph));
            }
        }
    }

    /** Camera basis in world axes: right, up, forward (view direction). */
    static float rx, ry, rz, ux, uy, uz, fx, fy, fz;

    private static WorldRenderer wr;
    private static Tessellator tess;

    private Fx() {
    }

    // ------------------------------------------------------------------ state

    /** Start drawing an effect whose origin is (ox, oy, oz); additive blending. */
    static void begin(double ox, double oy, double oz, double cx, double cy, double cz) {
        tess = Tessellator.getInstance();
        wr = tess.getWorldRenderer();
        GlStateManager.pushMatrix();
        GlStateManager.translate(ox - cx, oy - cy, oz - cz);
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        RenderManager rm = Minecraft.getMinecraft().getRenderManager();
        double yaw = Math.toRadians(rm.playerViewY);
        double pitch = Math.toRadians(rm.playerViewX);
        fx = (float) (-Math.sin(yaw) * Math.cos(pitch));
        fy = (float) -Math.sin(pitch);
        fz = (float) (Math.cos(yaw) * Math.cos(pitch));
        rx = (float) -Math.cos(yaw);
        ry = 0f;
        rz = (float) -Math.sin(yaw);
        ux = ry * fz - rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy - ry * fx;
    }

    static void end() {
        GL11.glLineWidth(1f);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    /** Light adds up (glows, fire, energy). */
    static void additive() {
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
    }

    /** Ordinary transparency (dark or solid-looking geometry). */
    static void normal() {
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    static void push(double x, double y, double z) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
    }

    static void rot(float deg, float ax, float ay, float az) {
        GlStateManager.rotate(deg, ax, ay, az);
    }

    static void scale(float sx, float sy, float sz) {
        GlStateManager.scale(sx, sy, sz);
    }

    static void pop() {
        GlStateManager.popMatrix();
    }

    // ----------------------------------------------------------------- colour

    private static void v(double x, double y, double z, int rgb, float a) {
        wr.pos(x, y, z).color((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, a).endVertex();
    }

    private static void vs(double x, double y, double z, int rgb, float shade, float a) {
        wr.pos(x, y, z).color((rgb >> 16 & 255) / 255f * shade, (rgb >> 8 & 255) / 255f * shade, (rgb & 255) / 255f * shade, a)
                .endVertex();
    }

    static int lerpColor(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int r = (int) ((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = (int) ((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** HSV (all 0..1) to 0xRRGGBB. */
    static int hsv(float h, float s, float v) {
        h = h - (float) Math.floor(h);
        float c = v * s;
        float hp = h * 6f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r, g, b;
        if (hp < 1) { r = c; g = x; b = 0; }
        else if (hp < 2) { r = x; g = c; b = 0; }
        else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; }
        else if (hp < 5) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        float m = v - c;
        return (int) ((r + m) * 255) << 16 | (int) ((g + m) * 255) << 8 | (int) ((b + m) * 255);
    }

    // ------------------------------------------------------------- billboards

    /** Soft round glow facing the camera: bright centre fading to nothing at the rim. */
    static void glow(double x, double y, double z, float radius, int rgb, float a) {
        if (a <= 0.003f || radius <= 0f) return;
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < SEG; i += 3) {
            int j = i + 3;
            v(x, y, z, rgb, a);
            v(x + (rx * COS[i] + ux * SIN[i]) * radius, y + (ry * COS[i] + uy * SIN[i]) * radius,
                    z + (rz * COS[i] + uz * SIN[i]) * radius, rgb, 0f);
            v(x + (rx * COS[j] + ux * SIN[j]) * radius, y + (ry * COS[j] + uy * SIN[j]) * radius,
                    z + (rz * COS[j] + uz * SIN[j]) * radius, rgb, 0f);
        }
        tess.draw();
    }

    /** Soft ring facing the camera (photon ring, halos). */
    static void billboardRing(double x, double y, double z, float radius, float width, int rgb, float a) {
        if (a <= 0.003f || radius <= 0f) return;
        float h = width * 0.5f;
        for (int pass = 0; pass < 2; pass++) {
            float r0 = pass == 0 ? radius - h : radius;
            float r1 = pass == 0 ? radius : radius + h;
            float a0 = pass == 0 ? 0f : a;
            float a1 = pass == 0 ? a : 0f;
            wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= SEG; i++) {
                float dx = rx * COS[i] + ux * SIN[i];
                float dy = ry * COS[i] + uy * SIN[i];
                float dz = rz * COS[i] + uz * SIN[i];
                v(x + dx * r0, y + dy * r0, z + dz * r0, rgb, a0);
                v(x + dx * r1, y + dy * r1, z + dz * r1, rgb, a1);
            }
            tess.draw();
        }
    }

    /**
     * Camera-facing ribbon along a polyline ({@code pts} = x,y,z triples, first
     * point is the head). Width and colour interpolate from head to tail; the
     * edges are soft (alpha 0) so it reads as a glowing streak.
     */
    static void ribbon(float[] pts, int n, float w0, float w1, int c0, int c1, float a0, float a1) {
        if (n < 2 || (a0 <= 0.003f && a1 <= 0.003f)) return;
        for (int pass = 0; pass < 2; pass++) {
            wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < n; i++) {
                int p = Math.max(0, i - 1) * 3;
                int q = Math.min(n - 1, i + 1) * 3;
                float tx = pts[q] - pts[p];
                float ty = pts[q + 1] - pts[p + 1];
                float tz = pts[q + 2] - pts[p + 2];
                // side = tangent x forward (perpendicular to both the line and the view)
                float sx = ty * fz - tz * fy;
                float sy = tz * fx - tx * fz;
                float sz = tx * fy - ty * fx;
                float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (len < 1e-5f) {
                    sx = rx;
                    sy = ry;
                    sz = rz;
                } else {
                    sx /= len;
                    sy /= len;
                    sz /= len;
                }
                float u = i / (float) (n - 1);
                float w = (w0 + (w1 - w0) * u) * 0.5f;
                int c = lerpColor(c0, c1, u);
                float a = a0 + (a1 - a0) * u;
                float x = pts[i * 3];
                float y = pts[i * 3 + 1];
                float z = pts[i * 3 + 2];
                float side = pass == 0 ? -w : w;
                v(x + sx * side, y + sy * side, z + sz * side, c, 0f);
                v(x, y, z, c, a);
            }
            tess.draw();
        }
    }

    // ------------------------------------------------------- flat (XZ plane)

    /** Soft horizontal ring (rotate the matrix for other planes). */
    static void ring(float radius, float width, int rgb, float a) {
        if (a <= 0.003f || radius <= 0f) return;
        float h = width * 0.5f;
        for (int pass = 0; pass < 2; pass++) {
            float r0 = pass == 0 ? Math.max(0f, radius - h) : radius;
            float r1 = pass == 0 ? radius : radius + h;
            float a0 = pass == 0 ? 0f : a;
            float a1 = pass == 0 ? a : 0f;
            wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= SEG; i++) {
                v(COS[i] * r0, 0, SIN[i] * r0, rgb, a0);
                v(COS[i] * r1, 0, SIN[i] * r1, rgb, a1);
            }
            tess.draw();
        }
    }

    /** Filled horizontal disc, centre alpha {@code a} fading to {@code rimA}. */
    static void disc(float radius, int rgb, float a, float rimA) {
        if ((a <= 0.003f && rimA <= 0.003f) || radius <= 0f) return;
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < SEG; i += 2) {
            int j = i + 2;
            v(0, 0, 0, rgb, a);
            v(COS[i] * radius, 0, SIN[i] * radius, rgb, rimA);
            v(COS[j] * radius, 0, SIN[j] * radius, rgb, rimA);
        }
        tess.draw();
    }

    /** Hard-edged horizontal arc band between radii r0..r1 and angles (radians). */
    static void arcBand(float r0, float r1, float from, float to, int rgb, float a) {
        if (a <= 0.003f || to <= from) return;
        int steps = Math.max(2, (int) ((to - from) / (2 * Math.PI) * SEG) + 1);
        wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= steps; i++) {
            double ang = from + (to - from) * i / steps;
            float c = (float) Math.cos(ang);
            float s = (float) Math.sin(ang);
            v(c * r0, 0, s * r0, rgb, a);
            v(c * r1, 0, s * r1, rgb, a);
        }
        tess.draw();
    }

    /** Flat horizontal stroke from (x0,z0) to (x1,z1). */
    static void stroke(float x0, float z0, float x1, float z1, float width, int rgb, float a) {
        if (a <= 0.003f) return;
        float dx = x1 - x0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-5f) return;
        float nx = -dz / len * width * 0.5f;
        float nz = dx / len * width * 0.5f;
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        v(x0 + nx, 0, z0 + nz, rgb, a);
        v(x1 + nx, 0, z1 + nz, rgb, a);
        v(x1 - nx, 0, z1 - nz, rgb, a);
        v(x0 - nx, 0, z0 - nz, rgb, a);
        tess.draw();
    }

    /** Outline of a regular polygon (horizontal band), rotated by {@code phase} radians. */
    static void polygonBand(int sides, float radius, float width, float phase, int rgb, float a) {
        if (a <= 0.003f || radius <= 0f) return;
        float r0 = Math.max(0f, radius - width * 0.5f);
        float r1 = radius + width * 0.5f;
        wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= sides; i++) {
            double ang = phase + 2 * Math.PI * i / sides;
            float c = (float) Math.cos(ang);
            float s = (float) Math.sin(ang);
            v(c * r0, 0, s * r0, rgb, a);
            v(c * r1, 0, s * r1, rgb, a * 0.35f);
        }
        tess.draw();
    }

    /** Flat spiral band from radius r0 to r1 over {@code turns} turns. */
    static void spiralBand(float r0, float r1, float turns, float phase, float width, int c0, int c1, float a0, float a1) {
        if (a0 <= 0.003f && a1 <= 0.003f) return;
        int steps = 40;
        wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= steps; i++) {
            float u = i / (float) steps;
            double ang = phase + u * turns * 2 * Math.PI;
            float r = r0 + (r1 - r0) * u;
            float w = width * (0.25f + 0.75f * (float) Math.sin(Math.PI * u)) * 0.5f;
            float c = (float) Math.cos(ang);
            float s = (float) Math.sin(ang);
            int col = lerpColor(c0, c1, u);
            float a = a0 + (a1 - a0) * u;
            v(c * (r - w), 0, s * (r - w), col, a);
            v(c * (r + w), 0, s * (r + w), col, a);
        }
        tess.draw();
    }

    // ------------------------------------------------------------------ solids

    /**
     * Sphere. {@code edge} blends from uniform alpha (0) to a Fresnel rim (1)
     * where the surface turns away from the viewer, which reads as an energy shell.
     */
    static void sphere(double x, double y, double z, float radius, int rgb, float a, float edge) {
        if (a <= 0.003f || radius <= 0f) return;
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int la = 0; la < LAT; la++) {
            for (int lo = 0; lo < LON; lo++) {
                sphereVertex(x, y, z, radius, la, lo, rgb, a, edge);
                sphereVertex(x, y, z, radius, la + 1, lo, rgb, a, edge);
                sphereVertex(x, y, z, radius, la + 1, lo + 1, rgb, a, edge);
                sphereVertex(x, y, z, radius, la, lo + 1, rgb, a, edge);
            }
        }
        tess.draw();
    }

    private static void sphereVertex(double x, double y, double z, float r, int la, int lo, int rgb, float a, float edge) {
        int k = (la * (LON + 1) + lo) * 3;
        float nx = SPHERE[k];
        float ny = SPHERE[k + 1];
        float nz = SPHERE[k + 2];
        float d = Math.abs(nx * fx + ny * fy + nz * fz);
        float fres = Math.min(1f, (1f - d) * (1f - d) * 1.8f);
        v(x + nx * r, y + ny * r, z + nz * r, rgb, a * ((1f - edge) + edge * fres));
    }

    /** Open cylinder / cone along +Y with an alpha gradient bottom to top. */
    static void beam(float r0, float r1, float h, int rgb, float a0, float a1) {
        if (a0 <= 0.003f && a1 <= 0.003f) return;
        wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= SEG; i += 2) {
            v(COS[i] * r0, 0, SIN[i] * r0, rgb, a0);
            v(COS[i] * r1, h, SIN[i] * r1, rgb, a1);
        }
        tess.draw();
    }

    /** One flat-shaded triangle (shade from the fixed light and the face normal). */
    private static void tri(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                            int rgb, float a) {
        float ex = bx - ax, ey = by - ay, ez = bz - az;
        float gx = cx - ax, gy = cy - ay, gz = cz - az;
        float nx = ey * gz - ez * gy;
        float ny = ez * gx - ex * gz;
        float nz = ex * gy - ey * gx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        float shade = 0.55f;
        if (len > 1e-6f) shade = 0.5f + 0.5f * Math.abs((nx * LX + ny * LY + nz * LZ) / len);
        vs(ax, ay, az, rgb, shade, a);
        vs(bx, by, bz, rgb, shade, a);
        vs(cx, cy, cz, rgb, shade, a);
    }

    /**
     * Crystal: a {@code sides}-sided bipyramid along +Y, from -down to +up.
     * Faceted shading makes it read as a solid even with blending.
     */
    static void crystal(float radius, float down, float up, int sides, int rgb, float a) {
        if (a <= 0.003f || up + down <= 0f) return;
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        int step = SEG / sides;
        for (int i = 0; i < SEG; i += step) {
            int j = (i + step) % SEG;
            float x0 = COS[i] * radius, z0 = SIN[i] * radius;
            float x1 = COS[j] * radius, z1 = SIN[j] * radius;
            tri(0, up, 0, x0, 0, z0, x1, 0, z1, rgb, a);
            if (down > 0f) tri(0, -down, 0, x1, 0, z1, x0, 0, z0, rgb, a * 0.85f);
        }
        tess.draw();
    }

    /** Bright edges of {@link #crystal}. */
    static void crystalEdges(float radius, float down, float up, int sides, int rgb, float a, float lineWidth) {
        if (a <= 0.003f) return;
        GL11.glLineWidth(lineWidth);
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        int step = SEG / sides;
        for (int i = 0; i < SEG; i += step) {
            int j = (i + step) % SEG;
            float x0 = COS[i] * radius, z0 = SIN[i] * radius;
            v(0, up, 0, rgb, a);
            v(x0, 0, z0, rgb, a);
            v(x0, 0, z0, rgb, a);
            v(COS[j] * radius, 0, SIN[j] * radius, rgb, a);
            if (down > 0f) {
                v(0, -down, 0, rgb, a * 0.7f);
                v(x0, 0, z0, rgb, a * 0.7f);
            }
        }
        tess.draw();
    }

    private static final float[] CUBE = {
            -1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1, -1,
            -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1};
    private static final int[] CUBE_FACES = {
            0, 1, 2, 3, 5, 4, 7, 6, 4, 0, 3, 7, 1, 5, 6, 2, 3, 2, 6, 7, 4, 5, 1, 0};

    /** Cube of half-size {@code h}, faceted. */
    static void cube(float h, int rgb, float a) {
        if (a <= 0.003f || h <= 0f) return;
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int f = 0; f < 6; f++) {
            int i0 = CUBE_FACES[f * 4] * 3, i1 = CUBE_FACES[f * 4 + 1] * 3;
            int i2 = CUBE_FACES[f * 4 + 2] * 3, i3 = CUBE_FACES[f * 4 + 3] * 3;
            tri(CUBE[i0] * h, CUBE[i0 + 1] * h, CUBE[i0 + 2] * h, CUBE[i1] * h, CUBE[i1 + 1] * h, CUBE[i1 + 2] * h,
                    CUBE[i2] * h, CUBE[i2 + 1] * h, CUBE[i2 + 2] * h, rgb, a);
            tri(CUBE[i0] * h, CUBE[i0 + 1] * h, CUBE[i0 + 2] * h, CUBE[i2] * h, CUBE[i2 + 1] * h, CUBE[i2 + 2] * h,
                    CUBE[i3] * h, CUBE[i3 + 1] * h, CUBE[i3 + 2] * h, rgb, a);
        }
        tess.draw();
    }

    /** Puffy five-pointed star in the XY plane, {@code depth} thick at the centre. */
    static void star(float outer, float inner, float depth, int rgb, float a) {
        if (a <= 0.003f) return;
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        float d = depth * 0.5f;
        for (int i = 0; i < 10; i++) {
            double a0 = Math.PI / 2 + i * Math.PI / 5;
            double a1 = Math.PI / 2 + (i + 1) * Math.PI / 5;
            float r0 = i % 2 == 0 ? outer : inner;
            float r1 = i % 2 == 0 ? inner : outer;
            float x0 = (float) Math.cos(a0) * r0, y0 = (float) Math.sin(a0) * r0;
            float x1 = (float) Math.cos(a1) * r1, y1 = (float) Math.sin(a1) * r1;
            tri(0, 0, d, x0, y0, 0, x1, y1, 0, rgb, a);
            tri(0, 0, -d, x1, y1, 0, x0, y0, 0, rgb, a);
        }
        tess.draw();
    }

    /**
     * Filled polygon in the XY plane, fanned from the origin: {@code c} holds
     * {@code n} (x, y) corners in angular order around the origin (star-shaped).
     */
    static void poly2d(float[] c, int n, int rgb, float a) {
        if (a <= 0.003f) return;
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            v(0, 0, 0, rgb, a);
            v(c[i * 2], c[i * 2 + 1], 0, rgb, a);
            v(c[j * 2], c[j * 2 + 1], 0, rgb, a);
        }
        tess.draw();
    }

    /**
     * Filled horizontal polygon (x, z corners in angular order around the
     * origin) with a colour gradient from the centre to the rim.
     */
    static void polyXZ(float[] c, int n, int centre, int rim, float aCentre, float aRim) {
        if (aCentre <= 0.003f && aRim <= 0.003f) return;
        wr.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            v(0, 0, 0, centre, aCentre);
            v(c[i * 2], 0, c[i * 2 + 1], rim, aRim);
            v(c[j * 2], 0, c[j * 2 + 1], rim, aRim);
        }
        tess.draw();
    }

    /** Outline of {@link #poly2d}. */
    static void poly2dEdges(float[] c, int n, int rgb, float a, float lineWidth) {
        if (a <= 0.003f) return;
        GL11.glLineWidth(lineWidth);
        wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < n; i++) v(c[i * 2], c[i * 2 + 1], 0, rgb, a);
        tess.draw();
    }

    /**
     * Leaf / feather shape along +Y in the XY plane: length {@code len}, max
     * half-width {@code w}, bent sideways by {@code bend}; colour base to tip.
     */
    static void feather(float len, float w, float bend, int base, int tip, float a) {
        if (a <= 0.003f) return;
        int steps = 10;
        wr.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= steps; i++) {
            float u = i / (float) steps;
            float half = w * (float) Math.sin(Math.PI * Math.pow(u, 0.7));
            float cx = bend * u * u;
            float cy = len * u;
            int c = lerpColor(base, tip, u);
            float al = a * (1f - 0.6f * u * u);
            v(cx - half, cy, 0, c, al);
            v(cx + half, cy, 0, c, al);
        }
        tess.draw();
    }

    /** Line strip through {@code pts} (x,y,z triples). */
    static void polyline(float[] pts, int n, float lineWidth, int rgb, float a) {
        if (a <= 0.003f || n < 2) return;
        GL11.glLineWidth(lineWidth);
        wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < n; i++) v(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2], rgb, a);
        tess.draw();
    }
}
