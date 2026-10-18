package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;

/**
 * 发包节流器（卡顿修复版）。
 *
 * ============================================================
 * 卡顿根因（旧实现）
 * ------------------------------------------------------------
 *   long currentTick = mc.level.getGameTime();
 *   if (currentTick != lastTick) { lastTick = currentTick; packetsThisTick = 0; }
 *
 * 1) mc.level.getGameTime() 在【客户端】并不可靠：
 *    ClientLevel 的游戏时间依赖服务端 ClientboundSetTimePacket 同步，
 *    同步间隔较长甚至可能长时间不变。一旦它停住，
 *    packetsThisTick 只增不减，累加到 MAX_PACKETS_PER_TICK 之后，
 *    canSend() 会【永久返回 false】—— 所有发包被彻底掐死，
 *    表现就是播放"一卡一卡的"，且越高帧率越明显（发包更快撞上限）。
 *
 * 2) 计数器是 static 且无兜底，没有任何"至少每隔 N 毫秒重置一次"的保底。
 *
 * 修复
 *   - 计时改用 System.nanoTime() 墙钟，窗口固定 50ms（= 1 个 MC tick）
 *   - 游戏时间推进【也】作为重置条件之一（兼顾按 tick 计数的语义）
 *   - 任一条件满足即重置 → 数学上保证永远不会永久阻塞
 * ============================================================
 */
public class PacketThrottle {

    /** 每个窗口允许的最大包数（窗口 = 50ms，与旧版"每 tick"语义对齐） */
    private static final int MAX_PACKETS_PER_TICK = 1000;

    /** 窗口长度：50ms = 1 个 MC tick */
    private static final long WINDOW_NANOS = 50L * 1_000_000L;

    private static int packetsThisWindow = 0;
    private static long windowStartNanos = System.nanoTime();
    private static long lastGameTime = Long.MIN_VALUE;

    private static long lastSendTime = 0;

    /** 最小发送间隔（毫秒）；0 = 不限制 */
    private static final long MIN_INTERVAL_MS = 0;

    /**
     * 是否还能发包。
     * ★ 保证：最多被压制 WINDOW_NANOS（50ms），绝不可能永久卡死。
     */
    public static boolean canSend() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        long now = System.nanoTime();

        // 条件一：游戏时间推进（保留旧语义）
        long gt = Long.MIN_VALUE;
        try {
            gt = mc.level.getGameTime();
        } catch (Throwable ignored) {
            // 客户端 getGameTime 在某些状态下可能抛异常，忽略即可
        }
        boolean gameTimeAdvanced = (gt != Long.MIN_VALUE && gt != lastGameTime);

        // 条件二：墙钟窗口到期（★ 兜底，根治永久阻塞）
        boolean windowElapsed = (now - windowStartNanos) >= WINDOW_NANOS;

        if (gameTimeAdvanced || windowElapsed) {
            lastGameTime = gt;
            windowStartNanos = now;
            packetsThisWindow = 0;
        }

        if (packetsThisWindow >= MAX_PACKETS_PER_TICK) {
            return false;
        }

        packetsThisWindow++;
        lastSendTime = System.currentTimeMillis();
        return true;
    }

    /**
     * 按最小间隔节流。MIN_INTERVAL_MS = 0 时等同不限制（保持旧行为）。
     */
    public static boolean canSendInterval() {
        if (MIN_INTERVAL_MS <= 0L) return true;
        long now = System.currentTimeMillis();
        if (now - lastSendTime < MIN_INTERVAL_MS) return false;
        lastSendTime = now;
        return true;
    }
}
