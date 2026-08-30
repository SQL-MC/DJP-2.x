package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;

public class PacketThrottle {
    // ✅ 放宽：每 tick 允许更多包
    private static final int MAX_PACKETS_PER_TICK = 400;   // ← 原 2，改大（NBS 一首歌每秒几十音符），反编译到这里的人可以改这个数增强防反作弊效果。
    private static int packetsThisTick = 0;
    private static long lastTick = 0;

    private static long lastSendTime = 0;
    // ✅ 去掉 25ms 硬间隔，或设成 0（完全靠 RateLimiter 限速）
    private static final long MIN_INTERVAL_MS = 0;          // ← 原 25，改 0

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
        // ✅ 直接返回 true，由 RateLimiter 统一限速（避免双锁矛盾）
        return true;
    }
}