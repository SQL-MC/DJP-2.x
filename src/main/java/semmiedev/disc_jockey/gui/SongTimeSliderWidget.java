package semmiedev.disc_jockey.gui;
// DJ_GUI23

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Song;

public class SongTimeSliderWidget extends AbstractSliderButton {

    public SongTimeSliderWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(), 0);
    }

    private static String padZeroes(int number, int length) {
        StringBuilder builder = new StringBuilder("" + number);
        while (builder.length() < length) {
            builder.insert(0, '0');
        }
        return builder.toString();
    }

    private static String formatTimestamp(int seconds) {
        return padZeroes(seconds / 60, 2) + ":" + padZeroes(seconds % 60, 2);
    }

    // DJ_GUI8: preview stays live while paused; GlobalPlay injects an empty
    // Song to open the spectrum gate, so require at least one note.
    private static boolean previewActive() {
        try {
            Song s = Main.PREVIEWER.getInstance().getSong();
            return s != null && s.notes != null && s.notes.length > 0
                    && (Main.PREVIEWER.running || Main.PREVIEWER.paused);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Song activeSong() {
        try {
            if (previewActive()) return Main.PREVIEWER.getInstance().getSong();
        } catch (Throwable ignored) {}
        return Main.SONG_PLAYER.song;
    }

    private static double elapsedSeconds() {
        try {
            if (previewActive()) {
                Song s = Main.PREVIEWER.getInstance().getSong();
                if (s != null) return s.ticksToMilliseconds(Main.PREVIEWER.getInstance().getTick()) / 1000.0D;
            }
        } catch (Throwable ignored) {}
        return Main.SONG_PLAYER.getSongElapsedSeconds();
    }

    @Override
    protected void updateMessage() {
        Song song = activeSong();
        if (song == null) {
            setMessage(Component.empty());
            return;
        }
        String prefix = previewActive() ? "\u266a " : "";
        setMessage(Component.literal(prefix
                + formatTimestamp((int) elapsedSeconds()) + " / "
                + formatTimestamp((int) song.getLengthInSeconds())));
    }

    @Override
    protected void applyValue() {
        if (previewActive()) {
            Song s = Main.PREVIEWER.getInstance().getSong();
            double total = s.getLengthInSeconds();
            Main.PREVIEWER.seekToSeconds(value * total);
            return;
        }
        if (Main.SONG_PLAYER.song == null) return;
        double total = Main.SONG_PLAYER.song.getLengthInSeconds();
        Main.SONG_PLAYER.setSongElapsedSeconds(value * total);
    }

    public void update() {
        Song song = activeSong();
        if (song == null) return;
        double elapsed = elapsedSeconds();
        double total = song.getLengthInSeconds();
        if (total <= 0) total = 1;
        double v = elapsed / total;
        if (v < 0) v = 0;
        if (v > 1) v = 1;
        value = v;
        updateMessage();
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        Song song = activeSong();
        if (song != null) {
            int cur = (int) elapsedSeconds();
            int len = (int) song.getLengthInSeconds();
            output.add(NarratedElementType.TITLE,
                    Component.translatable(previewActive() ? "disc_jockey.lit.3cafbc25a8" : "disc_jockey.lit.64cdac5839"));
            output.add(NarratedElementType.HINT,
                    Component.literal(formatTimestamp(cur) + " / " + formatTimestamp(len)));
        } else {
            output.add(NarratedElementType.TITLE,
                    Component.translatable("disc_jockey.lit.f2e0676bc9"));
        }
    }
}
