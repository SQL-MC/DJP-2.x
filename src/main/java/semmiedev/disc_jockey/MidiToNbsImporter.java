package semmiedev.disc_jockey;

import javax.sound.midi.*;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * MIDI (.mid/.midi) -> NBS 导入器（Fabric 版）。
 *
 * 设计目标
 * --------
 * 1) 时间轴严格按 tickRatio 换算，并做去重、限幅、排序。
 * 2) 同一 tick 内多个同音高音符只保留一个（保留 velocity 最大的）。
 * 3) 音高从 MIDI(0..127) 映射到 NBS 的 signedNoteId：nbsKey = midi - 21，signedNoteId = nbsKey - 33。
 * 4) 乐器从 GM program 映射到 NBS 乐器号（0..15）。
 * 5) 保存前重建 uniqueNotes，保证音符盒数量检测正确。
 *
 * 时间基准
 * --------
 * NBS 标准速率为 10 tick/秒（tempo=1000 → secPerTick = 100.0/1000 = 0.1s）。
 * tickRatio 与 tempo 必须共用这一基准，否则会出现"歌曲被压缩、播放极快"。
 */
public final class MidiToNbsImporter {

    /** NBS 标准 tick 速率：tempo=1000 → secPerTick=100.0/1000=0.1s → 10 tick/秒 */
    private static final int NBS_TICKS_PER_SECOND = 10;

    /** 安全上限，防止异常 MIDI 导致 OOM */
    private static final int MAX_EVENTS = 500_000;

    /** NBS 允许的最小/最大 signedNoteId（对应 midi 21..108，即 A0..C8） */
    private static final int SIGNED_MIN = -33;
    private static final int SIGNED_MAX = 54;

    /**
     * 原版音符盒乐器数。
     * MC 26.3 音符盒已有 16 种乐器，NBS V4 表也是 16 个，
     * 因此这里为 16，让 0..15 全部合法（若为 10 会把 >=10 全部钳成 Harp）。
     */
    private static final int VANILLA_INSTRUMENT_COUNT = 16;

    /** 诊断输出（Fabric 版：控制台；NeoForge 版改为 LOGGER.info） */
    private static void log(String s) {
        System.out.println(s);
    }

    private MidiToNbsImporter() {}

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    public static Song importMidi(File file) {
        if (file == null || !file.isFile()) {
            log("[MidiToNbsImporter] 文件无效: " + file);
            return null;
        }

        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(file);
        } catch (InvalidMidiDataException | IOException e) {
            log("[MidiToNbsImporter] 解析失败: " + e);
            return null;
        }

        // ---- 1. 基本校验 ----
        if (sequence == null) {
            log("[MidiToNbsImporter] Sequence 为 null");
            return null;
        }

        float divisionType = sequence.getDivisionType();
        if (divisionType != Sequence.PPQ) {
            log("[MidiToNbsImporter] 不支持的 divisionType=" + divisionType + "，按 PPQ 继续尝试");
        }
        int division = sequence.getResolution();
        if (division <= 0) {
            log("[MidiToNbsImporter] 非法 division=" + division + "，回退 96");
            division = 96;
        }

        // ---- 2. 收集所有音符事件 ----
        Track[] tracks = sequence.getTracks();
        if (tracks == null || tracks.length == 0) {
            log("[MidiToNbsImporter] 无轨道");
            return null;
        }

        List<Track> usable = new ArrayList<>();
        for (Track t : tracks) {
            if (t == null) continue;
            for (int i = 0; i < t.size(); i++) {
                MidiEvent me = t.get(i);
                if (me != null && me.getMessage() instanceof ShortMessage sm
                        && sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                    usable.add(t);
                    break;
                }
            }
        }
        if (usable.isEmpty()) {
            log("[MidiToNbsImporter] 未找到任何音符");
            return null;
        }

        // 取 tempo（默认 120 BPM = 500000 us/quarter）
        int microsecondsPerQuarter = 500_000;
        for (Track t : tracks) {
            if (t == null) continue;
            boolean found = false;
            for (int i = 0; i < t.size(); i++) {
                MidiEvent me = t.get(i);
                if (me == null || !(me.getMessage() instanceof MetaMessage mm)) continue;
                if (mm.getType() == 0x51) { // Set Tempo
                    byte[] d = mm.getData();
                    if (d != null && d.length >= 3) {
                        int v = ((d[0] & 0xFF) << 16) | ((d[1] & 0xFF) << 8) | (d[2] & 0xFF);
                        if (v > 0) {
                            microsecondsPerQuarter = v;
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (found) break;
        }

        double quartersPerSecond = 1_000_000.0 / microsecondsPerQuarter;
        double ticksPerSecond = quartersPerSecond * division;   // MIDI tick / 秒
        if (ticksPerSecond <= 0) ticksPerSecond = NBS_TICKS_PER_SECOND;

        // 统一基准：先把 MIDI tick 按 10 tick/s 归一化为 NBS tick，
        // tempo 固定用标准值 1000，二者不可各算一套。
        double tickRatio = ticksPerSecond / NBS_TICKS_PER_SECOND;
        if (tickRatio <= 0) tickRatio = 1.0;
        int tempo = NBS_TICKS_PER_SECOND * 100;   // = 1000

        log("[MidiToNbsImporter] division=" + division
                + ", usPerQuarter=" + microsecondsPerQuarter
                + ", midiTicksPerSecond=" + String.format("%.2f", ticksPerSecond)
                + ", tickRatio=" + String.format("%.4f", tickRatio)
                + ", tempo=" + tempo);

        // ---- 3. 生成 RawEvent ----
        List<RawEvent> raws = new ArrayList<>();

        for (int ti = 0; ti < usable.size(); ti++) {
            Track track = usable.get(ti);

            // 通道取第一个 NOTE_ON；音色取最后一个 PROGRAM_CHANGE
            int channel = -1;
            int gmProgram = 0;
            boolean hasProgram = false;
            for (int i = 0; i < track.size(); i++) {
                MidiEvent me = track.get(i);
                if (me == null || !(me.getMessage() instanceof ShortMessage sm)) continue;
                if (sm.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                    gmProgram = sm.getData1();
                    hasProgram = true;
                } else if (sm.getCommand() == ShortMessage.NOTE_ON && channel < 0) {
                    channel = sm.getChannel();
                }
            }
            final boolean isDrum = (channel == 9);
            final int trackInstrument = clampToVanilla(mapGmToNbs(gmProgram));
            if (!hasProgram && !isDrum) {
                log("[MidiToNbsImporter] ⚠ track " + ti
                        + " 无 PROGRAM_CHANGE，按 GM 默认 program 0（钢琴）处理");
            }

            for (int i = 0; i < track.size(); i++) {
                MidiEvent me = track.get(i);
                if (me == null || !(me.getMessage() instanceof ShortMessage sm)) continue;
                if (sm.getCommand() != ShortMessage.NOTE_ON) continue;
                if (sm.getData2() <= 0) continue;

                int note = sm.getData1();
                int nbsTick = (int) Math.round(me.getTick() / tickRatio);
                if (nbsTick < 0) nbsTick = 0;
                if (nbsTick > Short.MAX_VALUE) nbsTick = Short.MAX_VALUE;

                // 鼓轨按音符号分乐器，旋律轨按轨道音色
                int inst = isDrum ? mapDrumNoteToNbs(note) : trackInstrument;

                raws.add(new RawEvent(nbsTick, ti, inst, note, sm.getData2()));

                if (raws.size() >= MAX_EVENTS) {
                    log("[MidiToNbsImporter] ⚠ 音符数超过上限 " + MAX_EVENTS + "，截断");
                    break;
                }
            }
            if (raws.size() >= MAX_EVENTS) break;
        }

        if (raws.isEmpty()) {
            log("[MidiToNbsImporter] 无有效音符");
            return null;
        }

        // ---- 4. 去重（同一 tick + 同一音高，保留 velocity 最大的）----
        Map<Long, RawEvent> dedup = new LinkedHashMap<>();
        for (RawEvent r : raws) {
            long k = ((long) r.tick) * 1000L + r.note;
            RawEvent old = dedup.get(k);
            if (old == null || r.velocity > old.velocity) dedup.put(k, r);
        }
        List<RawEvent> events = new ArrayList<>(dedup.values());
        events.sort(Comparator.comparingInt(a -> a.tick));

        // 诊断：乐器分布直方图
        Map<Integer, Integer> hist = new TreeMap<>();
        for (RawEvent e : events) hist.merge(e.instrument, 1, Integer::sum);
        StringBuilder sb = new StringBuilder("[MidiToNbsImporter] 乐器分布:");
        for (Map.Entry<Integer, Integer> en : hist.entrySet()) {
            sb.append("\n  [").append(en.getKey()).append("] ").append(nameOf(en.getKey()))
              .append(" x").append(en.getValue());
        }
        log(sb.toString());

        // ---- 5. 打包成 long[] ----
        if (events.isEmpty()) return null;

        long[] notes = new long[events.size()];
        int maxTick = 0;
        int maxLayer = 0;
        Map<Integer, Integer> lastLayerByTick = new HashMap<>();

        for (int idx = 0; idx < events.size(); idx++) {
            RawEvent e = events.get(idx);
            int tick = e.tick;
            int note = e.note;

            // 音高映射：nbsKey = midi - 21；signedNoteId = nbsKey - 33
            int nbsKey = note - 21;
            int signedNoteId = nbsKey - 33;
            if (signedNoteId < SIGNED_MIN) signedNoteId = SIGNED_MIN;
            if (signedNoteId > SIGNED_MAX) signedNoteId = SIGNED_MAX;

            int instrument = clampToVanilla(e.instrument);

            // layer：同一 tick 内递增
            int layer = lastLayerByTick.getOrDefault(tick, -1) + 1;
            if (layer < 0) layer = 0;
            if (layer > 255) layer = 255;
            lastLayerByTick.put(tick, layer);

            long p = pack(tick, layer, instrument);
            p = Note.packNoteId(p, signedNoteId);
            notes[idx] = p;

            if (tick > maxTick) maxTick = tick;
            if (layer > maxLayer) maxLayer = layer;
        }

        // ---- 6. 组装 Song ----
        Song song = new Song();
        song.notes = notes;
        song.foldedNotes = null;
        song.length = (short) Math.max(0, Math.min(Short.MAX_VALUE, maxTick + 1));
        song.height = (short) Math.max(1, Math.min(Short.MAX_VALUE, maxLayer + 1));
        song.tempo = (short) Math.max(1, Math.min(Short.MAX_VALUE, tempo));
        song.loopStartTick = 0;

        String baseName = file.getName();
        if (baseName.toLowerCase().endsWith(".mid")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        } else if (baseName.toLowerCase().endsWith(".midi")) {
            baseName = baseName.substring(0, baseName.length() - 5);
        }
        song.fileName = baseName + ".nbs";
        song.displayName = baseName;
        song.name = baseName;
        song.searchableFileName = song.fileName.toLowerCase(Locale.ROOT);
        song.searchableName = baseName.toLowerCase(Locale.ROOT);
        song.author = "Disc Jockey Plus";
        song.originalAuthor = "";
        song.description = "";
        song.entry = null;

        // NBS v4 才有 16 种乐器表；v0 只定义 10 种，10-15 会被读回时钳成 Harp
        song.formatVersion = 4;
        song.vanillaInstrumentCount = (byte) VANILLA_INSTRUMENT_COUNT;

        // ---- 7. 重建 uniqueNotes（(instrument, noteId) 去重）----
        song.uniqueNotes.clear();
        Set<Long> seen = new LinkedHashSet<>();
        for (long p : song.notes) {
            int instrument = (int) ((p >>> 32) & 0xFFL);
            int noteId = Note.extractNoteId(p);
            long k = ((long) instrument << 8) | (noteId & 0xFF);
            if (seen.add(k)) {
                song.uniqueNotes.add(new Note(Note.INSTRUMENTS[instrument], (byte) noteId));
            }
        }

        // ---- 8. 时长自检 ----
        double midiSeconds = sequence.getMicrosecondLength() / 1_000_000.0;
        double nbsSeconds = song.length * (100.0 / song.tempo);
        log("[MidiToNbsImporter] 时长自检: MIDI=" + String.format("%.2f", midiSeconds)
                + "s, NBS=" + String.format("%.2f", nbsSeconds) + "s");
        if (midiSeconds > 0.5 && (nbsSeconds < midiSeconds * 0.5 || nbsSeconds > midiSeconds * 2.0)) {
            log("[MidiToNbsImporter] ⚠ 时长偏差过大，请检查 tickRatio 换算");
        }

        log("[MidiToNbsImporter] ✓ 导入完成: " + song.fileName
                + " | 音符 " + notes.length
                + " | ticks=" + song.length
                + " | tempo=" + song.tempo);
        return song;
    }

    // ------------------------------------------------------------------
    // 音高 / 乐器映射
    // ------------------------------------------------------------------

    /** tick | layer<<16 | instrument<<32（instrument 只允许 0..15） */
    private static long pack(int tick, int layer, int instrument) {
        int inst = Math.max(0, Math.min(15, instrument));
        return ((long) tick & 0xFFFFL)
                | (((long) layer & 0xFFFFL) << 16)
                | (((long) inst & 0xFFL) << 32);
    }

    private static int clampToVanilla(int instrument) {
        return (instrument < 0 || instrument >= VANILLA_INSTRUMENT_COUNT) ? 0 : instrument;
    }

    /**
     * GM1 音色号 (0-127) → NBS 乐器号 (0-15)。
     * GM1 规范每族 8 个音色，共 16 族。
     */
    private static int mapGmToNbs(int gmProgram) {
        if (gmProgram < 0 || gmProgram > 127) return 0;

        // 个别音色单独指定，比整族划分更准
        switch (gmProgram) {
            case 10: case 11:            return 8;   // 音乐盒 / 颤音琴 → CHIME
            case 12: case 13:            return 9;   // 马林巴 / 木琴   → XYLOPHONE
            case 14:                     return 7;   // 管钟           → BELL
            case 105:                    return 14;  // 班卓           → BANJO
            case 114:                    return 10;  // 钢鼓           → IRON_XYLOPHONE
            default: break;
        }

        int family = gmProgram / 8;      // GM1 每族 8 个音色，0..15
        return switch (family) {
            case 0  -> 0;    // Piano           → HARP
            case 1  -> 8;    // 音高打击乐      → CHIME
            case 2  -> 6;    // 管风琴          → FLUTE
            case 3  -> 5;    // 吉他            → GUITAR
            case 4  -> 1;    // 贝斯            → BASS
            case 5  -> 0;    // 弦乐            → HARP（46 就是竖琴）
            case 6  -> 0;    // 合奏/人声       → HARP
            case 7  -> 6;    // 铜管            → FLUTE（MC 无铜管，最接近）
            case 8  -> 6;    // 簧片            → FLUTE
            case 9  -> 6;    // 吹管            → FLUTE
            case 10 -> 13;   // 合成主音        → BIT
            case 11 -> 8;    // 合成垫          → CHIME
            case 12 -> 8;    // 合成音效        → CHIME
            case 13 -> 5;    // 民族乐器        → GUITAR
            case 14 -> 10;   // 打击乐          → IRON_XYLOPHONE
            case 15 -> 2;    // 音效            → BASEDRUM
            default -> 0;
        };
    }

    /** GM 打击乐通道：按 note 号分底鼓 / 军鼓 / 踩镲 */
    private static int mapDrumNoteToNbs(int note) {
        if (note == 35 || note == 36) return 2;                                   // 底鼓 → BASEDRUM
        if (note == 38 || note == 40) return 3;                                   // 军鼓 → SNARE
        if (note == 42 || note == 44 || note == 46 || note == 54) return 4;       // 踩镲 → HAT
        if (note == 49 || note == 51 || note == 53 || note == 55
                || note == 57 || note == 59) return 4;                            // 吊镲 → HAT
        if (note >= 41 && note <= 50) return 2;                                   // 嗵鼓 → BASEDRUM
        return 2;
    }

    /** 仅用于诊断输出 */
    private static String nameOf(int id) {
        String[] n = {"Harp", "Bass", "BDrum", "Snare", "Click", "Guitar", "Flute", "Bell",
                "Chime", "Xylo", "IronXyl", "CowBell", "Didger", "Bit", "Banjo", "Pling"};
        return (id >= 0 && id < n.length) ? n[id] : "?" + id;
    }

    // ------------------------------------------------------------------
    // 内部数据结构
    // ------------------------------------------------------------------

    private static final class RawEvent {
        final int tick;
        final int track;
        final int instrument;
        final int note;
        final int velocity;

        RawEvent(int tick, int track, int instrument, int note, int velocity) {
            this.tick = tick;
            this.track = track;
            this.instrument = instrument;
            this.note = note;
            this.velocity = velocity;
        }
    }
}