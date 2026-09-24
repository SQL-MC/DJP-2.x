package semmiedev.disc_jockey;


public class SpectrumDataSmoother {

    public static final int BAND_COUNT = 16;

    private float[] smoothed = new float[BAND_COUNT];
    private float[] lastValid = new float[BAND_COUNT];

    
    
    private float[] peakHold = new float[BAND_COUNT];

    
    private int[] peakTimer = new int[BAND_COUNT];

    
    private static final int PEAK_HOLD_TICKS = 3;

    
    private static final float ATTACK = 0.25F;   
    private static final float DECAY = 0.45F;   
    private static final float SNAP = 0.001F;

    
    private static final float OUTPUT_BOOST = 1.14514F;

    
    private static final float IDLE_DECAY = 0.15F;

    
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
            boolean songRunning = Main.SONG_PLAYER != null
                    && Main.SONG_PLAYER.running
                    && Main.SONG_PLAYER.song != null;
            boolean previewRunning = false;
            try {
                previewRunning = Previewer.running
                        && Previewer.getInstance() != null
                        && Previewer.getInstance().getSong() != null;
            } catch (Throwable t) {}
            isPlaying = songRunning || previewRunning;
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
            float rawVal = raw[i] * OUTPUT_BOOST;
            if (rawVal > 1.0F) rawVal = 1.0F;

            
            if (rawVal > peakHold[i]) {
                peakHold[i] = rawVal;
                peakTimer[i] = PEAK_HOLD_TICKS;
            } else if (peakTimer[i] > 0) {
                peakTimer[i]--;
            } else {
                peakHold[i] = rawVal;
            }

            
            float target = peakHold[i];
            float current = smoothed[i];

            if (target > current) {
                smoothed[i] = current + (target - current) * ATTACK;
            } else {
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
