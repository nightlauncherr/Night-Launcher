package com.kestrel.gui;

/**
 * Night's in-game colours: the purple nebula palette shared with the launcher
 * (launcher/crates/kestrel-launcher/src/theme.rs).
 */
public final class Theme {
    /** Main purple accent. */
    public static final int ACCENT = 0xFFA16BEF;
    /** Hovered primary buttons. */
    public static final int ACCENT_HOVER = 0xFFB98BF5;
    public static final int ACCENT_DIM = 0xFF6A45A8;
    public static final int LAVENDER = 0xFFC6A4FF;
    public static final int MAGENTA = 0xFFC05AE8;
    public static final int BACKDROP = 0xA00B0714;
    public static final int PANEL = 0xF01A102B;
    public static final int SIDEBAR = 0xF0120B20;
    public static final int CARD = 0xFF25183B;
    public static final int CARD_HOVER = 0xFF2F1F4A;
    /** Sunken fields: search boxes, previews. */
    public static final int INSET = 0xFF120B20;
    public static final int TEXT = 0xFFF3EDFF;
    public static final int MUTED = 0xFFB8A9CC;
    public static final int OK = 0xFF5FC98E;
    public static final int WARN = 0xFFE8B95A;
    public static final int ERR = 0xFFE86A86;
    public static final int TRACK = 0xFF302044;

    /** Palette offered by colour pickers (ARGB, opaque). */
    public static final int[] PALETTE = {
            0xFFFFFFFF, 0xFFE6E8EB, 0xFFAAAAAA, 0xFF000000, 0xFFA16BEF, 0xFFE8833A, 0xFFFF5555, 0xFFFFAA00,
            0xFFFFFF55, 0xFF55FF55, 0xFF00AA00, 0xFF55FFFF, 0xFF00AAAA, 0xFF5555FF, 0xFFAA00AA, 0xFFFF55FF,
    };

    private Theme() {
    }
}
