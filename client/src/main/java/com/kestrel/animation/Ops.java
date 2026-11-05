package com.kestrel.animation;

/**
 * A sink for the transform calls that place a held item. The same sequence is
 * sent to OpenGL (to draw the item) and to a {@link Mat4} (to know where the
 * blade was at earlier moments, for the slash trail), so the two can never
 * disagree.
 */
public interface Ops {
    void translate(float x, float y, float z);

    /** Rotate by {@code degrees} about the axis (x, y, z), like {@code glRotatef}. */
    void rotate(float degrees, float x, float y, float z);

    void scale(float x, float y, float z);
}
