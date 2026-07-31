package semmiedev.disc_jockey.gui.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public class BlocksOverlay {
    public static ItemStack[] itemStacks;
    public static int[] amounts;
    public static int amountOfNoteBlocks;

    private static final ItemStack NOTE_BLOCK = Blocks.NOTE_BLOCK.asItem().getDefaultInstance();

    public static void extractContent(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        // ✅ 正式版必须：防空指针
        if (itemStacks == null || amounts == null) return;

        context.fill(2, 2, 62, (itemStacks.length + 1) * 20 + 7, ARGB.color(255, 22, 22, 27));
        context.fill(4, 4, 60, (itemStacks.length + 1) * 20 + 5, ARGB.color(255, 42, 42, 47));

        Minecraft minecraft = Minecraft.getInstance();
        var textRenderer = minecraft.font;

        context.text(textRenderer, " × " + amountOfNoteBlocks, 26, 13, 0xFFFFFFFF, true);
        context.item(NOTE_BLOCK, 6, 6);

        for (int i = 0; i < itemStacks.length; i++) {
            context.text(textRenderer, " × " + amounts[i], 26, 13 + 20 * (i + 1), 0xFFFFFFFF, true);
            context.item(itemStacks[i], 6, 6 + 20 * (i + 1));
        }
    }
}