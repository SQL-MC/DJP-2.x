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
     * 用 Note.extractNoteId 还原有符号 noteId（-33 ~ +53）
     * 然后 + transpose，再八度折叠到 0~24
     * 不改 instrumentId（Tuner 按 instrument 分组）
     */
    public static void buildFoldedNotes(Song song, int transpose) {
        if (song == null || song.notes == null || song.notes.length == 0) return;

        long[] folded = new long[song.notes.length];

        for (int i = 0; i < song.notes.length; i++) {
            long note = song.notes[i];

            int instrumentId = (int) ((note >> 32L) & 0xFF);
            int rawNoteId = Note.extractNoteId(note); // ✅ 有符号还原，替代旧的 >>> NOTE_SHIFT

            int foldedId = rawNoteId + transpose;

            // ✅【修复】八度折叠替代取模
            // 取模会把 #F24+1 变成 #F0（差一个八度，音高错乱）
            // 八度折叠把超出的音就近折回 0~24 范围内
            while (foldedId > 24) foldedId -= 12;
            while (foldedId < 0)  foldedId += 12;
            if (foldedId < 0)  foldedId = 0;
            if (foldedId > 24) foldedId = 24;

            long rebuilt = (note & ~(0xFFL << NOTE_SHIFT));
            rebuilt |= ((long) ((byte) foldedId & 0xFF) << NOTE_SHIFT);
            folded[i] = rebuilt;
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
     * ✅ 八度折叠替代取模，保护 #F24 不被压掉
     */
    public static int applyTranspose(int rawNoteId, int transpose) {
        int result = rawNoteId + transpose;
        while (result > 24) result -= 12;
        while (result < 0)  result += 12;
        if (result < 0)  result = 0;
        if (result > 24) result = 24;
        return result;
    }

    public static int checkSong(Song song) {
        if (song == null || song.notes == null) return 0;
        int min = 255, max = 0;
        for (long note : song.notes) {
            int n = Note.extractNoteId(note); // ✅ 有符号还原
            min = Math.min(min, n);
            max = Math.max(max, n);
        }
        if (min < 0 || max > 24) return max - min + 1;
        return 0;
    }
}
