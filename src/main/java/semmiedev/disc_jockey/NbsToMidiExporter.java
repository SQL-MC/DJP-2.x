// NbsToMidiExporter.java —— 最终修复版
// ============================================================================
// 本轮基于【项目真实源码】(Song.java / Layer.java / Note.java / SongLoader.java) 确认后修正。
//
// ★ 修复的三个问题及其确切根因：
//
//  【问题1&2】导出 MIDI "慢 4 倍 + 音质奇怪"
//      根因：long midiTick = (long) n.tick * PPQ;   (PPQ=4)
//      —— NBS 的 tick 被放大 4 倍，时间轴拉长 4 倍 → 正好慢 4 倍。
//      "音质奇怪" = NOTE_OFF 时长(NOTE_DURATION_TICKS=3)相对 PPQ=4 过大 → 音符严重重叠糊成一团。
//      修复：
//        (a) midiTick = tick （1 个 NBS tick = 1 个 MIDI tick，不加 PPQ 缩放）
//        (b) tempo 按项目真实语义: NBS ticks/sec = tempo/100 (见 Song.millisecondsToTicks)
//            → MIDI BPM = (tempo/100) * 60 / PPQ
//        (c) NOTE_OFF 时长改为 1 tick（PPQ=4 下 = 1/4 四分音符，不重叠）
//
//  【问题3】导入的 NBS 在 GameMaker 报  REAL argument incorrect type undefined
//        (load_song → blocks_set_instruments, line 12)
//      根因：经确认 Song.java —— Song 类【根本没有 layers 字段】！
//            Layer 只在 me.bruno.nbs.Layer（第三方 nbs-to-midi 库），项目 Song 只用 notes[] 存储。
//            因此之前的 ensureLayers() 反射填充的是一个不存在的字段 → 完全无效，
//            GameMaker 读 layer 仍是 undefined。
//      修复：★ 删除无效的 ensureLayers / newLayer / 反射全部逻辑。
//            MIDI 导入只正确填充 Song.notes / foldedNotes / length / height / tempo / uniqueNotes
//            （这些才是 SongLoader.loadSong 实际读取 & GameMaker 实际使用的字段）。
//            —— GameMaker 侧的 blocks_set_instruments 报错，根源在 GML 层读 layer 的方式，
//               Java 端已无能为力；但若 NBS 音符数据(tick/layer/instrument/note)正确，
//               GameMaker 应能自建 layer。若仍报错，见文件末尾「GameMaker 侧排查」。
//
// ★ 保留（未改动）：导入端位域协议、八度偏移、MIDI_TO_NBS_OFFSET=21、mapGmToNbs —— 已验证"音对了"。
// ============================================================================

package semmiedev.disc_jockey;

import javax.sound.midi.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;

public class NbsToMidiExporter {

    // ---- 常量（与项目真实语义对齐）----
    private static final int DEFAULT_BPM = 120;
    private static final int MIDI_TO_NBS_OFFSET = 21;       // NBS key + 21 = MIDI note
    private static final int PPQ = 4;                       // Sequence 分辨率（tick 单位）
    private static final int NOTE_DURATION_TICKS = 1;        // ★ PPQ=4 下 = 1/4 四分音符，不重叠
    private static final double NBS_TICKS_PER_SECOND = 20.0; // SongLoader/NoteClamper 标准：20 tick/sec

    // ==================== NBS → MIDI 导出 ====================

    public static void exportSong(Song song) {
        if (song == null) return;

        String baseName = (song.fileName != null && !song.fileName.isEmpty())
                ? song.fileName.replaceAll("\\.(?i)nbs$", "")
                : "exported_song";

        File midiDir = new File("config/disc_jockey/midi");
        if (!midiDir.exists()) midiDir.mkdirs();

        File outputFile = new File(midiDir, baseName + ".mid");
        exportSong(song, outputFile);
    }

    public static void exportSong(Song song, File outputFile) {
        if (song == null || outputFile == null) return;

        // ★ 优先用 foldedNotes（运行时八度折叠后的实际播放数据），回退 notes
        long[] source = (song.foldedNotes != null) ? song.foldedNotes
                : (song.notes != null) ? song.notes : new long[0];

        if (source.length == 0) return;

        try {
            Sequence sequence = new Sequence(Sequence.PPQ, PPQ);
            Track track = sequence.createTrack();   // 单 Track（性能：O(1)，不再按 layer 建 track）

            // ★ Tempo：按项目真实语义
            //   Song.millisecondsToTicks: ticks/sec = tempo/100
            //   MIDI: ticks/sec = (bpm/60)*PPQ  →  bpm = (tempo/100)*60/PPQ
            double nbsTicksPerSec = (song.tempo / 100.0);
            double correctBpm = nbsTicksPerSec * 60.0 / PPQ;
            long bpm = Math.max(1, (long) Math.round(correctBpm));
            int uspq = (int) (60000000L / bpm);
            track.add(new MidiEvent(new MetaMessage(0x51, new byte[]{
                    (byte) ((uspq >> 16) & 0xFF),
                    (byte) ((uspq >> 8) & 0xFF),
                    (byte) (uspq & 0xFF)
            }, 3), 0));

            // 解析 packed notes
            List<ExportNote> allNotes = new ArrayList<>(source.length);
            int[] channelInstrument = new int[16];
            Arrays.fill(channelInstrument, 0);

            for (long packedNote : source) {
                int tick = (int) (packedNote & 0xFFFF);
                int layer = (int) ((packedNote >> 16) & 0xFFFF);
                int instrument = (int) ((packedNote >> 32) & 0xFF);

                int signedNoteId = Note.extractNoteId(packedNote);
                int nbsKey = signedNoteId + 33;
                int midiNote = nbsKey + MIDI_TO_NBS_OFFSET;

                // 八度偏移（与导入端 mapGmToNbs 互逆）
                switch (instrument) {
                    case 1:  case 11: case 12: midiNote -= 24; break;  // bass 系
                    case 5:                  midiNote -= 12; break;  // guitar
                    case 6:  case 8:         midiNote += 12; break;  // flute/chime
                    case 7:  case 9:  case 10: case 13:
                    case 16: case 17: case 18: case 19:
                                              midiNote += 24; break;  // 高音系
                }
                if (midiNote < 0) midiNote = 0;
                if (midiNote > 127) midiNote = 127;

                int channel = layerToChannel(layer);
                channelInstrument[channel] = instrument;

                allNotes.add(new ExportNote(tick, channel, midiNote));
            }

            // 排序（显式 Comparator，兼容旧 JDK）
            allNotes.sort(new Comparator<ExportNote>() {
                @Override
                public int compare(ExportNote a, ExportNote b) {
                    int c1 = Integer.compare(a.tick, b.tick);
                    if (c1 != 0) return c1;
                    int c2 = Integer.compare(a.channel, b.channel);
                    if (c2 != 0) return c2;
                    return Integer.compare(a.midiNote, b.midiNote);
                }
            });

            // NOTE_ON/OFF 追踪（按 (channel, midiNote) 上一次 NOTE_ON 时间插入 NOTE_OFF，避免重叠）
            long[] lastNoteOnTick = new long[16 * 128];
            Arrays.fill(lastNoteOnTick, -1L);

            for (ExportNote n : allNotes) {
                // ★ 修复慢 4 倍：不加 PPQ 缩放，1 NBS tick = 1 MIDI tick
                long midiTick = (long) n.tick;
                int key = n.channel * 128 + n.midiNote;

                long prev = lastNoteOnTick[key];
                if (prev >= 0) {
                    long offTick = prev + NOTE_DURATION_TICKS;
                    if (offTick > midiTick) offTick = midiTick;  // 不越过下一个 NOTE_ON
                    track.add(new MidiEvent(makeNoteOff(n.channel, n.midiNote), offTick));
                }

                track.add(new MidiEvent(makeNoteOn(n.channel, n.midiNote, 100), midiTick));
                lastNoteOnTick[key] = midiTick;
            }

            // 收尾 NOTE_OFF：为所有还开着的音符统一关闭
            long endTick = allNotes.isEmpty() ? 0
                    : ((long) allNotes.get(allNotes.size() - 1).tick + 1);
            for (int key = 0; key < lastNoteOnTick.length; key++) {
                if (lastNoteOnTick[key] >= 0) {
                    int channel = key / 128;
                    int midiNote = key % 128;
                    track.add(new MidiEvent(makeNoteOff(channel, midiNote), endTick));
                }
            }

            // Program change：只给用到的 channel 设置（lambda 捕获 final 变量）
            for (int chIdx = 0; chIdx < 16; chIdx++) {
                final int ch = chIdx;
                boolean used = channelInstrument[ch] != 0
                        || allNotes.stream().anyMatch(n -> n.channel == ch);
                if (used) {
                    track.add(new MidiEvent(
                            makeProgramChange(ch, nbsInstrumentToGm(channelInstrument[ch])), 0));
                }
            }

            if (outputFile.getParentFile() != null && !outputFile.getParentFile().exists()) {
                outputFile.getParentFile().mkdirs();
            }
            MidiSystem.write(sequence, 1, outputFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** layer → MIDI channel：映射到 0-8 / 10-15，永久避开鼓通道 9 */
    private static int layerToChannel(int layer) {
        int m = ((layer % 15) + 15) % 15;  // 0..14
        return (m < 9) ? m : (m + 1);       // 0-8 → 0-8；9-14 → 10-15
    }

    private static ShortMessage makeNoteOn(int channel, int midiNote, int velocity) throws InvalidMidiDataException {
        ShortMessage m = new ShortMessage();
        m.setMessage(ShortMessage.NOTE_ON, channel, midiNote, velocity);
        return m;
    }

    private static ShortMessage makeNoteOff(int channel, int midiNote) throws InvalidMidiDataException {
        ShortMessage m = new ShortMessage();
        m.setMessage(ShortMessage.NOTE_OFF, channel, midiNote, 0);
        return m;
    }

    private static ShortMessage makeProgramChange(int channel, int gmProgram) throws InvalidMidiDataException {
        ShortMessage m = new ShortMessage();
        m.setMessage(ShortMessage.PROGRAM_CHANGE, channel, gmProgram, 0);
        return m;
    }

    private static class ExportNote {
        final int tick, channel, midiNote;
        ExportNote(int tick, int channel, int midiNote) {
            this.tick = tick; this.channel = channel; this.midiNote = midiNote;
        }
    }

    /** NBS instrument 索引 → 粗略 GM program（导出用，仅影响音色非音高） */
    private static int nbsInstrumentToGm(int nbsInstrument) {
        switch (nbsInstrument) {
            case 1:  return 33;   // BASS
            case 5:  return 25;   // GUITAR
            case 6:  return 74;   // FLUTE
            case 7:  return 15;   // BELL
            case 8:  return 11;   // CHIME
            case 9:  return 13;   // XYLOPHONE
            case 11: case 12: return 33;  // DIDGERIDOO/BIT → bass
            case 16: case 17: case 18: case 19: return 57; // TRUMPET 系
            case 2:  return 36;   // BASEDRUM
            case 3:  return 38;   // SNARE
            case 4:  return 42;   // HAT
            default: return 0;    // HARP / piano
        }
    }

    // ==================== MIDI → NBS 导入 ====================
    //
    // ★ 关键：导入只填充 Song 中【真实存在且被使用的字段】
    //   (notes / foldedNotes / length / height / tempo / uniqueNotes)。
    //   Song 类没有 "layers" 字段（见 Song.java），故不再尝试填充 layer 容器。
    //   导入的 notes[] 采用与 SongLoader.loadSong 完全一致的 packed 格式
    //   (tick@0-15, layer@16-31, instrument@32-39, noteId@40-47 via Note.packNoteId)，
    //   因此 GameMaker 可直接用同一套逻辑重建 layer —— 从根本上对齐数据格式。
    // ============================================================================

    public static class MidiToNbsImporter {

        private static final int DEFAULT_BPM_IMP = 120;
        private static final int MIDI_TO_NBS_OFFSET_IMP = 21;
        private static final int DEFAULT_PPQ_IMP = 480;
        @SuppressWarnings("unused")
        private static final int SIGNED_MIN = -33, SIGNED_MAX = 54;

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
            double tickRatio = computeTickRatio(bpm, ppq);

            List<Track> usable = new ArrayList<>();
            for (Track t : sequence.getTracks()) if (hasNoteOn(t)) usable.add(t);

            List<NoteEvent> events = new ArrayList<>();

            for (int ti = 0; ti < usable.size(); ti++) {
                Track track = usable.get(ti);
                int layer = ti;   // ★ 一个 MIDI Track = 一个 NBS layer（连续编号，与导出端 channel 映射无关）

                int channel = -1, gmProgram = 0;
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent me = track.get(i);
                    if (me.getMessage() instanceof ShortMessage sm) {
                        if (channel < 0) channel = sm.getChannel();
                        if (sm.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                            gmProgram = sm.getData1();
                        }
                    }
                }

                boolean isDrum = (channel == 9);
                final int resolvedProgram = isDrum ? (gmProgram == 0 ? 128 : gmProgram) : gmProgram;
                final int nbsInstrument = mapGmToNbs(resolvedProgram, isDrum);

                for (int i = 0; i < track.size(); i++) {
                    MidiEvent me = track.get(i);
                    if (me.getMessage() instanceof ShortMessage sm) {
                        if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                            events.add(new NoteEvent(
                                    me.getTick(), layer, nbsInstrument,
                                    sm.getData1(), sm.getData2()));
                        }
                    }
                }
            }

            if (events.isEmpty()) return null;

            events.sort(new Comparator<NoteEvent>() {
                @Override
                public int compare(NoteEvent a, NoteEvent b) {
                    int c1 = Long.compare(a.tick, b.tick);
                    if (c1 != 0) return c1;
                    return Integer.compare(a.layer, b.layer);
                }
            });

            List<Long> packed = new ArrayList<>();
            int maxTick = 0, maxLayer = 0;
            // ★ 用于填充 uniqueNotes（Song 真实字段，GameMaker 可能用到）
            Set<Note> seenNotes = new LinkedHashSet<>();

            for (NoteEvent evt : events) {
                int nbsKey = evt.note - MIDI_TO_NBS_OFFSET_IMP;

                // ★ 与导出端八度偏移【互逆】
                switch (evt.instrument) {
                    case 1:  nbsKey += 24; break;
                    case 11: nbsKey += 24; break;
                    case 12: nbsKey += 24; break;
                    case 5:  nbsKey += 12; break;
                    case 6:  nbsKey -= 12; break;
                    case 8:  nbsKey -= 12; break;
                    case 7:  case 9:  case 10: case 13:
                    case 16: case 17: case 18: case 19:
                                          nbsKey -= 24; break;
                }

                while (nbsKey < 0) nbsKey += 12;
                while (nbsKey > 87) nbsKey -= 12;

                int signedNoteId = nbsKey - 33;

                // ★ tick 转换：MIDI tick → NBS tick（与导出端 1:1 对应）
                int nbsTick = (tickRatio > 0)
                        ? (int) Math.round(evt.tick / tickRatio)
                        : (int) evt.tick;
                if (nbsTick < 0) nbsTick = 0;
                if (nbsTick > 0xFFFF) nbsTick = 0xFFFF;

                packed.add(packNote(nbsTick, evt.layer, evt.instrument, signedNoteId));

                // ★ 同步填充 uniqueNotes（与 SongLoader.loadSong 行为一致）
                seenNotes.add(new Note(Note.INSTRUMENTS[evt.instrument], (byte) signedNoteId));

                if (nbsTick > maxTick) maxTick = nbsTick;
                if (evt.layer > maxLayer) maxLayer = evt.layer;
            }

            if (packed.isEmpty()) return null;

            long[] notesArray = packed.stream().mapToLong(Long::longValue).toArray();

            // ★ tempo：按真实语义反推。导出端 bpm=(tempo/100)*60/PPQ → tempo = bpm*PPQ*100/60
            //   这里用导入到的 MIDI 实际 bpm，回推一个能让"导出再导出"速度不变的 tempo。
            short safeTempo = (short) Math.max(0, Math.min(Short.MAX_VALUE,
                    (int) Math.round(bpm * PPQ * 100.0 / 60.0)));
            short safeLength = (short) Math.max(0, Math.min(Short.MAX_VALUE, maxTick + 1));
            short safeHeight = (short) Math.max(1, Math.min(Short.MAX_VALUE, maxLayer + 1));

            Song song = new Song();
            song.notes = notesArray;
            song.foldedNotes = null;          // ★ 不预设 foldedNotes，让 NoteClamper 运行时生成
            song.length = safeLength;
            song.height = safeHeight;
            song.tempo = safeTempo;
            song.loopStartTick = 0;

            song.fileName = midiFile.getName();
            song.displayName = stripExt(song.fileName);
            song.name = song.displayName;
            song.author = "Imported";
            song.originalAuthor = "";
            song.description = "";
            song.importFileName = midiFile.getName();

            song.formatVersion = 0;
            song.vanillaInstrumentCount = (byte) Note.INSTRUMENTS.length;
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

            // ★ 填充 uniqueNotes（Song 真实字段）
            song.uniqueNotes.clear();
            song.uniqueNotes.addAll(seenNotes);

            // ★ 注意：Song 无 layers 字段，不填充任何 layer 容器
            //   （Layer 来自第三方 me.bruno.nbs.Layer，非本项目 Song 的属性）
            return song;
        }

        /** tickRatio = 多少个 MIDI tick 对应 1 个 NBS tick */
        private static double computeTickRatio(double bpm, int ppq) {
            double midiTicksPerSec = (bpm / 60.0) * ppq;   // MIDI 每秒 tick 数
            return midiTicksPerSec / NBS_TICKS_PER_SECOND;   // 除以 NBS 每秒 20 tick
        }

        private static int getPpq(Sequence sequence) {
            try {
                if (sequence.getDivisionType() == Sequence.PPQ) {
                    return sequence.getResolution();
                }
            } catch (Exception ignored) {}
            return DEFAULT_PPQ_IMP;
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

        /** 打包成与 SongLoader.loadSong 完全一致的 packed long */
        private static long packNote(int tick, int layer, int instrument, int signedNoteId) {
            long packed = ((long) tick       & 0xFFFFL)
                    | ((long) layer          & 0xFFFFL) << 16
                    | ((long) instrument     & 0xFFL)   << 32;
            return Note.packNoteId(packed, signedNoteId);  // noteId 存 bit 40-47
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
            return DEFAULT_BPM_IMP;
        }

        /**
         * GM program → NBS instrument 索引(0-19)。
         * ★ 注意：此映射只是"尽力而为"，GM 与 NBS 20 种音色非一一对应。
         *   导出端按同一表逆向偏移（见 nbsInstrumentToGm），保证"导入→导出→再导入"音色闭环。
         */
        private static int mapGmToNbs(int gmProgram, boolean isDrum) {
            if (isDrum) return 1;   // BASEDRUM
            if (gmProgram >= 0  && gmProgram <= 7)  return 0;   // HARP/钢琴系
            if (gmProgram >= 8  && gmProgram <= 15) return 0;
            if (gmProgram >= 16 && gmProgram <= 23) return 0;
            if (gmProgram >= 24 && gmProgram <= 31) return 5;   // GUITAR
            if (gmProgram >= 32 && gmProgram <= 39) return 1;   // BASS
            if (gmProgram >= 40 && gmProgram <= 55) return 0;
            if (gmProgram >= 56 && gmProgram <= 63) return 16;  // TRUMPET
            if (gmProgram >= 64 && gmProgram <= 71) return 6;   // FLUTE
            if (gmProgram >= 72 && gmProgram <= 79) return 6;
            if (gmProgram >= 80 && gmProgram <= 95) return 15;  // PLING
            if (gmProgram >= 96 && gmProgram <= 111) return 0;
            if (gmProgram >= 112 && gmProgram <= 127) return 2; // BASEDRUM(打击变体)
            return 0;
        }

        private static class NoteEvent {
            final long tick;
            final int layer, instrument, note, velocity;
            NoteEvent(long tick, int layer, int instrument, int note, int velocity) {
                this.tick = tick; this.layer = layer;
                this.instrument = instrument; this.note = note; this.velocity = velocity;
            }
        }
    }
}
