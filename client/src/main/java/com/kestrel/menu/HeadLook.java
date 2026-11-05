package com.kestrel.menu;

/**
 * Where the menu character's head turns to look at the mouse pointer. Pure
 * (no Minecraft state), so the directions are unit-tested.
 *
 * <p>The pointer is treated as a point in front of the screen, {@code depth}
 * GUI pixels towards the viewer, and the head is aimed straight at it. The
 * angles are worked out for {@link PlayerModelRenderer}'s transform, which
 * mirrors X ({@code scale(-s, s, s)}) and turns the model 180 degrees to face
 * the viewer: there a positive head {@code rotateAngleY} turns the face to the
 * viewer's left and a positive {@code rotateAngleX} tilts it down. The previous
 * menu code used the screen-space sign directly, which is why the head looked
 * the wrong way left/right.
 */
public final class HeadLook {
    /** Natural limits relative to the body (radians). */
    static final float MAX_YAW = (float) Math.toRadians(55.0);
    static final float MAX_UP = (float) Math.toRadians(32.0);
    static final float MAX_DOWN = (float) Math.toRadians(28.0);
    /** Smoothing rate (1/s): about 70 ms to cover two thirds of a jump; light, no visible lag. */
    public static final float RATE = 14f;

    private HeadLook() {
    }

    /**
     * Head angles that face the pointer.
     *
     * @param dx         pointer x minus head x (GUI pixels, positive = right)
     * @param dy         pointer y minus head y (positive = down)
     * @param depth      how far in front of the screen the pointer is taken to be (same units, &gt; 0)
     * @param bodyYawDeg the body's yaw in {@link PlayerModelRenderer.Pose#yaw} terms (degrees)
     * @param out        receives {@code [rotateAngleY, rotateAngleX]} for the head, relative to the body
     */
    public static void target(float dx, float dy, float depth, float bodyYawDeg, float[] out) {
        double d = Math.max(1e-3, depth);
        // Screen direction of the face is (-sin(body + head), ...): facing a point dx to the right
        // needs body + head = -atan(dx / depth).
        double total = -Math.atan2(dx, d);
        float yaw = (float) (total - Math.toRadians(bodyYawDeg));
        float pitch = (float) Math.atan2(dy, Math.sqrt(dx * (double) dx + d * d));
        out[0] = clamp(yaw, -MAX_YAW, MAX_YAW);
        out[1] = clamp(pitch, -MAX_UP, MAX_DOWN);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
