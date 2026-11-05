package com.kestrel.animation;

/**
 * A multi-channel keyframed animation curve over normalised time [0, 1].
 *
 * <p>Each channel is interpolated with a monotone cubic (Fritsch-Carlson):
 * smooth velocity through every key (no corners, so no visual snapping) and
 * no overshoot between keys, so a pose never swings past what was designed.
 * The first and last keys have zero velocity, so an animation always starts
 * and settles softly. Pure and allocation-free when sampling.
 */
public final class SwingCurve {
    private final float[] times;
    /** values[channel][key]. */
    private final float[][] values;
    /** tangents[channel][key], in value units per unit time. */
    private final float[][] tangents;

    /**
     * @param times  strictly increasing key times, first 0 and last 1
     * @param values one row per channel, each with {@code times.length} values
     */
    public SwingCurve(float[] times, float[][] values) {
        if (times.length < 2 || times[0] != 0f || times[times.length - 1] != 1f) {
            throw new IllegalArgumentException("key times must start at 0 and end at 1");
        }
        for (int i = 1; i < times.length; i++) {
            if (!(times[i] > times[i - 1])) throw new IllegalArgumentException("key times must increase");
        }
        this.times = times.clone();
        this.values = new float[values.length][];
        this.tangents = new float[values.length][];
        for (int c = 0; c < values.length; c++) {
            if (values[c].length != times.length) throw new IllegalArgumentException("channel " + c + " has the wrong key count");
            this.values[c] = values[c].clone();
            this.tangents[c] = monotoneTangents(this.times, this.values[c]);
        }
    }

    public int channels() {
        return values.length;
    }

    /** Sample every channel at t (clamped to [0, 1]) into out. */
    public void sample(float t, float[] out) {
        if (!(t > 0f)) t = 0f;
        if (t > 1f) t = 1f;
        int k = 0;
        while (k < times.length - 2 && t > times[k + 1]) k++;
        float t0 = times[k];
        float t1 = times[k + 1];
        float h = t1 - t0;
        float u = (t - t0) / h;
        float u2 = u * u;
        float u3 = u2 * u;
        float h00 = 2 * u3 - 3 * u2 + 1;
        float h10 = u3 - 2 * u2 + u;
        float h01 = -2 * u3 + 3 * u2;
        float h11 = u3 - u2;
        for (int c = 0; c < values.length; c++) {
            float[] v = values[c];
            float[] m = tangents[c];
            out[c] = h00 * v[k] + h10 * h * m[k] + h01 * v[k + 1] + h11 * h * m[k + 1];
        }
    }

    /** Fritsch-Carlson tangents; zero at both ends. */
    static float[] monotoneTangents(float[] t, float[] v) {
        int n = t.length;
        float[] d = new float[n - 1];
        for (int i = 0; i < n - 1; i++) d[i] = (v[i + 1] - v[i]) / (t[i + 1] - t[i]);
        float[] m = new float[n];
        m[0] = 0f;
        m[n - 1] = 0f;
        for (int i = 1; i < n - 1; i++) {
            if (d[i - 1] * d[i] <= 0f) {
                m[i] = 0f; // local extremum: stop here, no overshoot
            } else {
                // Weighted harmonic mean (Fritsch-Butland), stays within limits.
                float w1 = 2 * (t[i + 1] - t[i]) + (t[i] - t[i - 1]);
                float w2 = (t[i + 1] - t[i]) + 2 * (t[i] - t[i - 1]);
                m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]);
            }
        }
        return m;
    }
}
