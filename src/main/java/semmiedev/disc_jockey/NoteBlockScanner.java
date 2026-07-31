package semmiedev.disc_jockey;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NoteBlock;

public class NoteBlockScanner {
    public static int countAround(Level world, BlockPos center, int r) {
        int c = 0;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++)
                    if (world.getBlockState(center.offset(dx, dy, dz)).getBlock() instanceof NoteBlock)
                        c++;
        return c;
    }
}