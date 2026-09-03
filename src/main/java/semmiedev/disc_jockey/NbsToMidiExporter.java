package semmiedev.disc_jockey;

import javax.sound.midi.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NbsToMidiExporter {

    /* ==================== 核心常量 ==================== */
    private static final int PPQ = 4;
    private static final int VELOCITY_BASE = 120;
    private static final int VELOCITY_MIN = 25;
    private static final double VELOCITY_DECAY = 0.72;
    private static final int NOTE_DURATION_TICKS = 1;
    private static final boolean ROUTE_DRUMS_TO_CHANNEL_10 = true;
    private static final boolean PAN_PER_CHANNEL = true;
    private static final int DEFAULT_CHANNEL_VOLUME = 100;
    private static final int MIDI_TO_NBS_OFFSET = 21;
    private static final int DRUM_CHANNEL = 9;

    private static final int PITCH_CLAMP_LOW = 48;
    private static final int PITCH_CLAMP_HIGH = 96;
    private static final int MAX_OCTAVE_SHIFT = 0;

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
        long[] src = (song.notes != null && song.notes.length > 0) ? song.notes
                : (song.foldedNotes != null) ? song.foldedNotes : new long[0];
        if (src.length == 0) return;

        try {
            Sequence seq = new Sequence(Sequence.PPQ, PPQ);
            Track track = seq.createTrack();

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
            int[] chanVol = new int[16];
            Map<Integer, Integer> tickCount = new HashMap<>(src.length);
            int pitchFolded = 0;

            for (long p : src) {
                int tick = (int) (p & 0xFFFF);
                int layer = (int) ((p >> 16) & 0xFFFF);
                int instr = (int) ((p >> 32) & 0xFF);
                int noteId = Note.extractNoteId(p);

                int midi = (noteId + 33) + MIDI_TO_NBS_OFFSET;

                switch (instr) {
                    case 1: case 11: case 12: midi -= 12; break;
                    case 5:                  midi -= 12; break;
                }

                while (midi > PITCH_CLAMP_HIGH) { midi -= 12; pitchFolded++; }
                while (midi < PITCH_CLAMP_LOW)  { midi += 12; pitchFolded++; }
                if (midi < 0) midi = 0;
                if (midi > 127) midi = 127;

                int ch = channelFor(instr, layer);
                chanInst[ch] = instr;
                int vol = channelVolume(instr);
                chanVol[ch] = Math.max(chanVol[ch], vol);

                long key = ((long) ch << 32) | (midi & 0xFF);
                all.add(new Exp(tick, ch, midi, key, instr));
                tickCount.merge(tick, 1, Integer::sum);
            }

            all.sort(Comparator.comparingLong(Exp::order));

            // ★ 力度 + 踩镲压制
            for (Exp e : all) {
                int simul = tickCount.getOrDefault(e.tick, 1);
                e.vel = scaledVelocity(VELOCITY_BASE, simul);
                if (e.instr == 4) {                    // ★ 踩镲额外压 60%
                    e.vel = Math.max(10, (int)(e.vel * 0.4));
                }
            }

            long endTick = 0;
            List<Event> events = new ArrayList<>(all.size() * 2);
            Map<Long, Long> lastOff = new HashMap<>();

            for (Exp e : all) {
                int dur = durationFor(e.instr);
                long mt = (long) e.tick;
                Long prev = lastOff.get(e.key);
                if (prev != null && prev > mt) {
                    lastOff.put(e.key, Math.max(prev, mt + dur));
                    continue;
                }
                if (prev != null) events.add(new Event(prev, false, e.ch, e.midi, 0));
                events.add(new Event(mt, true, e.ch, e.midi, e.vel));
                lastOff.put(e.key, mt + dur);
                if (mt + dur > endTick) endTick = mt + dur;
            }
            for (Map.Entry<Long, Long> en : lastOff.entrySet()) {
                long off = en.getValue();
                long k = en.getKey();
                events.add(new Event(off, false, (int)(k>>7)&0x7F, (int)(k&0x7F), 0));
            }
            Collections.sort(events);

            for (int ch = 0; ch < 16; ch++) {
                if (chanInst[ch] == 0 && !anyOnChannel(events, ch)) continue;
                if (PAN_PER_CHANNEL) {
                    track.add(new MidiEvent(makeControlChange(ch, 10, panFor(ch)), 0));
                }
                track.add(new MidiEvent(makeControlChange(ch, 7, Math.max(1, chanVol[ch])), 0));
                track.add(new MidiEvent(makeProgramChange(ch, gmProgram(chanInst[ch], ch)), 0));
            }
            for (Event ev : events) {
                track.add((MidiEvent) (ev.on
                        ? new MidiEvent(makeNoteOn(ev.ch, ev.midi, ev.vel), ev.tick)
                        : new MidiEvent(makeNoteOff(ev.ch, ev.midi), ev.tick)));
            }

            if (out.getParentFile() != null && !out.getParentFile().exists()) out.getParentFile().mkdirs();
            MidiSystem.write(seq, 1, out);

            System.out.println("[Export] ticks=" + (all.isEmpty() ? 0 : all.get(all.size()-1).tick)
                    + " notes=" + all.size() + " pitchFolded=" + pitchFolded
                    + " bpm=" + bpm);

        } catch (Exception e) {
            throw new RuntimeException("Export failed", e);
        }
    }

    /* ==================== 辅助方法 ==================== */

    private static int channelFor(int instr, int layer) {
        if (ROUTE_DRUMS_TO_CHANNEL_10 && isDrum(instr)) return DRUM_CHANNEL;
        int m = ((layer % 15) + 15) % 15;
        return (m < 9) ? m : (m + 1);
    }

    private static boolean isDrum(int instr) {
        return instr == 2 || instr == 3 || instr == 4;
    }

    private static int channelVolume(int instr) {
        if (isDrum(instr)) return 110;
        if (instr == 0) return 105;
        if (instr == 1) return 95;
        if (instr >= 5) return 85;
        return 90;
    }

    private static int panFor(int ch) {
        return 32 + ((ch * 17) % 65);
    }

    private static int scaledVelocity(int base, int density) {
        if (density < 1) density = 1;
        double v = base * Math.pow(VELOCITY_DECAY, density - 1);
        if (v < VELOCITY_MIN) v = VELOCITY_MIN;
        if (v > 127) v = 127;
        return (int) Math.round(v);
    }

    private static int durationFor(int instr) {
        if (isDrum(instr)) return 1;
        if (instr == 0) return 4;
        return 3;
    }

    private static int gmProgram(int nbs, int ch) {
        if (ch == DRUM_CHANNEL) return 0;
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
            case 4:  return 54; // ★ Tambourine 替代 Hi-Hat(42), 消除嘶嘶白噪
            default: return 0;
        }
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
    private static ShortMessage makeControlChange(int ch, int cc, int value) throws InvalidMidiDataException {
        ShortMessage m = new ShortMessage(); m.setMessage(ShortMessage.CONTROL_CHANGE, ch, cc, value); return m;
    }
    private static boolean anyOnChannel(List<Event> events, int ch) {
        for (Event e : events) if (e.ch == ch && e.on) return true;
        return false;
    }

    private static final class Exp {
        final int tick, ch, midi, instr;
        final long key;
        int vel;
        Exp(int t, int c, int m, long k, int instr) { tick = t; ch = c; midi = m; key = k; this.instr = instr; }
        long order() { return ((long) tick << 32) | ((long) ch << 16) | (midi & 0xFF); }
    }
    private static final class Event implements Comparable<Event> {
        final long tick; final boolean on; final int ch, midi, vel;
        Event(long t, boolean on, int ch, int midi, int vel) { this.tick = t; this.on = on; this.ch = ch; this.midi = midi; this.vel = vel; }
        @Override public int compareTo(Event o) { return Long.compare(tick, o.tick); }
    }

    // ===== writeNbs 及相关(完整保留,不删减) =====
    public static byte[] writeNbs(Song song) {
        if (song == null) throw new IllegalArgumentException("song is null");
        ByteBuffer head = ByteBuffer.allocate(0x2E).order(ByteOrder.LITTLE_ENDIAN);
        head.put((byte) 0xE0); head.putShort((short) 0);
        int layerCount = (song.height > 0) ? song.height : countLayers(song);
        head.putShort((short) 4); head.put((byte) 0x0A); head.put((byte) 3);
        head.put((byte) 0); head.put((byte) 4); head.put((byte) 0); head.put((byte) 0);
        while (head.position() < 0x2E) head.put((byte) 0);

        List<NoteRec> notes = collectNotes(song);
        ByteBuffer noteBuf = ByteBuffer.allocate(notes.size() * 8 + 8).order(ByteOrder.LITTLE_ENDIAN);
        int curTick = 0, curLayer = -1;
        for (NoteRec nr : notes) {
            if (nr.layer != curLayer) { noteBuf.putShort((short) 0); noteBuf.putShort((short) nr.layer); curLayer = nr.layer; curTick = 0; }
            int jump = nr.tick - curTick; if (jump < 0) jump = 0;
            noteBuf.putShort((short) jump); noteBuf.putShort((short) nr.layer);
            noteBuf.put((byte) nr.key); noteBuf.put((byte) nr.instrument);
            noteBuf.put((byte) nr.velocity); noteBuf.put((byte) nr.panning);
            curTick = nr.tick;
        }
        noteBuf.putShort((short) 0); noteBuf.putShort((short) 0);

        ByteBuffer layerBuf = ByteBuffer.allocate((layerCount + 4) * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int li = 0; li < layerCount; li++) {
            String name = (li < DEFAULT_LAYER_NAMES.length) ? DEFAULT_LAYER_NAMES[li] : "Layer " + li;
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            int recStart = layerBuf.position();
            layerBuf.putShort((short) nameBytes.length); layerBuf.put(nameBytes);
            int padTo = recStart + 16;
            if (layerBuf.position() > padTo) {
                layerBuf.position(recStart + 2);
                layerBuf.put(nameBytes, 0, Math.min(nameBytes.length, 14));
                layerBuf.position(padTo);
            } else { while (layerBuf.position() < padTo) layerBuf.put((byte) 0); }
        }
        head.flip(); noteBuf.flip(); layerBuf.flip();
        int total = head.remaining() + noteBuf.remaining() + layerBuf.remaining();
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(head); out.put(noteBuf); out.put(layerBuf);
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
            if (key < 0) key = 0; if (key > 87) key = 87;
            list.add(new NoteRec(tick, layer, key, instr, 100, 64));
        }
        list.sort(Comparator.comparingInt((NoteRec n) -> n.tick).thenComparingInt(n -> n.layer));
        return list;
    }

    private static int countLayers(Song song) {
        int max = 0;
        long[] src = (song.notes != null) ? song.notes : (song.foldedNotes != null) ? song.foldedNotes : new long[0];
        for (long p : src) { int layer = (int) ((p >> 16) & 0xFFFF); if (layer > max) max = layer; }
        return max + 1;
    }

    private static final String[] DEFAULT_LAYER_NAMES = {
            "Grand Piano", "Grand Piano", "Grand Piano", "Grand Piano",
            "Grand Piano", "Grand Piano", "Grand Piano", "Grand Piano"
    };
    private static class NoteRec {
        final int tick, layer, key, instrument, velocity, panning;
        NoteRec(int t, int l, int k, int i, int v, int p) { tick = t; layer = l; key = k; instrument = i; velocity = v; panning = p; }
    }

    /** MIDI→NBS 入口 */
    public static Song importMidi(File midiFile) { return MidiToNbsImporter.importMidi(midiFile); }
}