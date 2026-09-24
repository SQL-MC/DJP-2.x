package semmiedev.disc_jockey;


public class AudioLevelCollector {

    
    public static void update() {
        Song song = null;
        int index = 0;
        boolean running = false;

        
        if (Previewer.running) {
            song = Previewer.getInstance().getSong();
            index = Previewer.getInstance().getI();
            running = true;
        }
        
        else if (Main.SONG_PLAYER.running) {
            song = Main.SONG_PLAYER.getSong();
            index = Main.SONG_PLAYER.getIndex();
            running = true;
        }

        if (!running || song == null || song.notes == null) {
            
            return;
        }

        long[] notes = song.notes;
        if (index < 0 || index >= notes.length) {
            return;
        }

        long note = notes[index];
        int instrumentId = (int) ((note >> 32L) & 0xFF);
        int noteId = (int) ((note >> 40L) & 0xFF);

        
        Main.SPECTRUM.onNotePlayed(instrumentId & 0xFF, noteId);
    }
}