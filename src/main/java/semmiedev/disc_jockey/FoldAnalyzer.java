package semmiedev.disc_jockey;

import java.util.List;


public class FoldAnalyzer {

    public record RangeReport(
            String songName,
            int minNote,
            int maxNote,
            boolean needsFold,
            boolean severelyOutOfRange,
            int suggestedTransposeDown,
            int suggestedTransposeUp
    ) {}

    public static RangeReport analyze(Song song) {
        if (song == null || song.notes == null || song.notes.length == 0) {
            return new RangeReport(
                    "null",
                    0,
                    0,
                    false,
                    false,
                    0,
                    0
            );
        }

        int min = 255;
        int max = 0;

        for (long note : song.notes) {
            
            
            int rawNoteId = Note.extractNoteId(note);
            min = Math.min(min, rawNoteId);
            max = Math.max(max, rawNoteId);
        }

        boolean needsFold = min < 0 || max > 24;
        boolean severe = min < -12 || max > 36;

        int down = 0;
        int up = 0;
        if (min < 0) down = -(min / 12 + 1) * 12;
        if (max > 24) up = ((max - 24) / 12 + 1) * 12;

        return new RangeReport(
                song.displayName != null ? song.displayName : song.fileName,
                min,
                max,
                needsFold,
                severe,
                down,
                up
        );
    }

    
    public static int countSongsNeedingFold(List<Song> songs) {
        int c = 0;
        for (Song s : songs) {
            if (analyze(s).needsFold()) {
                
                
                c++;
            }
        }
        return c;
    }
}
