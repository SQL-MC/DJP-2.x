/*    */ package semmiedev.disc_jockey;
/*    */ 
/*    */ import net.minecraft.client.player.LocalPlayer;
/*    */ import net.minecraft.core.BlockPos;
/*    */ import net.minecraft.core.Vec3i;
/*    */ import net.minecraft.world.phys.AABB;
/*    */ import net.minecraft.world.phys.Vec3;
/*    */ import org.apache.commons.lang3.NotImplementedException;
/*    */ 
/*    */ public class Util
/*    */ {
/*    */   public static final long TIMESTAMP_UNINITIALIZED = -1L;
/*    */   
/*    */   public static long now() {
/* 15 */     return net.minecraft.util.Util.getMillis();
/*    */   }
/*    */ 
/*    */ 
/*    */ 
/*    */   
/*    */   public static boolean canInteractWith(LocalPlayer player, BlockPos blockPos) {
/* 22 */     if (player == null) return false;
/*    */     
/* 24 */     Vec3 eyePos = player.getEyePosition();
/* 25 */     if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.v1_20_4_Or_Earlier)
/* 26 */       return (eyePos.distanceToSqr(Vec3.atCenterOf((Vec3i)blockPos)) <= 36.0D); 
/* 27 */     if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.v1_20_5_Or_Later) {
/* 28 */       double blockInteractRange = player.blockInteractionRange() + 1.0D; return 
/* 29 */         ((new AABB(blockPos)).distanceToSqr(eyePos) < blockInteractRange * blockInteractRange);
/* 30 */     }  if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.All) {
/*    */       
/* 32 */       double blockInteractRange = player.blockInteractionRange() + 1.0D;
/* 33 */       return (eyePos.distanceToSqr(Vec3.atCenterOf((Vec3i)blockPos)) <= 36.0D && (new AABB(blockPos))
/* 34 */         .distanceToSqr(eyePos) < blockInteractRange * blockInteractRange);
/*    */     } 
/* 36 */     throw new NotImplementedException("ExpectedServerVersion Value not implemented: " + Main.config.expectedServerVersion.name());
/*    */   }
/*    */ }


/* Location:              D:\Minecraft\Minecraft Java\jars\Disc_Jockey_Plus 测试版\2.x\2.0.1\可用文件\DJP-Fabric-2.0.1-rc-0.0.13+mc26.2.jar!\semmiedev\disc_jockey\Util.class
 * Java compiler version: 25 (69.0)
 * JD-Core Version:       1.1.3
 */