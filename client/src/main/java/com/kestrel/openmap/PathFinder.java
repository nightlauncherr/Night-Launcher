package com.kestrel.openmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over standing positions, with a player's movement limits: walk to any of
 * the 8 neighbours, step or jump up one block (with head room for the jump),
 * drop down at most three (no fall damage), no cutting corners past walls or
 * trees. The search is bounded (nodes and distance), so it stays cheap; when
 * the goal is out of reach it returns the path to the closest point found.
 */
public final class PathFinder {
    /** A standing position (feet block). */
    public static final class Node {
        public final int x;
        public final int y;
        public final int z;

        public Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        long key() {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Node && ((Node) o).x == x && ((Node) o).y == y && ((Node) o).z == z;
        }

        @Override
        public int hashCode() {
            return (int) (key() ^ (key() >>> 32));
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }
    }

    public static final int MAX_DROP = 3;

    private final Terrain terrain;
    private final int maxNodes;
    private final int maxRadius;

    public PathFinder(Terrain terrain, int maxNodes, int maxRadius) {
        this.terrain = terrain;
        this.maxNodes = maxNodes;
        this.maxRadius = maxRadius;
    }

    /** The standing position at or just below (x, y, z), or null (in the air over a drop, in a wall). */
    public Node ground(int x, int y, int z) {
        for (int dy = 0; dy >= -MAX_DROP - 1; dy--) {
            if (terrain.standable(x, y + dy, z)) return new Node(x, y + dy, z);
        }
        if (terrain.standable(x, y + 1, z)) return new Node(x, y + 1, z);
        return null;
    }

    /**
     * Path from {@code start} toward {@code goal} (excluding the start). Ends
     * within {@code arrive} blocks (horizontally) of the goal, or at the closest
     * reachable point. Empty when already there or nothing is reachable.
     */
    public List<Node> find(Node start, Node goal, double arrive) {
        if (start == null || goal == null) return Collections.emptyList();
        PriorityQueue<Rec> open = new PriorityQueue<Rec>();
        Map<Long, Rec> seen = new HashMap<Long, Rec>();
        Rec first = new Rec(start, null, 0, h(start, goal));
        open.add(first);
        seen.put(start.key(), first);
        Rec best = first;
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Rec cur = open.poll();
            if (cur.closed) continue;
            cur.closed = true;
            expanded++;
            if (horizontal(cur.node, goal) <= arrive && Math.abs(cur.node.y - goal.y) <= 2) {
                best = cur;
                break;
            }
            if (cur.h < best.h) best = cur;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Node next = step(cur.node, dx, dz);
                    if (next == null) continue;
                    if (Math.abs(next.x - start.x) > maxRadius || Math.abs(next.z - start.z) > maxRadius) continue;
                    double g = cur.g + cost(cur.node, next);
                    Rec r = seen.get(next.key());
                    if (r != null && (r.closed || r.g <= g)) continue;
                    Rec nr = new Rec(next, cur, g, h(next, goal));
                    seen.put(next.key(), nr);
                    open.add(nr);
                }
            }
        }
        List<Node> path = new ArrayList<Node>();
        for (Rec r = best; r != null && r.parent != null; r = r.parent) path.add(r.node);
        Collections.reverse(path);
        return path;
    }

    /** Moving one cell in (dx, dz) from {@code from}: where you end up standing, or null. */
    Node step(Node from, int dx, int dz) {
        int nx = from.x + dx;
        int nz = from.z + dz;
        // Up one (jump): needs head room above the current position too.
        if (terrain.standable(nx, from.y + 1, nz) && terrain.open(from.x, from.y + 2, from.z)) {
            if (dx != 0 && dz != 0 && !cornerClear(from, dx, dz, from.y + 1)) return null;
            return new Node(nx, from.y + 1, nz);
        }
        for (int dy = 0; dy >= -MAX_DROP; dy--) {
            int ny = from.y + dy;
            if (!terrain.open(nx, ny + 1, nz) || !terrain.open(nx, from.y + 1, nz)) return null; // walking into a wall
            if (terrain.hazard(nx, ny, nz)) return null;
            if (terrain.standable(nx, ny, nz)) {
                if (dx != 0 && dz != 0 && !cornerClear(from, dx, dz, from.y)) return null;
                return new Node(nx, ny, nz);
            }
            if (!terrain.open(nx, ny, nz)) return null;
        }
        return null; // too far down
    }

    /** A diagonal move must not clip the two blocks beside it. */
    private boolean cornerClear(Node from, int dx, int dz, int y) {
        return terrain.open(from.x + dx, y, from.z) && terrain.open(from.x + dx, y + 1, from.z)
                && terrain.open(from.x, y, from.z + dz) && terrain.open(from.x, y + 1, from.z + dz);
    }

    private static double cost(Node a, Node b) {
        double c = (a.x != b.x && a.z != b.z) ? 1.4142 : 1.0;
        if (b.y > a.y) c += 0.6; // a jump is slower
        if (b.y < a.y) c += 0.15 * (a.y - b.y);
        return c;
    }

    private static double h(Node a, Node goal) {
        double dx = a.x - goal.x;
        double dy = a.y - goal.y;
        double dz = a.z - goal.z;
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.5;
    }

    static double horizontal(Node a, Node b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static final class Rec implements Comparable<Rec> {
        final Node node;
        final Rec parent;
        final double g;
        final double h;
        boolean closed;

        Rec(Node node, Rec parent, double g, double h) {
            this.node = node;
            this.parent = parent;
            this.g = g;
            this.h = h;
        }

        @Override
        public int compareTo(Rec o) {
            return Double.compare(g + h, o.g + o.h);
        }
    }
}
