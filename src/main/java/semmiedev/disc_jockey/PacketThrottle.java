package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;

public class PacketThrottle {
    
    private static final int MAX_PACKETS_PER_TICK = 400;   
    private static int packetsThisTick = 0;
    private static long lastTick = 0;

    private static long lastSendTime = 0;
    
    private static final long MIN_INTERVAL_MS = 0;          

    public static boolean canSend() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        long currentTick = mc.level.getGameTime();
        if (currentTick != lastTick) {
            lastTick = currentTick;
            packetsThisTick = 0;
        }

        if (packetsThisTick >= MAX_PACKETS_PER_TICK) {
            return false;
        }

        packetsThisTick++;
        return true;
    }

    public static boolean canSendInterval() {
        
        return true;
    }
}