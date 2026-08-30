package semmiedev.disc_jockey.gui.screen.spectrum;

import semmiedev.disc_jockey.Previewer;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.SongPlayer;

/**
 * ✅ 频谱数据生产器（DJP021482 根治版 · DJP023xxx 播放频谱修复 · DJP023yyy 卡顶修复）
 *
 * ============================================================
 * ✅ DJP023xxx 修复说明（播放时不再出现频谱）：
 * ------------------------------------------------------------
 * 根因：DJP021482 重构时，songRunning 分支改为走
 *   player.getSpectrumLevels(BAND_COUNT) 作为"世界内真实电平"，
 *   但 SongPlayer.getSpectrumLevels() 实现永远返回全 0 数组，
 *   导致播放时 raw 全 0 → currentLevels 全 0 → 渲染无频谱。
 *
 * 同时，onNotePlayed 注入的 previewPeaks（瞬时峰值）在 tick 末尾
 * 被清零，而 songRunning 分支根本不读 previewPeaks，峰值被丢弃。
 *
 * 修复：songRunning 时若 getSpectrumLevels 返回"实质无信号"
 *   （max<=0，即无真实电平数据），则回退到本 tick 由 onNotePlayed
 *   注入的 previewPeaks，与 preview 路径统一数据源。
 *   AudioLevelCollector 对播放/预览都调 onNotePlayed，注入路径一致。
 *
 * 这样播放和预览都基于 previewPeaks 瞬时峰值 + SMOOTHER 峰值保持，
 * 渲染一定能读到峰值 → 频谱正常显示。
 * ============================================================
 *
 * ✅ DJP023yyy 卡顶修复说明（播放时赖在最高处不下来）：
 * ------------------------------------------------------------
 * 根因：songRunning&&!previewRunning 时，衰减循环被条件
 *   `if (!songRunning || previewRunning)` 跳过，currentLevels
 *   只增不减 → 每 tick 有音符写成 0.5、无音符也不衰减 → 卡顶。
 *
 * 修复：把"先回落"无条件化——所有路径每 tick 都先乘 DECAY，
 *   有峰值时再覆盖为峰值。这样播放/预览/空闲统一"先回落再顶"，
 *   无音符自然衰减到 0，有音符随峰值跳动，不再卡顶。
 * ============================================================
 *
 * Preview 策略：瞬时采样 + 快速指数衰减
 * - 每 tick 先让 currentLevels 乘衰减系数（值必然下降，根治卡顶）
 * - onNotePlayed 只设 previewPeaks 峰值（取最大值，不累加）
 * - tick() 用 previewPeaks 作 raw 后立刻清零，保证下一 tick 峰值只反映本 tick 实际播放的音符
 * - 世界内仍优先走 player.getSpectrumLevels(16)；仅在返回无信号时回退 previewPeaks
 */
public class SpectrumVisualizer {

    /** ✅ 与你所有 Renderer 对齐 */
    public static final int BAND_COUNT = 16;

    /** ✅ 当前帧（已平滑） */
    public float[] currentLevels = new float[BAND_COUNT];

    /** ✅ 上一帧（用于平滑插值） */
    private float[] prevLevels = new float[BAND_COUNT];

    /** ✅ Preview 瞬时峰值（与 currentLevels 解耦，每 tick 采集后清零） */
    private float[] previewPeaks = new float[BAND_COUNT];

    /** ✅ 频谱总开关（供 /discjockey spectrum 使用） */
    private boolean enabled = true;

    /**
     * ✅ 每 tick 衰减系数
     * 0.6 ≈ 3 tick 降到 1/8（约 0.15s），回落明显且自然
     * 这是"值必然下降"的唯一路径，根治卡顶
     */
    private static final float DECAY = 0.6f;

    /** ✅ 平滑系数（current→target 跟手度） */
    private static final float SMOOTH = 0.4f;

    /**
     * ✅ 峰值采样能量（不累加，只取最大）
     * DJP021482：0.7 → 0.5，降低 preview 峰值上限，配合 PREVIEW_SCALE=1.0 使输出经 SMOOTHER 放大后仍不顶满
     */
    private static final float PEAK_ENERGY = 0.5f;

    /**
     * ✅ Preview 显示系数：currentLevels = raw * PREVIEW_SCALE
     * DJP021482：0.35 → 1.0（原 0.35 压到 0.175 导致世界外几乎不可见/消失）
     *   现 0.5 * 1.0 = 0.5（中高位，渲染约一半高度，明显可见），
     *   经 SMOOTHER.OUTPUT_BOOST≈1.145 后 ≈0.57，<1.0 不顶满
     */
    private static final float PREVIEW_SCALE = 1.0f;

    /**
     * ✅ 每 tick 调用一次（由 Main / HUD / Screen 驱动）
     */
    public void tick() {
        SongPlayer player = Main.SONG_PLAYER;
        boolean songRunning = player != null && player.running && player.song != null;
        boolean previewRunning = false;
        try {
            previewRunning = Previewer.running
                    && Previewer.getInstance() != null
                    && Previewer.getInstance().getSong() != null;
        } catch (Throwable t) {}

        // ✅ DJP023yyy：无条件先回落（所有路径都进），根治卡顶
        //   原条件 `if (!songRunning || previewRunning)` 会让纯播放分支跳过衰减 → 卡顶
        for (int i = 0; i < BAND_COUNT; i++) {
            currentLevels[i] *= DECAY;
            if (currentLevels[i] < 0.01f) currentLevels[i] = 0f;
        }

        if (!songRunning && !previewRunning) {
            // ✅ 完全空闲：currentLevels 已衰减，直接返回
            return;
        }

        // ✅ 取 raw：优先 player.getSpectrumLevels（世界内真实电平意图）；
        //   DJP023xxx：若返回无信号（max<=0，即无真实数据），回退到 previewPeaks
        float[] raw;
        boolean usePeaksFallback = false;

        if (songRunning && !previewRunning) {
            raw = player.getSpectrumLevels(BAND_COUNT);
            // 检测是否实质无信号（getSpectrumLevels 在你们实现里返回全 0）
            float maxRaw = 0f;
            for (int i = 0; i < BAND_COUNT; i++) {
                if (raw[i] > maxRaw) maxRaw = raw[i];
            }
            if (maxRaw <= 0f) {
                // ✅ DJP023xxx：回退到本 tick onNotePlayed 注入的 previewPeaks
                raw = previewPeaks;
                usePeaksFallback = true;
            }
        } else {
            raw = previewPeaks; // 本 tick 内 onNotePlayed 设的峰值
            usePeaksFallback = true;
        }

        // ✅ DJP023xxx：统一用 raw*PREVIEW_SCALE + 衰减回落，播放/预览一致
        //   PREVIEW_SCALE=1.0 → 输出 0.5（中高位可见），经 SMOOTHER≈0.57 不顶满
        //   无论 songRunning 还是 previewRunning，只要 raw 有峰值就能显示频谱
        for (int i = 0; i < BAND_COUNT; i++) {
            if (raw[i] > 0f) {
                currentLevels[i] = raw[i] * PREVIEW_SCALE;
            }
            if (currentLevels[i] < 0.01f) currentLevels[i] = 0f;
        }

        // ✅ 关键：本 tick 处理完 raw 后立刻清零 previewPeaks
        //   → 下一 tick 的峰值只反映"下一 tick 实际播放的音符"，不跨 tick 累积
        for (int i = 0; i < BAND_COUNT; i++) {
            previewPeaks[i] = 0f;
        }

        System.arraycopy(currentLevels, 0, prevLevels, 0, BAND_COUNT);
    }

    /* =========================================================
       ✅ 音符事件注入（Preview / 播放时均由 Previewer/AudioLevelCollector 调用）
       - 只设峰值（取最大值），不累加
       - 同一 tick 多个音符只保留最强一个，能量固定 PEAK_ENERGY=0.5
       - 配合 tick() 末尾清零，单 tick 内峰值有界，绝不可能冲顶
       ========================================================= */
    public void onNotePlayed(int instrumentId, int noteId) {
        if (!enabled) return;

        // ✅ noteId 0~24 → band 0~15（foldToRange 已在 Previewer 保证 0~24）
        int band = Math.abs(noteId) % BAND_COUNT;
        if (PEAK_ENERGY > previewPeaks[band]) {
            previewPeaks[band] = PEAK_ENERGY;
        }
    }

    /* =========================================================
       ✅ 供 Previewer/命令 重置频谱（空闲/stop/高速时清屏）
       - 清零 previewPeaks + currentLevels 归零
       - Previewer 通过反射调用（字段保持 private）
       ========================================================= */
    public void resetPeaks() {
        if (previewPeaks != null) {
            for (int i = 0; i < previewPeaks.length; i++) previewPeaks[i] = 0f;
        }
        if (currentLevels != null) {
            for (int i = 0; i < currentLevels.length; i++) currentLevels[i] = 0f;
        }
    }

    /* =========================================================
       ✅ 频谱开关（供 /discjockey spectrum 命令使用）
       ========================================================= */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}