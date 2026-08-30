package semmiedev.disc_jockey;

import javax.sound.midi.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NbsToMidiExporter {

    private static final int PPQ = 4;
    /**
     * ★ 音符时长（MIDI tick）。PPQ=4 下 1 tick ≈ 十六分音符。
     * 旧值 1 太短导致声音发虚/偏小；改为 2 让音符更饱满。
     * 若仍觉得短/长，可微调此值（2~3 较合适）。
     */
    private static final int NOTE_DURATION_TICKS = 2;
    private static final int MIDI_TO_NBS_OFFSET = 21;

    public static void exportSong(Song song) {
        if (song == null) return;
        String base = (song.fileName != null && !song.fileName.isEmpty())
                ? song.fileName.replaceAll("\\.(?i)nbs$", "") : "exported_song";
        File dir = new File("config/disc_jockey/midi");
        if (!dir.exists()) dir.mkdirs();
        exportSong(song, new File(dir, base + ".mid"));
    }

    public static void exportSong(Song song, File out) {
        if (song == null || out == null) return;
        long[] src = (song.notes != null) ? song.notes
                : (song.foldedNotes != null) ? song.foldedNotes : new long[0];
        if (src.length == 0) return;

        try {
            Sequence seq = new Sequence(Sequence.PPQ, PPQ);
            Track track = seq.createTrack();

            // ★ BPM = TPS * 15（参考 NBSToMIDI 确认）
            float tps = song.tempo / 100.0F;
            int bpm = (int) Math.round(tps * 15.0F);
            if (bpm < 1) bpm = 120;
            int uspq = (int) (60000000L / bpm);
            track.add(new MidiEvent(new MetaMessage(0x51, new byte[]{
                    (byte) ((uspq >> 16) & 0xFF),
                    (byte) ((uspq >> 8) & 0xFF),
                    (byte) (uspq & 0xFF)}, 3), 0));

            List<Exp> all = new ArrayList<>(src.length);
            int[] chanInst = new int[16];
            Map<Long, Integer> occ = new HashMap<>(src.length);

            for (long p : src) {
                int tick = (int) (p & 0xFFFF);
                int layer = (int) ((p >> 16) & 0xFFFF);
                int instr = (int) ((p >> 32) & 0xFF);
                int noteId = Note.extractNoteId(p);
                int midi = (noteId + 33) + MIDI_TO_NBS_OFFSET;

                switch (instr) {
                    case 1: case 11: case 12: midi -= 24; break;
                    case 5:                  midi -= 12; break;
                    case 6: case 8:          midi += 12; break;
                    case 7: case 9: case 10: case 13:
                    case 16: case 17: case 18: case 19: midi += 24; break;
                }
                if (midi < 0) midi = 0;
                if (midi > 127) midi = 127;

                int ch = layerToChannel(layer);
                chanInst[ch] = instr;
                all.add(new Exp(tick, ch, midi));
                occ.merge(((long) ch << 32) | (midi & 0xFF), 1, Integer::sum);
            }

            all.sort(Comparator.comparingInt((Exp e) -> e.tick)
                    .thenComparingInt(e -> e.ch).thenComparingInt(e -> e.midi));

            long[] last = new long[16 * 128];
            Arrays.fill(last, -1L);

            for (Exp e : all) {
                // ★ MIDI tick = NBS tick，1:1（不乘 PPQ，否则慢 4 倍）
                long mt = (long) e.tick;
                int key = e.ch * 128 + e.midi;
                long prev = last[key];
                if (prev >= 0) {
                    long off = prev + NOTE_DURATION_TICKS;
                    if (off > mt) off = mt;   // 防止越过下一个 NOTE_ON 造成重叠
                    try { track.add(new MidiEvent(makeNoteOff(e.ch, e.midi), off)); } catch (InvalidMidiDataException ignored) {}
                }
                int density = occ.getOrDefault(((long) e.ch << 32) | (e.midi & 0xFF), 1);
                // ★ 力度：110（旋律响） / density^0.25（伴奏温和衰减，不再小到听不见）
                int vel = scaledVelocity(110, density);
                try { track.add(new MidiEvent(makeNoteOn(e.ch, e.midi, vel), mt)); } catch (InvalidMidiDataException ignored) {}
                last[key] = mt;
            }

            // ★ 收尾同样不乘 PPQ
            long end = all.isEmpty() ? 0 : ((long) all.get(all.size() - 1).tick + 1);
            for (int k = 0; k < last.length; k++) {
                if (last[k] >= 0) {
                    try { track.add(new MidiEvent(makeNoteOff(k / 128, k % 128), end)); } catch (InvalidMidiDataException ignored) {}
                }
            }

            for (int i = 0; i < 16; i++) {
                final int ch = i;
                if (chanInst[ch] != 0 || all.stream().anyMatch(e -> e.ch == ch)) {
                    try { track.add(new MidiEvent(makeProgramChange(ch, nbsToGm(chanInst[ch])), 0)); } catch (InvalidMidiDataException ignored) {}
                }
            }

            if (out.getParentFile() != null && !out.getParentFile().exists()) out.getParentFile().mkdirs();
            MidiSystem.write(seq, 1, out);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int layerToChannel(int layer) {
        int m = ((layer % 15) + 15) % 15;
        return (m < 9) ? m : (m + 1);
    }

    /**
     * ★ 力度缩放（修复“声太小”的关键）
     * 旧：base / sqrt(density) —— 衰减太狠（density=16 → 25，几乎听不见）
     * 新：base / density^0.25 —— 温和衰减，并设下限 40 保证可闻。
     *   density=1 → 110（旋律突出）
     *   density=4 → 78（伴奏清晰）
     *   density=16 → 55（不盖旋律）
     *   density=64 → 40（下限兜底）
     * 想整体更响：把调用处的 110 调到 120~127；
     * 想伴奏更弱/旋律更突出：把指数 0.25 调大（如 0.35）。
     */
    private static int scaledVelocity(int base, int density) {
        if (density < 1) density = 1;
        double v = base / Math.pow(density, 0.25);
        if (v < 40) v = 40;        // 下限：保证听得见
        if (v > 127) v = 127;      // MIDI 上限
        return (int) Math.round(v);
    }

    private static ShortMessage makeNoteOn(int ch, int midi, int vel) throws InvalidMidiDataException {
        ShortMessage m = new ShortMessage(); m.setMessage(ShortMessage.NOTE_ON, ch, midi, vel); return m;
    }
    private static ShortMessage makeNoteOff(int ch, int midi) throws InvalidMidiDataException {
        ShortMessage m = new ShortMessage(); m.setMessage(ShortMessage.NOTE_OFF, ch, midi, 0); return m;
    }
    private static ShortMessage makeProgramChange(int ch, int gm) throws InvalidMidiDataException {
        ShortMessage m = new ShortMessage(); m.setMessage(ShortMessage.PROGRAM_CHANGE, ch, gm, 0); return m;
    }

    private static class Exp {
        final int tick, ch, midi;
        Exp(int t, int c, int m) { tick = t; ch = c; midi = m; }
    }

    private static int nbsToGm(int nbs) {
        switch (nbs) {
            case 1:  return 33;
            case 5:  return 25;
            case 6:  return 74;
            case 7:  return 15;
            case 8:  return 11;
            case 9:  return 13;
            case 16: case 17: case 18: case 19: return 57;
            case 2:  return 36;
            case 3:  return 38;
            case 4:  return 42;
            default: return 0;
        }
    }

    // ===== writeNbs 及相关（完整保留，不删减） =====
    public static byte[] writeNbs(Song song) {
        if (song == null) throw new IllegalArgumentException("song is null");

        ByteBuffer head = ByteBuffer.allocate(0x2E).order(ByteOrder.LITTLE_ENDIAN);
        head.put((byte) 0xE0);
        head.putShort((short) 0);
        int layerCount = (song.height > 0) ? song.height : countLayers(song);
        head.putShort((short) 4);
        head.put((byte) 0x0A);
        head.put((byte) 3);
        head.put((byte) 0);
        head.put((byte) 4);
        head.put((byte) 0);
        head.put((byte) 0);
        while (head.position() < 0x2E) head.put((byte) 0);

        List<NoteRec> notes = collectNotes(song);
        ByteBuffer noteBuf = ByteBuffer.allocate(notes.size() * 8 + 8).order(ByteOrder.LITTLE_ENDIAN);
        int curTick = 0;
        int curLayer = -1;
        for (NoteRec nr : notes) {
            if (nr.layer != curLayer) {
                noteBuf.putShort((short) 0);
                noteBuf.putShort((short) nr.layer);
                curLayer = nr.layer;
                curTick = 0;
            }
            int jump = nr.tick - curTick;
            if (jump < 0) jump = 0;
            noteBuf.putShort((short) jump);
            noteBuf.putShort((short) nr.layer);
            noteBuf.put((byte) nr.key);
            noteBuf.put((byte) nr.instrument);
            noteBuf.put((byte) nr.velocity);
            noteBuf.put((byte) nr.panning);
            curTick = nr.tick;
        }
        noteBuf.putShort((short) 0);
        noteBuf.putShort((short) 0);

        ByteBuffer layerBuf = ByteBuffer.allocate((layerCount + 4) * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int li = 0; li < layerCount; li++) {
            String name = (li < DEFAULT_LAYER_NAMES.length) ? DEFAULT_LAYER_NAMES[li] : "Layer " + li;
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            int recStart = layerBuf.position();
            layerBuf.putShort((short) nameBytes.length);
            layerBuf.put(nameBytes);
            int padTo = recStart + 16;
            if (layerBuf.position() > padTo) {
                layerBuf.position(recStart + 2);
                layerBuf.put(nameBytes, 0, Math.min(nameBytes.length, 14));
                layerBuf.position(padTo);
            } else {
                while (layerBuf.position() < padTo) layerBuf.put((byte) 0);
            }
        }

        head.flip();
        noteBuf.flip();
        layerBuf.flip();
        int total = head.remaining() + noteBuf.remaining() + layerBuf.remaining();
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(head);
        out.put(noteBuf);
        out.put(layerBuf);
        return out.array();
    }

    private static List<NoteRec> collectNotes(Song song) {
        List<NoteRec> list = new ArrayList<>();
        long[] src = (song.notes != null) ? song.notes
                : (song.foldedNotes != null) ? song.foldedNotes : new long[0];
        for (long p : src) {
            int tick = (int) (p & 0xFFFF);
            int layer = (int) ((p >> 16) & 0xFFFF);
            int instr = (int) ((p >> 32) & 0xFF);
            int noteId = Note.extractNoteId(p);
            int key = noteId + 33;
            if (key < 0) key = 0;
            if (key > 87) key = 87;
            list.add(new NoteRec(tick, layer, key, instr, 100, 64));
        }
        list.sort(Comparator.comparingInt((NoteRec n) -> n.tick).thenComparingInt(n -> n.layer));
        return list;
    }

    private static int countLayers(Song song) {
        int max = 0;
        long[] src = (song.notes != null) ? song.notes
                : (song.foldedNotes != null) ? song.foldedNotes : new long[0];
        for (long p : src) {
            int layer = (int) ((p >> 16) & 0xFFFF);
            if (layer > max) max = layer;
        }
        return max + 1;
    }

    private static final String[] DEFAULT_LAYER_NAMES = {
            "Grand Piano", "Grand Piano", "Grand Piano", "Grand Piano",
            "Grand Piano", "Grand Piano", "Grand Piano", "Grand Piano"
    };

    private static class NoteRec {
        final int tick, layer, key, instrument, velocity, panning;
        NoteRec(int t, int l, int k, int i, int v, int p) {
            tick = t; layer = l; key = k; instrument = i; velocity = v; panning = p;
        }
    }

    /** MIDI→NBS 入口：委托给独立的 MidiToNbsImporter（无内嵌类，不会报文件名不匹配） */
    public static Song importMidi(File midiFile) {
        return MidiToNbsImporter.importMidi(midiFile);
    }
}