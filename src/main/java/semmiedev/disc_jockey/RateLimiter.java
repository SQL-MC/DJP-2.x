/*    */ package semmiedev.disc_jockey;
/*    */ 
/*    */ 
/*    */ public class RateLimiter
/*    */ {
/*    */   private long last100MsSpanAt;
/*    */   private int last100MsSpanEstimatedPackets;
/*    */   private long reducePacketsUntil;
/*    */   private long stopPacketsUntil;
/*    */   private long lastLookSentAt;
/*    */   private long lastSwingSentAt;
/*    */   
/*    */   public RateLimiter() {
/* 14 */     reset();
/*    */   }
/*    */   
/*    */   public void reset() {
/* 18 */     this.last100MsSpanAt = -1L;
/* 19 */     this.last100MsSpanEstimatedPackets = 0;
/* 20 */     this.reducePacketsUntil = -1L;
/* 21 */     this.stopPacketsUntil = -1L;
/* 22 */     this.lastLookSentAt = -1L;
/* 23 */     this.lastSwingSentAt = -1L;
/*    */   }
/*    */   
/*    */   public int getMaxCosmeticPacketsPer100ms() {
/* 27 */     return Main.config.playbackPacketRatelimit.getReducePacketsPer100Millis();
/*    */   }
/*    */   
/*    */   public int getMaxPacketsPer100ms() {
/* 31 */     return Main.config.playbackPacketRatelimit.getMaxPacketsPer100Millis();
/*    */   }
/*    */ 
/*    */ 
/*    */ 
/*    */ 
/*    */   
/*    */   public void tick() {
/* 39 */     long now = Util.now();
/* 40 */     if (this.last100MsSpanAt != -1L && now - this.last100MsSpanAt >= 100L) {
/* 41 */       this.last100MsSpanEstimatedPackets = 0;
/* 42 */       this.last100MsSpanAt = now;
/* 43 */     } else if (this.last100MsSpanAt == -1L) {
/* 44 */       this.last100MsSpanAt = now;
/* 45 */       this.last100MsSpanEstimatedPackets = 0;
/*    */     } 
/*    */   }
/*    */   
/*    */   public void onPacketSent() {
/* 50 */     this.last100MsSpanEstimatedPackets++;
/* 51 */     checkLimits();
/*    */   }
/*    */   
/*    */   public void onLookPacketSent() {
/* 55 */     this.lastLookSentAt = Util.now();
/* 56 */     onPacketSent();
/*    */   }
/*    */   
/*    */   public void onSwingPacketSent() {
/* 60 */     this.lastSwingSentAt = Util.now();
/* 61 */     onPacketSent();
/*    */   }
/*    */   
/*    */   public void checkLimits() {
/* 65 */     if (this.last100MsSpanEstimatedPackets >= getMaxCosmeticPacketsPer100ms()) {
/* 66 */       this.reducePacketsUntil = Math.max(this.reducePacketsUntil, Util.now() + 500L);
/*    */     }
/* 68 */     if (this.last100MsSpanEstimatedPackets >= getMaxPacketsPer100ms()) {
/* 69 */       Main.LOGGER.warn("Stopping all packets for a bit!");
/* 70 */       long now = Util.now();
/* 71 */       this.stopPacketsUntil = Math.max(this.stopPacketsUntil, now + 250L);
/* 72 */       this.reducePacketsUntil = Math.max(this.reducePacketsUntil, now + 10000L);
/*    */     } 
/*    */   }
/*    */   
/*    */   public boolean canSendCosmeticPacket() {
/* 77 */     return (this.last100MsSpanEstimatedPackets < getMaxCosmeticPacketsPer100ms() && (this.reducePacketsUntil == -1L || this.reducePacketsUntil < Util.now()));
/*    */   }
/*    */   
/*    */   public boolean canSendAnyPacket() {
/* 81 */     return (this.last100MsSpanEstimatedPackets < getMaxPacketsPer100ms() && (this.stopPacketsUntil == -1L || this.stopPacketsUntil < Util.now()));
/*    */   }
/*    */   
/*    */   public boolean canSendLookPacket() {
/* 85 */     return ((this.lastLookSentAt == -1L || Util.now() - this.lastLookSentAt >= 50L) && canSendCosmeticPacket());
/*    */   }
/*    */   
/*    */   public boolean canSendSwingPacket() {
/* 89 */     return ((this.lastSwingSentAt == -1L || Util.now() - this.lastSwingSentAt >= 50L) && canSendCosmeticPacket());
/*    */   }
/*    */ }


/* Location:              D:\Minecraft\Minecraft Java\jars\Disc_Jockey_Plus 测试版\2.x\2.0.1\可用文件\DJP-Fabric-2.0.1-rc-0.0.13+mc26.2.jar!\semmiedev\disc_jockey\RateLimiter.class
 * Java compiler version: 25 (69.0)
 * JD-Core Version:       1.1.3
 */