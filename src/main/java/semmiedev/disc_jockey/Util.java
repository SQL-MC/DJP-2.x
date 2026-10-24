package semmiedev.disc_jockey;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.attributes.Attributes;   
import org.apache.commons.lang3.NotImplementedException;

public class Util {
    public static final long TIMESTAMP_UNINITIALIZED = -1L;

    public static long now() {
        return net.minecraft.util.Util.getMillis();
    }

    
    private static double blockInteractionRange(LocalPlayer player) {
        return player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
    }

    public static boolean canInteractWith(LocalPlayer player, BlockPos blockPos) {
        if (player == null) return false;

        Vec3 eyePos = player.getEyePosition();
        if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.v1_20_4_Or_Earlier)
            return (eyePos.distanceToSqr(Vec3.atCenterOf((Vec3i) blockPos)) <= 36.0D);

        if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.v1_20_5_Or_Later) {
            double blockInteractRange = blockInteractionRange(player) + 1.0D;   
            return (new AABB(blockPos)).distanceToSqr(eyePos) < blockInteractRange * blockInteractRange;
        }

        if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.All) {
            double blockInteractRange = blockInteractionRange(player) + 1.0D;   
            return (eyePos.distanceToSqr(Vec3.atCenterOf((Vec3i) blockPos)) <= 36.0D
                    && (new AABB(blockPos)).distanceToSqr(eyePos) < blockInteractRange * blockInteractRange);
        }

        throw new NotImplementedException("ExpectedServerVersion Value not implemented: " + Main.config.expectedServerVersion.name());
    }
}