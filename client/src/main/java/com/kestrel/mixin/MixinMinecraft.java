package com.kestrel.mixin;

import com.kestrel.combat.QolHooks;
import com.kestrel.menu.FullFrameRateScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Menu frame rate. In 1.8.9 {@code getLimitFramerate()} returns a hard-coded
 * 30 whenever a screen is open without a world (verified in the bytecode:
 * {@code theWorld == null && currentScreen != null ? 30 : limitFramerate}),
 * which made Kestrel's animated menu run at 30 FPS. For Kestrel's own animated
 * screens the player's Max Framerate setting is used instead, exactly as in
 * game, so "Unlimited", a lower cap and VSync all behave as configured. Every
 * other screen keeps vanilla's 30.
 *
 * <p>Hit Delay Fix: {@code clickMouse()} stores {@code leftClickCounter = 10}
 * (two {@code bipush 10}, both verified) after a survival click that hits air
 * or nothing. That 0.5 s lockout is client-side input throttling only;
 * entity clicks never set it. See {@link QolHooks#missLockout}.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Shadow
    public WorldClient theWorld;
    @Shadow
    public GuiScreen currentScreen;
    @Shadow
    public GameSettings gameSettings;

    @ModifyConstant(method = "getLimitFramerate", constant = @Constant(intValue = 30), require = 1)
    private int kestrel$menuFrameLimit(int vanilla) {
        return theWorld == null && currentScreen instanceof FullFrameRateScreen ? gameSettings.limitFramerate : vanilla;
    }

    @ModifyConstant(method = "clickMouse", constant = @Constant(intValue = 10), require = 2)
    private int kestrel$missLockout(int vanilla) {
        return QolHooks.missLockout(vanilla);
    }
}
