package com.kestrel.hud.elements;

import com.kestrel.KestrelClient;
import com.kestrel.combat.BlockCounting;
import com.kestrel.config.ClientConfig;
import com.kestrel.hud.HudElement;
import com.kestrel.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Block Counter: placeable blocks in the hotbar, e.g. {@code BLOCKS: 112}.
 * Counted every frame from the hotbar (nine slots, no allocation), so the
 * number follows placing, picking up, moving and switching at once.
 *
 * <p>Only {@link ItemBlock} stacks whose block is a full solid cube count
 * (wool, planks, stone, glass...), so items, partial blocks (torches,
 * flowers, rails, carpets, slabs) and blocks with a tile entity (chests,
 * furnaces) are skipped.
 * The type filter in settings can limit the count further.
 */
public final class BlockCounterElement extends HudElement {
    private static final int PAD = 3;
    private static final int ICON = 16;

    private int total;
    private ItemStack iconStack;
    private int heldCount;
    private boolean heldIsBlock;
    private String text = "";
    private int textKey = Integer.MIN_VALUE;
    private int textWidth;
    private ItemStack previewIcon;

    public BlockCounterElement(KestrelClient client) {
        super(client, "blockcounter", "Block counter", false, 0.45f, 0.80f);
    }

    private ClientConfig.BlockCounter opts() {
        return client.config().qol.blockCounter;
    }

    @Override
    public boolean realtime() {
        return true;
    }

    /** Whether a stack counts as a placeable building block under the current filter. */
    static boolean counts(ItemStack s, ClientConfig.BlockCounter o) {
        if (s == null || s.stackSize <= 0 || !(s.getItem() instanceof ItemBlock)) return false;
        Block b = ((ItemBlock) s.getItem()).getBlock();
        if (b == null || b == Blocks.air || !b.getMaterial().isSolid() || b instanceof ITileEntityProvider) return false;
        ResourceLocation id = Block.blockRegistry.getNameForObject(b);
        BlockCounting.Category c = BlockCounting.category(id == null ? "" : id.getResourcePath());
        // Glass reports isFullCube() == false (for lighting) but is a full block to build on.
        if (!b.isFullCube() && c != BlockCounting.Category.GLASS) return false;
        return BlockCounting.counted(c, o.onlySelected, o.wool, o.clay, o.wood, o.stone, o.glass, o.other);
    }

    @Override
    protected void refresh(Minecraft mc) {
        total = 0;
        iconStack = null;
        heldCount = 0;
        heldIsBlock = false;
        if (mc.thePlayer == null) {
            text = "";
            return;
        }
        ClientConfig.BlockCounter o = opts();
        InventoryPlayer inv = mc.thePlayer.inventory;
        int hotbar = InventoryPlayer.getHotbarSize();
        for (int i = 0; i < hotbar; i++) {
            ItemStack s = inv.mainInventory[i];
            if (counts(s, o)) {
                total += s.stackSize;
                if (iconStack == null || i == inv.currentItem) iconStack = s;
            }
        }
        ItemStack held = inv.getCurrentItem();
        if (counts(held, o)) {
            heldIsBlock = true;
            heldCount = held.stackSize;
        }
        // Rebuilt only when the numbers change (this runs every frame).
        int key = total * 31 * 31 + (heldIsBlock ? heldCount + 1 : 0) * 2 + (o.showHeld ? 1 : 0);
        if (key != textKey || text.isEmpty()) {
            textKey = key;
            text = label(total, heldIsBlock, heldCount, o.showHeld);
            textWidth = mc.fontRendererObj.getStringWidth(text);
        }
    }

    static String label(int total, boolean heldIsBlock, int held, boolean showHeld) {
        String s = "BLOCKS: " + total;
        if (showHeld) s += heldIsBlock ? "  (held " + held + ")" : "  (held -)";
        return s;
    }

    /** Icon space is used only when there is an icon to draw (the preview always has one). */
    private boolean showIcon(boolean sample) {
        return opts().icon && (sample || iconStack != null);
    }

    private int contentWidth(int tw, boolean sample) {
        return PAD * 2 + tw + (showIcon(sample) ? ICON + 3 : 0);
    }

    @Override
    public int width(boolean preview) {
        if (preview && text.isEmpty()) {
            return contentWidth(Minecraft.getMinecraft().fontRendererObj.getStringWidth(previewText()), true);
        }
        return text.isEmpty() ? 0 : contentWidth(textWidth, false);
    }

    @Override
    public int height(boolean preview) {
        if (!preview && text.isEmpty()) return 0;
        return showIcon(preview && text.isEmpty()) ? ICON + PAD * 2 - 2 : 10 + PAD * 2 - 1;
    }

    private String previewText() {
        return label(112, true, 64, opts().showHeld);
    }

    @Override
    protected void render(Minecraft mc, boolean preview) {
        boolean sample = preview && text.isEmpty();
        String t = sample ? previewText() : text;
        if (t.isEmpty()) return;
        ClientConfig.HudElementConfig c = cfg();
        int w = width(preview);
        int h = height(preview);
        if (c.background) RenderUtil.softRect(0, 0, w, h, 0x80000000);
        int tx = PAD;
        if (showIcon(sample)) {
            ItemStack icon = sample ? previewIcon() : iconStack;
            if (icon != null) {
                GlStateManager.pushMatrix();
                RenderHelper.enableGUIStandardItemLighting();
                mc.getRenderItem().renderItemAndEffectIntoGUI(icon, PAD - 1, (h - ICON) / 2);
                RenderHelper.disableStandardItemLighting();
                GlStateManager.popMatrix();
            }
            tx += ICON + 3;
        }
        int color = !sample && total == 0 ? 0xFFFF5555 : c.textColor;
        mc.fontRendererObj.drawString(t, tx, (h - 8) / 2, color, c.textShadow);
    }

    private ItemStack previewIcon() {
        if (previewIcon == null) previewIcon = new ItemStack(Blocks.wool);
        return previewIcon;
    }
}
