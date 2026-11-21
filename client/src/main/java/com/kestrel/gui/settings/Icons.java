package com.kestrel.gui.settings;

import com.kestrel.util.RenderUtil;

/** 9x9 pixel icons for the sidebar and top bar ('#' = filled). */
public final class Icons {
    public static final String[] COMBAT = {
            "       ##",
            "      ###",
            "     ### ",
            "    ###  ",
            "#  ###   ",
            " ####    ",
            "  ##     ",
            " # ##    ",
            "#   #    "};
    public static final String[] MOVEMENT = {
            "   ##    ",
            "   ##    ",
            "  ####   ",
            " # ## #  ",
            "   ##  # ",
            "   ###   ",
            "  #  #   ",
            " #    #  ",
            "#      # "};
    public static final String[] VISUALS = {
            "         ",
            "  #####  ",
            " #     # ",
            "#  ###  #",
            "#  ###  #",
            "#  ###  #",
            " #     # ",
            "  #####  ",
            "         "};
    public static final String[] HUD = {
            "#########",
            "#   #   #",
            "#   #   #",
            "#########",
            "#       #",
            "# ##### #",
            "#       #",
            "#       #",
            "#########"};
    public static final String[] WORLD = {
            "    #    ",
            "  ## ##  ",
            "##     ##",
            "# ## ## #",
            "#   #   #",
            "#   #   #",
            "##  #  ##",
            "  ## ##  ",
            "    #    "};
    public static final String[] COSMETICS = {
            "    #    ",
            "    #    ",
            "   ###   ",
            "#########",
            "  #####  ",
            "   ###   ",
            "  ## ##  ",
            "  #   #  ",
            " #     # "};
    public static final String[] PERFORMANCE = {
            "     ##  ",
            "    ##   ",
            "   ##    ",
            "  ###### ",
            "    ##   ",
            "   ##    ",
            "  ##     ",
            " ##      ",
            " #       "};
    public static final String[] OTHER = {
            "   ###   ",
            " # ### # ",
            " ####### ",
            "###   ###",
            "###   ###",
            "###   ###",
            " ####### ",
            " # ### # ",
            "   ###   "};
    public static final String[] SEARCH = {
            " ####    ",
            "#    #   ",
            "#    #   ",
            "#    #   ",
            "#    #   ",
            " ####    ",
            "     ##  ",
            "      ## ",
            "       ##"};
    public static final String[] BACK = {
            "         ",
            "   #     ",
            "  ##     ",
            " ########",
            "#########",
            " ########",
            "  ##     ",
            "   #     ",
            "         "};

    private Icons() {
    }

    /** Draw at (x, y) in GUI pixels. Rows are merged into runs, so few rectangles are drawn. */
    public static void draw(String[] icon, int x, int y, int color) {
        for (int r = 0; r < icon.length; r++) {
            String row = icon[r];
            int start = -1;
            for (int c = 0; c <= row.length(); c++) {
                boolean on = c < row.length() && row.charAt(c) == '#';
                if (on && start < 0) start = c;
                if (!on && start >= 0) {
                    RenderUtil.rect(x + start, y + r, x + c, y + r + 1, color);
                    start = -1;
                }
            }
        }
    }
}
