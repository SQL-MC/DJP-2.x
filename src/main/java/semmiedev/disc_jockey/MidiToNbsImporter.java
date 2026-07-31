package semmiedev.disc_jockey;

import javax.sound.midi.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ✅ MIDI → NBS 反向导入器（逻辑修复版）
 * ✅ 文件不存在 / MIDI 解析失败 / 无音符 → 返回 null
 * ✅ 不修改 Song.java
 * ✅ 不触碰 Note 构造函数
 * ✅ JDK 25 / Gradle 9.6.1 编译安全
 */
public class MidiToNbsImporter {

    private static final int DEFAULT_BPM = 120;
    private static final int MAX_SHORT = 32767;
    private static final int MIN_SHORT = -32768;

    /**
     * @return 成功返回 Song；失败 / 无音符 / 文件非法返回 null
     */
    public static Song importMidi(File midiFile) {
        if (midiFile == null || !midiFile.exists() || !midiFile.isFile()) {
            return null;
        }

        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(new FileInputStream(midiFile));
        } catch (Exception e) {
            // ✅ 解析失败，不抛异常，直接失败
            return null;
        }

        int bpm = parseTempo(sequence);

        List<NoteEvent> noteEvents = new ArrayList<>();
        int layerIndex = 0;

        for (Track track : sequence.getTracks()) {
            int layer = layerIndex++;
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof ShortMessage sm) {
                    if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        noteEvents.add(new NoteEvent(
                                event.getTick(),
                                layer,
                                0,
                                sm.getData1(),
                                sm.getData2()
                        ));
                    }
                }
            }
        }

        // ✅ 关键：MIDI 里一个音符都没有 → 失败
        if (noteEvents.isEmpty()) {
            return null;
        }

        List<Long> packedNotes = new ArrayList<>();
        for (NoteEvent evt : noteEvents) {
            int noteId = Math.max(0, Math.min(24, evt.note - 57));
            int safeTick = (int) Math.max(0, Math.min(Integer.MAX_VALUE, evt.tick));
            packedNotes.add(packNote(safeTick, evt.layer, evt.instrument, noteId));
        }

        long[] notesArray = packedNotes.stream().mapToLong(Long::longValue).toArray();

        short safeTempo = (short) Math.max(MIN_SHORT,
                Math.min(MAX_SHORT, bpm * 100));

        short safeLength = (short) Math.max(MIN_SHORT,
                Math.min(MAX_SHORT, calculateLength(notesArray)));

        Song song = new Song();

        song.notes = notesArray;
        song.foldedNotes = null;
        song.length = safeLength;
        song.height = 0;
        song.tempo = safeTempo;
        song.loopStartTick = 0;

        song.fileName = midiFile.getName();
        song.displayName = midiFile.getName().replaceAll("\\.mid$", "");
        song.name = song.displayName;
        song.author = "Imported";
        song.originalAuthor = "";
        song.description = "";
        song.importFileName = midiFile.getName();

        song.autoSaving = 0;
        song.autoSavingDuration = 0;
        song.timeSignature = 4;
        song.vanillaInstrumentCount = 16;
        song.formatVersion = 0;
        song.loop = 0;
        song.maxLoopCount = 0;

        song.minutesSpent = 0;
        song.leftClicks = 0;
        song.rightClicks = 0;
        song.blocksAdded = 0;
        song.blocksRemoved = 0;

        song.entry = null;

        song.searchableFileName = midiFile.getName().toLowerCase();
        song.searchableName = song.displayName.toLowerCase();

        return song;
    }

    /* =========================================================
       ✅ static 方法
       ========================================================= */
    private static int parseTempo(Sequence sequence) {
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof MetaMessage mm && mm.getType() == 0x51) {
                    byte[] d = mm.getData();
                    int uspq = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
                    return (int) (6.0E7D / uspq);
                }
            }
        }
        return DEFAULT_BPM;
    }

    private static long packNote(int tick, int layer, int instrument, int noteId) {
        return ((long) tick & 0xFFFFL)
                | ((long) layer & 0xFFFFL) << 16
                | ((long) instrument & 0xFFL) << 32
                | ((long) noteId & 0xFFL) << 40;
    }

    private static int calculateLength(long[] notes) {
        int max = 0;
        for (long packed : notes) {
            int tick = (int) (packed & 0xFFFFL);
            if (tick > max) max = tick;
        }
        return max;
    }

    private static class NoteEvent {
        final long tick;
        final int layer;
        final int instrument;
        final int note;
        final int velocity;

        NoteEvent(long tick, int layer, int instrument, int note, int velocity) {
            this.tick = tick;
            this.layer = layer;
            this.instrument = instrument;
            this.note = note;
            this.velocity = velocity;
        }
    }
}