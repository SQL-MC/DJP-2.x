package semmiedev.disc_jockey;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * ✅ 音符盒八度折叠工具
 * 
 * - buildFoldedNotes: noteId + transpose, 折叠到 0~24
 * - applyTranspose: 运行时 transpose 工具方法
 * - checkSong: 检查歌曲是否需要折叠
 * - clearFoldedNotes / hasFoldedNotes: 缓存管理
 */
public class NoteClamper {

    public static final int NOTE_SHIFT = 40;
    public static final long NOTE_MASK = 0xFFL << NOTE_SHIFT;

    @Deprecated
    public static void clampSong(Song song) {
        throw new UnsupportedOperationException(
                "clampSong() is destructive and disabled. Use buildFoldedNotes() instead."
        );
    }

    /**
     * ✅ 安全的 transpose + 八度折叠
     * 
     * 只做：rawNoteId + transpose，然后 % 25 折叠到 0~24
     * 不改 instrumentId（Tuner 按 instrument 分组）
     */
    public static void buildFoldedNotes(Song song, int transpose) {
        if (song == null || song.notes == null || song.notes.length == 0) return;

        long[] folded = new long[song.notes.length];

        for (int i = 0; i < song.notes.length; i++) {
            long note = song.notes[i];

            int instrumentId = (int) ((note >> 32L) & 0xFF);
            int rawNoteId = (int) ((note & NOTE_MASK) >>> NOTE_SHIFT);

            int foldedId = rawNoteId + transpose;
            foldedId = ((foldedId % 25) + 25) % 25;

            folded[i] =
                    (note & ~(0xFFL << NOTE_SHIFT))
                  | ((long) foldedId << NOTE_SHIFT);
        }

        song.foldedNotes = folded;
    }

    public static void clearFoldedNotes(Song song) {
        if (song != null) song.foldedNotes = null;
    }

    public static boolean hasFoldedNotes(Song song) {
        return song != null && song.foldedNotes != null && song.foldedNotes.length > 0;
    }

    /**
     * ✅ 运行时 transpose（SongPlayer.applyTranspose 用）
     */
    public static int applyTranspose(int rawNoteId, int transpose) {
        int result = rawNoteId + transpose;
        result = ((result % 25) + 25) % 25;
        return result;
    }

    public static int checkSong(Song song) {
        if (song == null || song.notes == null) return 0;
        int min = 255, max = 0;
        for (long note : song.notes) {
            int n = (int) ((note & NOTE_MASK) >>> NOTE_SHIFT);
            min = Math.min(min, n);
            max = Math.max(max, n);
        }
        if (min < 0 || max > 24) return max - min + 1;
        return 0;
    }
}