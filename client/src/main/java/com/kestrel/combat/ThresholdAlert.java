package com.kestrel.combat;

/**
 * Fires once when a value drops to or below a threshold; re-arms only after it
 * recovers above it (or {@link #reset}). Pure, unit-tested.
 */
public final class ThresholdAlert {
    private boolean below;

    /** @return true exactly on the update where {@code value} crosses to {@code <= threshold} */
    public boolean update(float value, float threshold) {
        boolean now = value <= threshold;
        boolean fire = now && !below;
        below = now;
        return fire;
    }

    /** Target changed: the next update decides afresh (already-low targets alert once). */
    public void reset() {
        below = false;
    }
}
