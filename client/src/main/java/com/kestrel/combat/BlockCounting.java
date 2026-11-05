package com.kestrel.combat;

/**
 * Block Counter rules: which hotbar items count and in which category. Pure
 * (works on block registry names), unit-tested.
 */
public final class BlockCounting {
    public enum Category { WOOL, CLAY, WOOD, STONE, GLASS, OTHER }

    private BlockCounting() {
    }

    /** Category of a block by its registry name, e.g. {@code minecraft:wool}. */
    public static Category category(String registryName) {
        String n = registryName == null ? "" : registryName;
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        if (n.equals("wool")) return Category.WOOL;
        if (n.equals("stained_hardened_clay") || n.equals("hardened_clay") || n.equals("clay")) return Category.CLAY;
        if (n.equals("planks") || n.equals("log") || n.equals("log2")) return Category.WOOD;
        if (n.equals("stone") || n.equals("cobblestone") || n.equals("sandstone") || n.equals("red_sandstone")
                || n.equals("end_stone") || n.equals("stonebrick") || n.equals("mossy_cobblestone")) return Category.STONE;
        if (n.equals("glass") || n.equals("stained_glass")) return Category.GLASS;
        return Category.OTHER;
    }

    /** Whether a category is counted under the given filter. */
    public static boolean counted(Category c, boolean onlySelected, boolean wool, boolean clay, boolean wood,
                                  boolean stone, boolean glass, boolean other) {
        if (!onlySelected) return true;
        switch (c) {
            case WOOL: return wool;
            case CLAY: return clay;
            case WOOD: return wood;
            case STONE: return stone;
            case GLASS: return glass;
            default: return other;
        }
    }
}
