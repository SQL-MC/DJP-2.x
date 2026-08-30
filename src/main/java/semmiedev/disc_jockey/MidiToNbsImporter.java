package semmiedev.disc_jockey;

import javax.sound.midi.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MidiToNbsImporter {

    private static final int DEFAULT_BPM = 120;

    /** NBS key 合法范围 0-87, 对应 signedNoteId 范围 [-33, 54] */
    @SuppressWarnings("unused")
    private static final int SIGNED_MIN = -33;
    @SuppressWarnings("unused")
    private static final int SIGNED_MAX = 54;

    /** MIDI 音高基准: NBS key + 21 = MIDI note (NBSTool/NBStoMIDI 验证) */
    private static final int MIDI_TO_NBS_OFFSET = 21;

    /** MIDI PPQ（默认 480，若 sequence 有 division 则用它） */
    private static final int DEFAULT_PPQ = 480;

    /** Minecraft tick 速率 */
    private static final double NBS_TICKS_PER_SECOND = 20.0;

    public static Song importMidi(File midiFile) {
        if (midiFile == null || !midiFile.exists() || !midiFile.isFile()) return null;

        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(new FileInputStream(midiFile));
        } catch (Exception e) {
            return null;
        }

        double bpm = parseTempo(sequence);
        int ppq = getPpq(sequence);
        // 1 NBS tick = tickRatio 个 MIDI PPQ tick
        double tickRatio = computeTickRatio(bpm, ppq);

        // 只给"确实含 NOTE_ON"的有效 track 编连续 layer
        List<Track> usable = new ArrayList<>();
        for (Track t : sequence.getTracks()) if (hasNoteOn(t)) usable.add(t);

        List<NoteEvent> events = new ArrayList<>();

        for (int ti = 0; ti < usable.size(); ti++) {
            Track track = usable.get(ti);
            int layer = ti;

            // ★ 从 track 里提取 channel 和"最后一个 GM program"
            int channel = -1;
            int gmProgram = 0;   // 默认 piano(0)
            for (int i = 0; i < track.size(); i++) {
                MidiEvent me = track.get(i);
                if (me.getMessage() instanceof ShortMessage sm) {
                    if (channel < 0) channel = sm.getChannel();
                    if (sm.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                        gmProgram = sm.getData1();   // ★ 持续更新，覆盖 track 内所有 program change
                    }
                }
            }

            boolean isDrum = (channel == 9);
            // ★ 鼓通道若没有 program change，强制指向鼓组 program(128 标记)
            final int resolvedProgram = isDrum ? (gmProgram == 0 ? 128 : gmProgram) : gmProgram;
            final int nbsInstrument = mapGmToNbs(resolvedProgram, isDrum);

            for (int i = 0; i < track.size(); i++) {
                MidiEvent me = track.get(i);                          // ★ MidiEvent 变量
                if (me.getMessage() instanceof ShortMessage sm) {
                    if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        events.add(new NoteEvent(
                                me.getTick(), layer, nbsInstrument, // ✅ me.getTick()，不是 sm.getTick()
                                sm.getData1(), sm.getData2()));
                    }
                }
            }
        }

        if (events.isEmpty()) return null;

        // ★ (tick, layer) 排序, 保证 jump 编码单调递增
        events.sort(Comparator.comparingLong((NoteEvent e) -> e.tick)
                .thenComparingInt(e -> e.layer));

        List<Long> packed = new ArrayList<>();
        int maxTick = 0, maxLayer = 0;

        for (NoteEvent evt : events) {
            // —— 1) MIDI note → NBS key (含乐器八度偏移) ——
            int nbsKey = evt.note - MIDI_TO_NBS_OFFSET;   // midiNote - 21

            // ★ 乐器八度偏移：evt.instrument 现在是 NBS instrument 索引(0-19)，分支正确命中
            switch (evt.instrument) {
                case 1:  nbsKey += 24; break;  // BASS: 低2八度 → 导入 +24 补偿
                case 11: nbsKey += 24; break;  // DIDGERIDOO: 低音 → +24
                case 12: nbsKey += 24; break;  // BIT: 低音 → +24
                case 5:  nbsKey += 12; break;  // GUITAR: 低1八度 → +12
                case 6:  nbsKey -= 12; break;  // FLUTE: 高1八度 → -12
                case 8:  nbsKey -= 12; break;  // CHIME: 高1八度 → -12
                case 7:  nbsKey -= 24; break;  // BELL: 高2八度 → -24
                case 9:  nbsKey -= 24; break;  // XYLOPHONE: 高2八度 → -24
                case 10: nbsKey -= 24; break;  // IRON_XYLOPHONE: 高2八度 → -24
                case 13: nbsKey -= 24; break;  // COW_BELL: 高2八度 → -24
                case 16: case 17: case 18: case 19: nbsKey -= 24; break; // TRUMPET 系: 高2八度
                // 0(HARP), 2(BASEDRUM), 3(SNARE), 4(HAT), 14(BANJO), 15(PLING): 不偏移
            }

            // 八度折叠到 NBS 合法范围 0-87
            while (nbsKey < 0) nbsKey += 12;
            while (nbsKey > 87) nbsKey -= 12;

            int signedNoteId = nbsKey - 33;   // 与 Song.save(+33) / Note.packNoteId 对称

            // —— 2) tick: MIDI PPQ → NBS 游戏tick ——
            int nbsTick = (tickRatio > 0)
                    ? (int) Math.round(evt.tick / tickRatio)
                    : (int) evt.tick;
            if (nbsTick < 0) nbsTick = 0;
            if (nbsTick > 0xFFFF) nbsTick = 0xFFFF;   // ★ 防止 short 溢出

            packed.add(packNote(nbsTick, evt.layer, evt.instrument, signedNoteId));

            if (nbsTick > maxTick) maxTick = nbsTick;
            if (evt.layer > maxLayer) maxLayer = evt.layer;
        }

        if (packed.isEmpty()) return null;

        long[] notesArray = packed.stream().mapToLong(Long::longValue).toArray();

        // ★ Tempo: MIDI BPM → NBS tempo (与导出互逆: bpm = tempo/100*15)
        int tempoInt = (int) Math.max(0, Math.min(Short.MAX_VALUE, bpm / 15.0 * 100));
        short safeTempo  = (short) tempoInt;
        short safeLength = (short) Math.max(0, Math.min(Short.MAX_VALUE, maxTick + 1));
        short safeHeight = (short) Math.max(1, Math.min(Short.MAX_VALUE, maxLayer + 1));

        Song song = new Song();
        song.notes = notesArray;
        song.foldedNotes = null;
        song.length = safeLength;
        song.height = safeHeight;
        song.tempo = safeTempo;
        song.loopStartTick = 0;

        song.fileName = midiFile.getName();
        song.displayName = stripExt(midiFile.getName());
        song.name = song.displayName;
        song.author = "Imported";
        song.originalAuthor = "";
        song.description = "";
        song.importFileName = midiFile.getName();

        // ★ 走「新格式」分支, 与 Song.save 默认 (vanillaInstrumentCount=20≠0) 一致
        song.formatVersion = 0;
        song.vanillaInstrumentCount = (byte) Note.INSTRUMENTS.length;  // = 20
        song.autoSaving = 0;
        song.autoSavingDuration = 0;
        song.timeSignature = 4;
        song.loop = 0;
        song.maxLoopCount = 0;

        song.minutesSpent = 0;
        song.leftClicks = 0;
        song.rightClicks = 0;
        song.blocksAdded = 0;
        song.blocksRemoved = 0;
        song.entry = null;

        song.searchableFileName = song.fileName.toLowerCase();
        song.searchableName = song.displayName.toLowerCase();
        return song;
    }

    /**
     * GM program → NBS instrument 映射
     * @param gmProgram 0-127 GM program；鼓组用 128 表示"未指定鼓"
     * @param isDrum 是否鼓机通道 (channel 10 / index 9)
     */
    private static int mapGmToNbs(int gmProgram, boolean isDrum) {
        if (isDrum) {
            // 鼓通道一律 BASEDRUM(1)，偏移 +24 低音区（简单可靠）
            return 1;
        }
        if (gmProgram >= 0  && gmProgram <= 7)  return 0;   // HARP (钢琴/电钢)
        if (gmProgram >= 8  && gmProgram <= 15) return 0;   // HARP
        if (gmProgram >= 16 && gmProgram <= 23) return 0;   // HARP/手风琴
        if (gmProgram >= 24 && gmProgram <= 31) return 5;   // GUITAR
        if (gmProgram >= 32 && gmProgram <= 39) return 1;   // BASS
        if (gmProgram >= 40 && gmProgram <= 55) return 0;   // HARP (弦乐/竖琴)
        if (gmProgram >= 56 && gmProgram <= 63) return 16;  // TRUMPET
        if (gmProgram >= 64 && gmProgram <= 71) return 6;   // FLUTE(近似, 簧片)
        if (gmProgram >= 72 && gmProgram <= 79) return 6;   // FLUTE (笛/哨)
        if (gmProgram >= 80 && gmProgram <= 95) return 15;  // PLING (合成主音)
        if (gmProgram >= 96 && gmProgram <= 119) return 0;  // HARP (合成垫/音效)
        if (gmProgram >= 112 && gmProgram <= 127) return 2; // BASEDRUM (打击)
        return 0; // 默认 HARP
    }

    /**
     * PPQ → 游戏tick 转换系数
     * nbsTick = midiTick / tickRatio
     */
    private static double computeTickRatio(double bpm, int ppq) {
        double beatsPerSecond = bpm / 60.0;
        // 1 秒 = bpm/60 拍 = bpm/60 * ppq 个 MIDI tick
        // 1 秒 = 20 个 NBS tick
        // → 1 NBS tick = (bpm/60 * ppq) / 20 个 MIDI tick
        return (beatsPerSecond * ppq) / NBS_TICKS_PER_SECOND;
    }

    private static int getPpq(Sequence sequence) {
        try {
            if (sequence.getDivisionType() == Sequence.PPQ) {
                return sequence.getResolution();
            }
        } catch (Exception ignored) {}
        return DEFAULT_PPQ;
    }

    private static String stripExt(String name) {
        return name == null ? "" : name.replaceAll("\\.(?i)midi?$", "");
    }

    private static boolean hasNoteOn(Track track) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage sm) {
                if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) return true;
            }
        }
        return false;
    }

    /**
     * packNote —— 位域严格对齐 Note.java:
     *   bit 0-15  : tick        (16位)
     *   bit 16-31 : layer       (16位, = LAYER_SHIFT)
     *   bit 32-39 : instrument  (8位,  = INSTRUMENT_SHIFT)
     *   bit 40-47 : note        (8位,  = NOTE_SHIFT, 由 Note.packNoteId 写入)
     *
     * ★ 注意: instrument 必须已是 NBS instrument 索引(0-19)，不是 GM program
     */
    private static long packNote(int tick, int layer, int instrument, int signedNoteId) {
        long packed = ((long) tick       & 0xFFFFL)        // bit  0-15
                | ((long) layer          & 0xFFFFL) << 16  // bit 16-31
                | ((long) instrument     & 0xFFL)   << 32; // bit 32-39
        return Note.packNoteId(packed, signedNoteId);        // bit 40-47
    }

    private static int parseTempo(Sequence sequence) {
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof MetaMessage mm && mm.getType() == 0x51) {
                    byte[] d = mm.getData();
                    int uspq = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
                    return (int) Math.round(6.0E7D / uspq);
                }
            }
        }
        return DEFAULT_BPM;
    }

    private static class NoteEvent {
        final long tick;
        final int layer;
        final int instrument;  // ★ 此处存的是 NBS instrument 索引 (0-19)
        final int note;        // MIDI note (0-127)
        final int velocity;
        NoteEvent(long tick, int layer, int instrument, int note, int velocity) {
            this.tick = tick; this.layer = layer;
            this.instrument = instrument; this.note = note; this.velocity = velocity;
        }
    }
}
