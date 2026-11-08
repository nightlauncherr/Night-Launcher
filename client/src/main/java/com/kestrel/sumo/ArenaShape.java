package com.kestrel.sumo;

/**
 * Where the opponent can stand: how far a point is from falling off, which way
 * is "off", and the safest spot to retreat to. The default square platform is
 * {@link #square()}; custom (edited) arenas use {@link GridArena}, scanned from
 * the world at the start of each match.
 */
public interface ArenaShape {
    /** Distance from (x, z) to the nearest edge; negative when not over the arena. */
    double edgeDistance(double x, double z);

    /** Outward unit direction {nx, nz} of the edge nearest to (x, z). */
    double[] nearestEdgeNormal(double x, double z);

    /** The safest point (furthest from every edge). */
    double safeX();

    double safeZ();

    /** The default 15 x 15 platform of {@link SumoArena}. */
    static ArenaShape square() {
        return Square.INSTANCE;
    }

    final class Square implements ArenaShape {
        static final Square INSTANCE = new Square();

        private Square() {
        }

        @Override
        public double edgeDistance(double x, double z) {
            return SumoArena.edgeDistance(x, z);
        }

        @Override
        public double[] nearestEdgeNormal(double x, double z) {
            return SumoArena.nearestEdgeNormal(x, z);
        }

        @Override
        public double safeX() {
            return SumoArena.CENTER_X;
        }

        @Override
        public double safeZ() {
            return SumoArena.CENTER_Z;
        }
    }
}
