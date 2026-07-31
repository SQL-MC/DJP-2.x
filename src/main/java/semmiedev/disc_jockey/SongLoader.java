package semmiedev.disc_jockey;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import semmiedev.disc_jockey.gui.SongListWidget;

public class SongLoader {
    public static final ArrayList<Song> SONGS = new ArrayList<>();
    public static final ArrayList<String> SONG_SUGGESTIONS = new ArrayList<>();
    public static volatile boolean loadingSongs;
    public static volatile boolean showToast;

    public static void loadSongs() {
        if (loadingSongs) return;
        new Thread(() -> {
            try {
                loadingSongs = true;
                SONGS.clear();
                SONG_SUGGESTIONS.clear();
                SONG_SUGGESTIONS.add("Songs are loading, please wait");

                File[] files = Main.songsFolder.listFiles();
                if (files != null) {
                    for (File file : files) {
                        Song song = null;
                        try {
                            song = loadSong(file);
                        } catch (Exception exception) {
                            Main.LOGGER.error("Unable to read or parse song {}", file.getName(), exception);
                        }
                        if (song != null) SONGS.add(song);
                    }
                }

                for (Song song : SONGS)
                    SONG_SUGGESTIONS.add(song.displayName);

                Main.config.favorites.removeIf(f ->
                        SONGS.stream().noneMatch(s ->
                                s.displayName.equalsIgnoreCase(f) ||
                                s.fileName.equalsIgnoreCase(f)
                        )
                );

                if (showToast && Minecraft.getInstance().font != null)
                    SystemToast.add(
                            Minecraft.getInstance().gui.toastManager(),
                            SystemToast.SystemToastId.PACK_LOAD_FAILURE,
                            Main.NAME,
                            Component.translatable("disc_jockey.loading_done")
                    );
                showToast = true;
            } catch (Exception e) {
                Main.LOGGER.error("Failed to load songs", e);
            } finally {
                loadingSongs = false;
            }
        }).start();
    }

    public static Song loadSong(File file) throws IOException {
        if (!file.isFile()) return null;

        BinaryReader reader = new BinaryReader(Files.newInputStream(file.toPath()));
        Song song = new Song();

        song.fileName = file.getName().replaceAll("[\\n\\r]", "");

        // ===== NBS Header =====
        int length = reader.readShort() & 0xFFFF;
        boolean newFormat = (length == 0);

        if (newFormat) {
            song.formatVersion = reader.readByte();
            song.vanillaInstrumentCount = reader.readByte();
            length = reader.readShort() & 0xFFFF;
        }

        song.length = (short) length;
        song.height = reader.readShort();
        song.name = reader.readString().replaceAll("[\\n\\r]", "");
        song.author = reader.readString().replaceAll("[\\n\\r]", "");
        song.originalAuthor = reader.readString().replaceAll("[\\n\\r]", "");
        song.description = reader.readString().replaceAll("[\\n\\r]", "");
        song.tempo = reader.readShort();
        song.autoSaving = reader.readByte();
        song.autoSavingDuration = reader.readByte();
        song.timeSignature = reader.readByte();
        song.minutesSpent = reader.readInt();
        song.leftClicks = reader.readInt();
        song.rightClicks = reader.readInt();
        song.blocksAdded = reader.readInt();
        song.blocksRemoved = reader.readInt();
        song.importFileName = reader.readString().replaceAll("[\\n\\r]", "");

        if (newFormat) {
            song.loop = reader.readByte();
            song.maxLoopCount = reader.readByte();
            song.loopStartTick = reader.readShort();
        }

        song.displayName = song.name.isEmpty()
                ? song.fileName
                : (song.name + " (" + song.fileName + ")");
        song.entry = new SongListWidget.SongEntry(song, SONGS.size());
        song.entry.favorite = Main.config.favorites.contains(song.fileName);
        song.searchableFileName = song.fileName.toLowerCase().replaceAll("\\s", "");
        song.searchableName = song.name.toLowerCase().replaceAll("\\s", "");

        // ===== NBS NOTE PARSING（纯净版：只做 -33，不做折叠，不做钳制）=====
        int tick = -1;
        int jump;

        while ((jump = reader.readShort() & 0xFFFF) != 0) {
            tick += jump;

            int layer = -1;
            while ((jump = reader.readShort() & 0xFFFF) != 0) {
                layer += jump;

                int instrumentId = reader.readByte() & 0xFF;
                int noteIdRaw = reader.readByte() & 0xFF;

                // ✅ NBS 规范：33 = Minecraft F#0，不做任何折叠/钳制
                int noteId = noteIdRaw - 33;

                if (newFormat) {
                    reader.readByte(); // velocity
                    reader.readByte(); // panning
                    reader.readShort(); // pitch
                }

                Note note = new Note(
                        Note.INSTRUMENTS[instrumentId],
                        (byte) noteId
                );

                if (!song.uniqueNotes.contains(note))
                    song.uniqueNotes.add(note);

                song.notes = Arrays.copyOf(song.notes, song.notes.length + 1);
                song.notes[song.notes.length - 1] =
                        ((long) tick)
                                | ((long) layer << 16)
                                | ((long) instrumentId << 32)
                                | ((long) (noteId & 0xFF) << 40);
            }
        }

        return song;
    }

    public static void sort() {
        SONGS.sort(Comparator.comparing(song -> song.displayName));
    }
}