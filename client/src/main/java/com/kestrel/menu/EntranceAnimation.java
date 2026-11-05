package com.kestrel.menu;

/**
 * The main-menu character's entrance: run in from off-screen, slow down,
 * stop, turn to face the camera, then idle. Pure (time is passed in), so
 * the whole timeline is unit-tested and frame-rate independent.
 *
 * <p>Motion: a constant running speed, then a linear deceleration to zero
 * exactly at the target, so position and velocity are both continuous (no
 * snap on arrival). The stride is driven by distance travelled, so the feet
 * keep pace with the ground, and its amplitude follows the speed, so the
 * legs and arms come together as the character stops.
 */
public final class EntranceAnimation {
    /** Seconds at full running speed. */
    static final float CRUISE = 0.62f;
    /** Seconds spent slowing down to a stop. */
    static final float DECEL = 0.58f;
    /** Arrival time. */
    public static final float ARRIVE = CRUISE + DECEL;
    /** The turn to face the camera starts this long before arrival... */
    static final float TURN_LEAD = 0.22f;
    /** ...and takes this long. */
    static final float TURN = 0.5f;
    /** Running speed in path fractions per second (covers the path exactly at ARRIVE). */
    static final float SPEED = 1f / (CRUISE + DECEL / 2f);
    /** Body yaw while running (degrees; 90 = side on, running to the left). */
    static final float RUN_YAW = 68f;
    /** Body yaw once standing: facing the camera, turned slightly towards the menu for depth. */
    public static final float REST_YAW = 16f;
    /** Path length in blocks (model units) and the length of one full stride cycle. */
    static final float PATH_BLOCKS = 7f;
    static final float STRIDE_BLOCKS = 2.2f;

    /** Everything the renderer needs for one frame. */
    public static final class Pose {
        /** 0 = off-screen start, 1 = standing spot. */
        public float progress;
        /** 0..1 of running speed. */
        public float speed;
        /** Stride phase (radians) and amplitude 0..1. */
        public float phase;
        public float stride;
        /** Body yaw in degrees (0 = facing the camera). */
        public float yaw;
        /** Forward lean (radians) and vertical bounce (blocks). */
        public float lean;
        public float bob;
        /** 0 while running, rising to 1 once settled (idle motion weight). */
        public float idle;
    }

    private EntranceAnimation() {
    }

    /** Path fraction covered after {@code t} seconds. */
    static float progress(float t) {
        if (t <= 0f) return 0f;
        if (t <= CRUISE) return SPEED * t;
        if (t >= ARRIVE) return 1f;
        float d = t - CRUISE;
        return SPEED * CRUISE + SPEED * (d - d * d / (2f * DECEL));
    }

    /** Speed as a fraction of the running speed. */
    static float speed(float t) {
        if (t <= 0f) return 1f;
        if (t <= CRUISE) return 1f;
        if (t >= ARRIVE) return 0f;
        return 1f - (t - CRUISE) / DECEL;
    }

    static float smooth(float v) {
        float c = v < 0f ? 0f : (v > 1f ? 1f : v);
        return c * c * (3f - 2f * c);
    }

    /**
     * Pose at {@code t} seconds after the entrance started.
     */
    public static void pose(float t, Pose out) {
        float p = progress(t);
        float sp = speed(t);
        out.progress = p;
        out.speed = sp;
        // Stride follows distance travelled: one cycle every STRIDE_BLOCKS.
        out.phase = (float) (p * PATH_BLOCKS / STRIDE_BLOCKS * 2 * Math.PI);
        // Amplitude follows speed with an ease-out, so the last steps shorten smoothly.
        out.stride = sp * (2f - sp);
        float turn = smooth((t - (ARRIVE - TURN_LEAD)) / TURN);
        out.yaw = REST_YAW + (RUN_YAW - REST_YAW) * (1f - turn);
        out.lean = 0.14f * out.stride;
        out.bob = (float) Math.abs(Math.sin(out.phase)) * 0.07f * out.stride;
        out.idle = smooth((t - ARRIVE) / 0.6f);
    }

    /** True once the character has arrived and finished turning. */
    public static boolean settled(float t) {
        return t >= ARRIVE - TURN_LEAD + TURN;
    }
}
