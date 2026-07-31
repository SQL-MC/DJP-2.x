package semmiedev.disc_jockey;

/**
 * ✅ 频谱缓冲器（最终版 · 峰值保持）
 * 
 * 问题：computeIntensity() 的 exp 衰减太快（1~2tick 就掉到 0.3）
 *       → 渲染读到时峰值已过 → "不缓冲"
 * 
 * 修复：每个 band 保持峰值 2~3 tick 再衰减
 *       → 渲染一定能读到峰值 → 柱子顶上去 → 丝滑落下
 */
public class SpectrumDataSmoother {

    public static final int BAND_COUNT = 16;

    private float[] smoothed = new float[BAND_COUNT];
    private float[] lastValid = new float[BAND_COUNT];

    /* =========================================================
       ✅ 峰值保持（核心机制）
       ========================================================= */
    /** 每个 band 当前保持的峰值 */
    private float[] peakHold = new float[BAND_COUNT];

    /** 每个 band 的峰值计时器（tick 数） */
    private int[] peakTimer = new int[BAND_COUNT];

    /** 峰值保持时长（tick）— 2~3 最丝滑 */
    private static final int PEAK_HOLD_TICKS = 3;

    /* =========================================================
       ✅ 平滑参数（和 GUI 的 Screen 平滑一致）
       ========================================================= */
    private static final float ATTACK = 0.25F;   // 上升（几乎即时）
    private static final float DECAY = 0.45F;   // 下降（丝滑拖尾）
    private static final float SNAP = 0.001F;

    /* =========================================================
       ✅ 输出放大（高度已验证正确 = 96px）
       ========================================================= */
    private static final float OUTPUT_BOOST = 1.14514F;

    /** 静止衰减 */
    private static final float IDLE_DECAY = 0.15F;

    /** 信号阈值 */
    private static final float SIGNAL_THRESH = 0.05F;

    public void tick() {
        float[] raw = null;
        try {
            if (Main.SPECTRUM != null && Main.SPECTRUM.currentLevels != null) {
                raw = Main.SPECTRUM.currentLevels;
            }
        } catch (Throwable t) {
            raw = null;
        }

        if (raw == null || raw.length < BAND_COUNT) {
            raw = lastValid;
        } else {
            System.arraycopy(raw, 0, lastValid, 0, BAND_COUNT);
        }

        boolean isPlaying = false;
        try {
            isPlaying = Main.SONG_PLAYER != null
                    && Main.SONG_PLAYER.running
                    && Main.SONG_PLAYER.song != null;
        } catch (Throwable t) {}

        if (!isPlaying) {
            for (int i = 0; i < BAND_COUNT; i++) {
                smoothed[i] = Math.max(0f, smoothed[i] - IDLE_DECAY);
                peakHold[i] = 0f;
                peakTimer[i] = 0;
            }
            return;
        }

        for (int i = 0; i < BAND_COUNT; i++) {
            float rawVal = raw[i] * OUTPUT_BOOST;  // 放大到 ~1.0
            if (rawVal > 1.0F) rawVal = 1.0F;

            // === 峰值保持逻辑 ===
            if (rawVal > peakHold[i]) {
                // ✅ 新峰值来了，更新并保持
                peakHold[i] = rawVal;
                peakTimer[i] = PEAK_HOLD_TICKS;
            } else if (peakTimer[i] > 0) {
                // ✅ 保持期内，维持峰值不变
                peakTimer[i]--;
            } else {
                // ✅ 保持期过了，开始跟随 rawVal（自然衰减）
                peakHold[i] = rawVal;
            }

            // === 用 peakHold 做平滑输出 ===
            float target = peakHold[i];
            float current = smoothed[i];

            if (target > current) {
                // ✅ 上升：快速跟上峰值
                smoothed[i] = current + (target - current) * ATTACK;
            } else {
                // ✅ 下降：丝滑拖尾
                smoothed[i] = current + (target - current) * DECAY;
            }

            if (Math.abs(smoothed[i] - target) < SNAP) {
                smoothed[i] = target;
            }
        }
    }

    public float[] getSmoothedLevels() {
        return smoothed;
    }

    public void reset() {
        for (int i = 0; i < BAND_COUNT; i++) {
            smoothed[i] = 0f;
            lastValid[i] = 0f;
            peakHold[i] = 0f;
            peakTimer[i] = 0;
        }
    }
}