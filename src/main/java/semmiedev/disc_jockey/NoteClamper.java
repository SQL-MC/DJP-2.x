package semmiedev.disc_jockey;

import java.util.ArrayList;
import java.util.HashSet;


public class NoteClamper {

    public static final int NOTE_SHIFT = 40;
    public static final long NOTE_MASK = 0xFFL << NOTE_SHIFT;

    @Deprecated
    public static void clampSong(Song song) {
        throw new UnsupportedOperationException(
                "clampSong() is destructive and disabled. Use buildFoldedNotes() instead."
        );
    }

    
    public static void buildFoldedNotes(Song song, int transpose) {
        if (song == null || song.notes == null || song.notes.length == 0) return;

        long[] folded = new long[song.notes.length];

        for (int i = 0; i < song.notes.length; i++) {
            long note = song.notes[i];

            int instrumentId = (int) ((note >> 32L) & 0xFF);
            int rawNoteId = Note.extractNoteId(note); 

            int foldedId = rawNoteId + transpose;

            
            
            
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
            int n = Note.extractNoteId(note); 
            min = Math.min(min, n);
            max = Math.max(max, n);
        }
        if (min < 0 || max > 24) return max - min + 1;
        return 0;
    }
}
