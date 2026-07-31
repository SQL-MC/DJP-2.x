package semmiedev.disc_jockey.gui.screen.spectrum;

import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.SongPlayer;

/**
 * ✅ 频谱数据生产器
 * 只负责：从 SongPlayer 拿原始电平 → 平滑 → 衰减
 * 不负责任何渲染
 */
public class SpectrumVisualizer {

    /** ✅ 与你所有 Renderer 对齐 */
    public static final int BAND_COUNT = 16;

    /** ✅ 当前帧（已平滑） */
    public float[] currentLevels = new float[BAND_COUNT];

    /** ✅ 上一帧（用于平滑插值） */
    private float[] prevLevels = new float[BAND_COUNT];

    /** ✅ 频谱总开关（供 /discjockey spectrum 使用） */
    private boolean enabled = true;

    /** ✅ 衰减速度（每 tick） */
    private static final float DECAY = 0.15f;

    /** ✅ 平滑系数（0~1，越大越跟手） */
    private static final float SMOOTH = 0.35f;

    /**
     * ✅ 每 tick 调用一次（由 Main / HUD / Screen 驱动）
     */
    public void tick() {
        SongPlayer player = Main.SONG_PLAYER;
        if (player == null || !player.running || player.song == null) {
            // ✅ 无播放时自然衰减
            for (int i = 0; i < BAND_COUNT; i++) {
                currentLevels[i] = Math.max(0f, currentLevels[i] - DECAY);
            }
            return;
        }

        float[] raw = player.getSpectrumLevels(BAND_COUNT);

        for (int i = 0; i < BAND_COUNT; i++) {
            float target = clamp(raw[i]);
            float current = currentLevels[i];

            // ✅ 上升快，下降慢（更“DJ”的感觉）
            if (target > current) {
                currentLevels[i] = lerp(current, target, 0.4f);
            } else {
                currentLevels[i] = lerp(current, target, SMOOTH);
            }

            // ✅ 最低可见高度
            if (currentLevels[i] < 0.01f) {
                currentLevels[i] = 0f;
            }
        }

        System.arraycopy(currentLevels, 0, prevLevels, 0, BAND_COUNT);
    }

    /* =========================================================
       ✅【关键补丁】旧 API：音符事件注入
       供 AudioLevelCollector / Previewer / SongPlayer 使用
       ========================================================= */
    public void onNotePlayed(int instrumentId, int noteId) {
        if (!enabled) return;

        // ✅ 简单能量注入（稳定、不依赖 FFT）
        int band = Math.abs(noteId) % BAND_COUNT;
        float energy = 0.6f + (instrumentId % 3) * 0.15f;

        currentLevels[band] = Math.max(currentLevels[band], energy);
    }

    /* =========================================================
       ✅【关键补丁】旧 API：频谱开关
       供 /discjockey spectrum 命令使用
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