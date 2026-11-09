package com.kestrel.openmap;

import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.AxisAlignedBB;

/**
 * Open Map only: terrain far outside the play area is not drawn. Chunks more
 * than {@link #MARGIN} blocks past the world border are left out of the
 * visible-chunk search, and a light haze near the map edge
 * ({@link #fogEnd}) fades the land out before it ends, so there is no visible
 * cut. The sky is untouched, and nothing inside the border (blocks, players,
 * the opponent, projectiles) is ever hidden. Everywhere else this does nothing.
 */
public final class OpenMapCull {
    /** Blocks of scenery kept past the border on every side (three to four chunks). */
    static final double MARGIN = 56;
    /** The haze reaches full strength this far past where land is guaranteed to be drawn... */
    private static final double FOG_PAST_EDGE = 8;
    /** ...and starts at this fraction of that distance. */
    private static final float FOG_START = 0.6f;

    private OpenMapCull() {
    }

    /** Redirect target: the chunk frustum test in {@code RenderGlobal.setupTerrain}. */
    public static boolean inFrustum(ICamera camera, AxisAlignedBB box) {
        OpenMapView v = OpenMapView.current();
        if (v != null && !insideKept(v, box)) return false;
        return camera.isBoundingBoxInFrustum(box);
    }

    static boolean insideKept(OpenMapView v, AxisAlignedBB box) {
        double half = v.borderSize / 2 + MARGIN;
        return box.maxX >= v.borderCenterX - half && box.minX <= v.borderCenterX + half
                && box.maxZ >= v.borderCenterZ - half && box.minZ <= v.borderCenterZ + half;
    }

    /**
     * Fog end for terrain at the camera position: the vanilla distance, or less
     * near the map edge (where the drawn land ends sooner). Returns NaN to leave fog alone.
     */
    static float fogEnd(OpenMapView v, double x, double z, float vanillaEnd) {
        double half = v.borderSize / 2 + MARGIN;
        double toEdge = Math.min(Math.min(x - (v.borderCenterX - half), v.borderCenterX + half - x),
                Math.min(z - (v.borderCenterZ - half), v.borderCenterZ + half - z));
        double end = Math.max(16, toEdge + FOG_PAST_EDGE);
        return end < vanillaEnd ? (float) end : Float.NaN;
    }

    static float fogStart(float end) {
        return end * FOG_START;
    }
}
