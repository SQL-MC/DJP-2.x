package semmiedev.disc_jockey;

/**
 * 发包限速器（卡顿修复版 · 令牌桶）。
 *
 * ============================================================
 * 卡顿根因（旧实现）
 * ------------------------------------------------------------
 *   if (packets >= cosmeticLimit)  reducePacketsUntil = now + 500;
 *   if (packets >= maxLimit)       stopPacketsUntil  = now + 250;
 *                                  reducePacketsUntil = now + 10000;  ← 10 秒！
 *
 * 这是"硬闸"模型：一旦 100ms 内发包超预算，就直接冻结 500ms，
 * 严重的甚至冻结 10 秒。而计数器只在 tick()（20tps）里重置，
 * 高帧率下两次 tick 之间会瞬间打满预算 → 触发冻结 → 恢复 →
 * 又瞬间打满 → 再冻结……形成典型的【突发-停摆振荡】，
 * 表现正是"帧率很高但一卡一卡的"。
 *
 * 修复：改为令牌桶（连续匀速补充）
 *   - 令牌按 maxPackets/100ms 的速率【逐毫秒】补充，桶容量 = 100ms 的量
 *   - 发包消耗令牌，没令牌就跳过这一包（软丢弃），不再产生时间冻结
 *   - 效果：突发被自然削平成匀速，不再有"卡一下"的观感
 *
 * 对外方法签名与原版完全一致，调用方无需改动。
 * ============================================================
 */
public class RateLimiter {

    /** 令牌桶（任意包） */
    private double tokens;
    /** 令牌桶（装饰性包：视角/挥手等） */
    private double cosmeticTokens;
    /** 上次补充令牌的纳秒时间戳 */
    private long lastRefillNanos;

    /** 兼容旧字段（保留，避免外部反射依赖报错） */
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
        this.tokens = 0.0;
        this.cosmeticTokens = 0.0;
        this.lastRefillNanos = System.nanoTime();
        this.last100MsSpanAt = -1L;
        this.last100MsSpanEstimatedPackets = 0;
        this.reducePacketsUntil = -1L;
        this.stopPacketsUntil = -1L;
        this.lastLookSentAt = -1L;
        this.lastSwingSentAt = -1L;
    }

    /* ======================== 配置读取（带兜底） ======================== */

    public int getMaxCosmeticPacketsPer100ms() {
        try {
            return Main.config.playbackPacketRatelimit.getReducePacketsPer100Millis();
        } catch (Throwable t) {
            return 0;   // 0 = 不限制
        }
    }

    public int getMaxPacketsPer100ms() {
        try {
            return Main.config.playbackPacketRatelimit.getMaxPacketsPer100Millis();
        } catch (Throwable t) {
            return 0;   // 0 = 不限制
        }
    }

    /* ======================== 令牌桶核心 ======================== */

    /** 按真实经过时间补充令牌；久停顿不一次性灌满，避免恢复时突发打满 */
    private void refill() {
        long now = System.nanoTime();
        long last = this.lastRefillNanos;
        if (last <= 0L) {
            this.lastRefillNanos = now;
            return;
        }
        double dtMs = (now - last) / 1_000_000.0;
        this.lastRefillNanos = now;
        if (dtMs <= 0.0) return;
        if (dtMs > 500.0) dtMs = 500.0;   // 卡顿/切后台：最多补 500ms 的量

        int maxPer100 = getMaxPacketsPer100ms();
        if (maxPer100 <= 0) {
            this.tokens = 1.0;            // 不限制
        } else {
            double rate = maxPer100 / 100.0;          // 令牌 / 毫秒
            double cap = maxPer100;                   // 桶容量 = 100ms 的量
            this.tokens = Math.min(cap, this.tokens + rate * dtMs);
        }

        int cosPer100 = getMaxCosmeticPacketsPer100ms();
        if (cosPer100 <= 0) {
            this.cosmeticTokens = 1.0;
        } else {
            double rate = cosPer100 / 100.0;
            double cap = cosPer100;
            this.cosmeticTokens = Math.min(cap, this.cosmeticTokens + rate * dtMs);
        }
    }

    /* ======================== Tick ======================== */

    /** 每 client tick 调用一次（兼容旧调用点，仅做令牌补充） */
    public void tick() {
        refill();
        long now = Util.now();
        if (this.last100MsSpanAt == -1L || now - this.last100MsSpanAt >= 100L) {
            this.last100MsSpanAt = now;
            this.last100MsSpanEstimatedPackets = 0;
        }
    }

    /* ======================== 发包记账 ======================== */

    /** 发出一个包后调用：消耗令牌 */
    public void onPacketSent() {
        refill();
        if (this.tokens >= 1.0) this.tokens -= 1.0;
        this.last100MsSpanEstimatedPackets++;
        checkLimits();
    }

    public void onLookPacketSent() {
        this.lastLookSentAt = Util.now();
        refill();
        if (this.cosmeticTokens >= 1.0) this.cosmeticTokens -= 1.0;
        onPacketSent();
    }

    public void onSwingPacketSent() {
        this.lastSwingSentAt = Util.now();
        refill();
        if (this.cosmeticTokens >= 1.0) this.cosmeticTokens -= 1.0;
        onPacketSent();
    }

    /**
     * 旧版在这里设置 500ms / 10s 的硬冻结 —— 那正是卡顿来源。
     * 现在只保留极短的软化惩罚（30ms），且令牌桶已保证匀速，
     * 实际上几乎不会触发。
     */
    public void checkLimits() {
        int cos = getMaxCosmeticPacketsPer100ms();
        int max = getMaxPacketsPer100ms();
        long now = Util.now();
        if (cos > 0 && this.last100MsSpanEstimatedPackets >= cos) {
            // 软化：30ms 后即可恢复，不再是 500ms 硬闸
            this.reducePacketsUntil = Math.max(this.reducePacketsUntil, now + 30L);
        }
        if (max > 0 && this.last100MsSpanEstimatedPackets >= max) {
            // 软化：50ms，不再是 250ms + 10000ms 双重冻结
            this.stopPacketsUntil = Math.max(this.stopPacketsUntil, now + 50L);
            this.reducePacketsUntil = Math.max(this.reducePacketsUntil, now + 50L);
        }
    }

    /* ======================== 查询 ======================== */

    public boolean canSendCosmeticPacket() {
        refill();
        if (this.reducePacketsUntil > 0L && Util.now() < this.reducePacketsUntil) return false;
        return this.cosmeticTokens >= 1.0;
    }

    public boolean canSendAnyPacket() {
        refill();
        if (this.stopPacketsUntil > 0L && Util.now() < this.stopPacketsUntil) return false;
        return this.tokens >= 1.0;
    }

    public boolean canSendLookPacket() {
        refill();
        return ((this.lastLookSentAt == -1L || Util.now() - this.lastLookSentAt >= 50L)
                && canSendCosmeticPacket());
    }

    public boolean canSendSwingPacket() {
        refill();
        return ((this.lastSwingSentAt == -1L || Util.now() - this.lastSwingSentAt >= 50L)
                && canSendCosmeticPacket());
    }
}
