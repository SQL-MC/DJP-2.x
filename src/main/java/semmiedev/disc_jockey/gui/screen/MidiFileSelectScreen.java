package semmiedev.disc_jockey.gui.screen;

import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.MidiToNbsImporter;
import semmiedev.disc_jockey.Song;
import semmiedev.disc_jockey.SongLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MidiFileSelectScreen extends Screen {

    private final Screen parent;
    private final File midiDir;

    public MidiFileSelectScreen(Screen parent) {
        super(Component.translatable("disc_jockey.screen.import_midi"));
        this.parent = parent;
        this.midiDir = new File(Main.songsFolder.getParentFile(), "midi");
    }

    @Override
    protected void init() {
        this.addRenderableWidget(
                Button.builder(Component.literal("← Back"), btn ->
                        Minecraft.getInstance().gui.setScreen(parent)
                )
                        .bounds(10, 10, 80, 20)
                        .build()
        );

        if (!midiDir.exists() || !midiDir.isDirectory()) return;

        File[] files = midiDir.listFiles(
                f -> f.isFile() &&
                     (f.getName().toLowerCase().endsWith(".mid") ||
                      f.getName().toLowerCase().endsWith(".midi"))
        );

        if (files == null) return;

        List<File> midiFiles = new ArrayList<>(List.of(files));
        int y = 40;

        for (File file : midiFiles) {
            this.addRenderableWidget(
                    Button.builder(
                            Component.literal(file.getName()),
                            btn -> importMidi(file)
                    ).bounds(width / 2 - 100, y, 200, 20).build()
            );
            y += 24;
        }
    }

    private void importMidi(File midiFile) {
        try {
            Song song = MidiToNbsImporter.importMidi(midiFile);
            if (song == null) {
                minecraft.gui.chatListener().handleSystemMessage(
                        Component.translatable("disc_jockey.import.empty_or_invalid"),
                        false
                );
                return;
            }

            SongLoader.SONGS.add(song);
            SongLoader.sort();

            minecraft.gui.chatListener().handleSystemMessage(
                    Component.translatable("disc_jockey.import.success", song.displayName),
                    false
            );

            Minecraft.getInstance().gui.setScreen(parent);
        } catch (Exception e) {
            Main.LOGGER.error("MIDI import failed", e);
            minecraft.gui.chatListener().handleSystemMessage(
                    Component.translatable("disc_jockey.import.error"),
                    false
            );
        }
    }
}