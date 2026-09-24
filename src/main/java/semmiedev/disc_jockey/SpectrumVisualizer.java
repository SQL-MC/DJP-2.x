package semmiedev.disc_jockey.gui.screen.spectrum;

import semmiedev.disc_jockey.Previewer;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.SongPlayer;


public class SpectrumVisualizer {

    
    public static final int BAND_COUNT = 16;

    
    public float[] currentLevels = new float[BAND_COUNT];

    
    private float[] prevLevels = new float[BAND_COUNT];

    
    private float[] previewPeaks = new float[BAND_COUNT];

    
    private boolean enabled = true;

    
    private static final float DECAY = 0.6f;

    
    private static final float SMOOTH = 0.4f;

    
    private static final float PEAK_ENERGY = 0.5f;

    
    private static final float PREVIEW_SCALE = 1.0f;

    
    public void tick() {
        SongPlayer player = Main.SONG_PLAYER;
        boolean songRunning = player != null && player.running && player.song != null;
        boolean previewRunning = false;
        try {
            previewRunning = Previewer.running
                    && Previewer.getInstance() != null
                    && Previewer.getInstance().getSong() != null;
        } catch (Throwable t) {}

        
        
        for (int i = 0; i < BAND_COUNT; i++) {
            currentLevels[i] *= DECAY;
            if (currentLevels[i] < 0.01f) currentLevels[i] = 0f;
        }

        if (!songRunning && !previewRunning) {
            
            return;
        }

        
        
        float[] raw;
        boolean usePeaksFallback = false;

        if (songRunning && !previewRunning) {
            raw = player.getSpectrumLevels(BAND_COUNT);
            
            float maxRaw = 0f;
            for (int i = 0; i < BAND_COUNT; i++) {
                if (raw[i] > maxRaw) maxRaw = raw[i];
            }
            if (maxRaw <= 0f) {
                
                raw = previewPeaks;
                usePeaksFallback = true;
            }
        } else {
            raw = previewPeaks; 
            usePeaksFallback = true;
        }

        // ✅ DJP023xxx：统一用 raw*PREVIEW_SCALE + 衰减回落，播放/预览一致
        
        
        for (int i = 0; i < BAND_COUNT; i++) {
            if (raw[i] > 0f) {
                currentLevels[i] = raw[i] * PREVIEW_SCALE;
            }
            if (currentLevels[i] < 0.01f) currentLevels[i] = 0f;
        }

        
        //   → 下一 tick 的峰值只反映"下一 tick 实际播放的音符"，不跨 tick 累积
        for (int i = 0; i < BAND_COUNT; i++) {
            previewPeaks[i] = 0f;
        }

        System.arraycopy(currentLevels, 0, prevLevels, 0, BAND_COUNT);
    }

    
    public void onNotePlayed(int instrumentId, int noteId) {
        if (!enabled) return;

        
        int band = Math.abs(noteId) % BAND_COUNT;
        if (PEAK_ENERGY > previewPeaks[band]) {
            previewPeaks[band] = PEAK_ENERGY;
        }
    }

    
    public void resetPeaks() {
        if (previewPeaks != null) {
            for (int i = 0; i < previewPeaks.length; i++) previewPeaks[i] = 0f;
        }
        if (currentLevels != null) {
            for (int i = 0; i < currentLevels.length; i++) currentLevels[i] = 0f;
        }
    }

    
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