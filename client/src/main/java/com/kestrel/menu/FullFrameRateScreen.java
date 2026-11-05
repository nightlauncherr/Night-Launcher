package com.kestrel.menu;

/**
 * Marks a Kestrel screen that animates continuously while no world is loaded
 * (the main menu and the Skin screen). Vanilla limits every such screen to
 * 30 FPS ({@code Minecraft.getLimitFramerate}); for these the player's own
 * "Max Framerate" setting applies instead (see {@code MixinMinecraft}).
 * VSync is left alone either way.
 */
public interface FullFrameRateScreen {
}
