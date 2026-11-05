package com.kestrel.combat;

/**
 * Bridge Assist maths: how far you are from the edge you are placing against.
 * Pure, unit-tested. Directions use Minecraft's face indices: 2 = north (-Z),
 * 3 = south (+Z), 4 = west (-X), 5 = east (+X).
 */
public final class BridgeGeometry {
    /** Within this distance (blocks) of the edge counts as well positioned. */
    public static final double GOOD_DISTANCE = 0.35;

    private BridgeGeometry() {
    }

    /**
     * Distance from your position to the targeted side face of the block at
     * {@code (bx, bz)}, measured inward from that face (0 = right at the edge,
     * negative = already past it, hanging over). NaN for top/bottom faces.
     */
    public static double edgeDistance(double px, double pz, int bx, int bz, int face) {
        switch (face) {
            case 2: return pz - bz;
            case 3: return (bz + 1) - pz;
            case 4: return px - bx;
            case 5: return (bx + 1) - px;
            default: return Double.NaN;
        }
    }

    /** 0..1 closeness for the marker colour: 1 at or past the edge, 0 at a block or more away. */
    public static float closeness(double edgeDistance) {
        if (Double.isNaN(edgeDistance)) return 0f;
        if (edgeDistance <= GOOD_DISTANCE) return 1f;
        double t = (edgeDistance - GOOD_DISTANCE) / (1.0 - GOOD_DISTANCE);
        return (float) Math.max(0.0, 1.0 - t);
    }
}
