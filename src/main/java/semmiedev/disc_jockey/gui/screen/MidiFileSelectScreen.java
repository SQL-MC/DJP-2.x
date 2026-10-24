package semmiedev.disc_jockey.gui.screen;

import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.MidiToNbsImporter;
import semmiedev.disc_jockey.Song;
import semmiedev.disc_jockey.SongLoader;
import semmiedev.disc_jockey.gui.SongListWidget;   
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
                        Component.translatable("disc_jockey.import.empty_or_invalid"), false);
                return;
            }

            
            if (song.entry == null) {
                song.entry = new SongListWidget.SongEntry(song, SongLoader.SONGS.size());
            }
            song.entry.songListWidget = null;

            File saved = saveImportedSong(song);

            SongLoader.SONGS.add(song);
            SongLoader.sort();

            
            if (parent instanceof DiscJockeyScreen djs) {
                djs.markSongsDirty();
            }

            minecraft.gui.chatListener().handleSystemMessage(
                    (saved != null)
                        ? Component.literal("§a[DiscJockey] 导入成功并已保存: §f" + saved.getName())
                        : Component.translatable("disc_jockey.import.success", song.displayName),
                    false);

            Minecraft.getInstance().gui.setScreen(parent);
        } catch (Exception e) {
            Main.LOGGER.error("MIDI import failed", e);
            minecraft.gui.chatListener().handleSystemMessage(
                    Component.literal("§c[DiscJockey] 导入失败: " + e.getMessage()), false);
        }
    }

    private static File saveImportedSong(Song song) {
        try {
            if (!Main.songsFolder.exists()) Main.songsFolder.mkdirs();
            String base = (song.displayName != null ? song.displayName : "imported")
                    .replaceAll("[^a-zA-Z0-9_\\-\\s]", "_")
                    .replaceAll("\\s+", "_");
            if (base.isEmpty()) base = "imported";
            File out = new File(Main.songsFolder, base + ".nbs");
            for (int i = 1; out.exists(); i++) out = new File(Main.songsFolder, base + "_" + i + ".nbs");
            song.save(out);   
            return out;
        } catch (Exception e) {
            Main.LOGGER.error("Failed to save imported NBS", e);
            return null;
        }
    }
}