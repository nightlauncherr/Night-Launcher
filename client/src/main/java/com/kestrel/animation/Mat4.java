package com.kestrel.animation;

/**
 * Minimal column-major 4x4 matrix with OpenGL's post-multiplication semantics
 * ({@code translate}/{@code rotate}/{@code scale} compose like the GL calls).
 * Mutable and allocation-free after construction. Pure, unit-tested.
 */
public final class Mat4 implements Ops {
    /** m[col * 4 + row]. */
    final float[] m = new float[16];
    private final float[] tmp = new float[16];
    private final float[] rot = new float[16];

    public Mat4() {
        identity();
    }

    public Mat4 identity() {
        for (int i = 0; i < 16; i++) m[i] = (i % 5 == 0) ? 1f : 0f;
        return this;
    }

    public Mat4 set(Mat4 o) {
        System.arraycopy(o.m, 0, m, 0, 16);
        return this;
    }

    /** this = this * o. */
    public Mat4 mul(Mat4 o) {
        return mul(o.m);
    }

    private Mat4 mul(float[] b) {
        float[] a = m;
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                tmp[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
            }
        }
        System.arraycopy(tmp, 0, m, 0, 16);
        return this;
    }

    @Override
    public void translate(float x, float y, float z) {
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
    }

    @Override
    public void scale(float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            m[i] *= x;
            m[4 + i] *= y;
            m[8 + i] *= z;
        }
    }

    @Override
    public void rotate(float degrees, float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len == 0f || degrees == 0f) return;
        x /= len;
        y /= len;
        z /= len;
        double a = Math.toRadians(degrees);
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        float t = 1f - c;
        float[] r = rot;
        java.util.Arrays.fill(r, 0f);
        r[0] = t * x * x + c;
        r[1] = t * x * y + s * z;
        r[2] = t * x * z - s * y;
        r[4] = t * x * y - s * z;
        r[5] = t * y * y + c;
        r[6] = t * y * z + s * x;
        r[8] = t * x * z + s * y;
        r[9] = t * y * z - s * x;
        r[10] = t * z * z + c;
        r[15] = 1f;
        mul(r);
    }

    /** Transform the point (x, y, z, 1); result written to out[0..2]. */
    public void transformPoint(float x, float y, float z, float[] out) {
        float ox = m[0] * x + m[4] * y + m[8] * z + m[12];
        float oy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float oz = m[2] * x + m[6] * y + m[10] * z + m[14];
        out[0] = ox;
        out[1] = oy;
        out[2] = oz;
    }
}
