package semmiedev.disc_jockey;

/**
 * 音频电平采集器
 * ✅ 只负责“读”
 * ❌ 不直接写任何频谱数组
 *
 * 由 Main.tick() → AudioLevelCollector.update() 驱动
 */
public class AudioLevelCollector {

    /**
     * 每帧调用一次
     */
    public static void update() {
        Song song = null;
        int index = 0;
        boolean running = false;

        // 优先 Previewer
        if (Previewer.running) {
            song = Previewer.getInstance().getSong();
            index = Previewer.getInstance().getI();
            running = true;
        }
        // 否则 SongPlayer
        else if (Main.SONG_PLAYER.running) {
            song = Main.SONG_PLAYER.getSong();
            index = Main.SONG_PLAYER.getIndex();
            running = true;
        }

        if (!running || song == null || song.notes == null) {
            // 淡出由 SpectrumVisualizer.tick() 负责
            return;
        }

        long[] notes = song.notes;
        if (index < 0 || index >= notes.length) {
            return;
        }

        long note = notes[index];
        int instrumentId = (int) ((note >> 32L) & 0xFF);
        int noteId = (int) ((note >> 40L) & 0xFF);

        // ✅ 唯一合法写入方式
        Main.SPECTRUM.onNotePlayed(instrumentId & 0xFF, noteId);
    }
}