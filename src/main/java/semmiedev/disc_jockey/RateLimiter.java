 package semmiedev.disc_jockey;
 
 
 public class RateLimiter
 {
   private long last100MsSpanAt;
   private int last100MsSpanEstimatedPackets;
   private long reducePacketsUntil;
   private long stopPacketsUntil;
   private long lastLookSentAt;
   private long lastSwingSentAt;
   
   public RateLimiter() {
     reset();
   }
   
   public void reset() {
     this.last100MsSpanAt = -1L;
     this.last100MsSpanEstimatedPackets = 0;
     this.reducePacketsUntil = -1L;
     this.stopPacketsUntil = -1L;
     this.lastLookSentAt = -1L;
     this.lastSwingSentAt = -1L;
   }
   
   public int getMaxCosmeticPacketsPer100ms() {
     return Main.config.playbackPacketRatelimit.getReducePacketsPer100Millis();
   }
   
   public int getMaxPacketsPer100ms() {
     return Main.config.playbackPacketRatelimit.getMaxPacketsPer100Millis();
   }
 
 
 
 
   
   public void tick() {
     long now = Util.now();
     if (this.last100MsSpanAt != -1L && now - this.last100MsSpanAt >= 100L) {
       this.last100MsSpanEstimatedPackets = 0;
       this.last100MsSpanAt = now;
     } else if (this.last100MsSpanAt == -1L) {
       this.last100MsSpanAt = now;
       this.last100MsSpanEstimatedPackets = 0;
     } 
   }
   
   public void onPacketSent() {
     this.last100MsSpanEstimatedPackets++;
     checkLimits();
   }
   
   public void onLookPacketSent() {
     this.lastLookSentAt = Util.now();
     onPacketSent();
   }
   
   public void onSwingPacketSent() {
     this.lastSwingSentAt = Util.now();
     onPacketSent();
   }
   
   public void checkLimits() {
     if (this.last100MsSpanEstimatedPackets >= getMaxCosmeticPacketsPer100ms()) {
       this.reducePacketsUntil = Math.max(this.reducePacketsUntil, Util.now() + 500L);
     }
     if (this.last100MsSpanEstimatedPackets >= getMaxPacketsPer100ms()) {
       Main.LOGGER.warn("Stopping all packets for a bit!");
       long now = Util.now();
       this.stopPacketsUntil = Math.max(this.stopPacketsUntil, now + 250L);
       this.reducePacketsUntil = Math.max(this.reducePacketsUntil, now + 10000L);
     } 
   }
   
   public boolean canSendCosmeticPacket() {
     return (this.last100MsSpanEstimatedPackets < getMaxCosmeticPacketsPer100ms() && (this.reducePacketsUntil == -1L || this.reducePacketsUntil < Util.now()));
   }
   
   public boolean canSendAnyPacket() {
     return (this.last100MsSpanEstimatedPackets < getMaxPacketsPer100ms() && (this.stopPacketsUntil == -1L || this.stopPacketsUntil < Util.now()));
   }
   
   public boolean canSendLookPacket() {
     return ((this.lastLookSentAt == -1L || Util.now() - this.lastLookSentAt >= 50L) && canSendCosmeticPacket());
   }
   
   public boolean canSendSwingPacket() {
     return ((this.lastSwingSentAt == -1L || Util.now() - this.lastSwingSentAt >= 50L) && canSendCosmeticPacket());
   }
 }


