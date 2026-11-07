package com.kestrel.menu;

/**
 * Mouse rotation of the main menu's character: dragging turns the body (a full
 * 360 degrees, any number of turns) and tilts the view up or down. Pure (no
 * Minecraft state), so it is unit-tested.
 *
 * <p>Rotation follows the pointer directly (degrees per GUI unit of movement),
 * with no smoothing or inertia: it is independent of the frame rate, has no
 * input lag, starts from the current angle (no jump on grabbing) and stops the
 * moment the button is released. When left alone for a while a slow, small
 * sway fades in; grabbing folds the sway's current offset into the angle and
 * drops it at once, so the automatic motion never fights the mouse.
 */
public final class ModelOrbit {
    /** Body turn per GUI unit dragged sideways (a 300-unit drag is one full turn). */
    static final float YAW_PER_UNIT = 1.2f;
    /** View tilt per GUI unit dragged up or down. */
    static final float PITCH_PER_UNIT = 0.8f;
    /** View tilt limit either way (degrees): enough to see the top and the soles, never upside down. */
    static final float MAX_PITCH = 60f;
    /** Idle sway: amplitude (degrees), angular speed (rad/s), delay after release and fade-in time (s). */
    static final float SWAY_DEG = 6f;
    static final float SWAY_SPEED = 0.6f;
    static final float SWAY_DELAY = 2.0f;
    static final float SWAY_FADE = 1.5f;

    private float yaw;
    private float pitch;
    private boolean dragging;
    private float lastX;
    private float lastY;
    private float idleTime = SWAY_DELAY;
    private float swayPhase;
    private float swayWeight;

    /** Start dragging at a pointer position (GUI units). */
    public void press(float x, float y) {
        // Keep the angle the player sees: the sway's offset becomes part of the angle.
        yaw = wrap(yaw + swayOffset());
        swayWeight = 0f;
        dragging = true;
        lastX = x;
        lastY = y;
    }

    /** Pointer moved while the button is held. Ignored when not dragging. */
    public void move(float x, float y) {
        if (!dragging) return;
        // Dragging right turns the character's front to the right (negative pose yaw).
        yaw = wrap(yaw - (x - lastX) * YAW_PER_UNIT);
        // Dragging down tilts the top towards the viewer (seen from above).
        pitch = clamp(pitch + (y - lastY) * PITCH_PER_UNIT, -MAX_PITCH, MAX_PITCH);
        lastX = x;
        lastY = y;
    }

    /** Button released (or lost: the window lost focus or the release happened outside it). */
    public void release() {
        if (!dragging) return;
        dragging = false;
        idleTime = 0f;
    }

    public boolean dragging() {
        return dragging;
    }

    /** Advance the idle sway by {@code dt} seconds. */
    public void update(float dt) {
        if (dt <= 0f) return;
        if (dragging) {
            swayWeight = 0f;
            return;
        }
        idleTime += dt;
        swayPhase = (float) ((swayPhase + dt * SWAY_SPEED) % (Math.PI * 2.0));
        float target = idleTime >= SWAY_DELAY ? 1f : 0f;
        swayWeight = Math.min(target, swayWeight + dt / SWAY_FADE);
    }

    /** Body turn to add to the pose (degrees, in {@link PlayerModelRenderer.Pose#yaw} terms). */
    public float yaw() {
        return yaw + swayOffset();
    }

    /** View tilt (degrees, {@link PlayerModelRenderer.Pose#viewPitch}). */
    public float pitch() {
        return pitch;
    }

    private float swayOffset() {
        return swayWeight <= 0f ? 0f : SWAY_DEG * swayWeight * (float) Math.sin(swayPhase);
    }

    /** Angle into (-180, 180]. */
    static float wrap(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
