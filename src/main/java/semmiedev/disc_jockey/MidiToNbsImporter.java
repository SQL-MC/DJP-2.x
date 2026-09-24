package semmiedev.disc_jockey;

import javax.sound.midi.*;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MidiToNbsImporter {

    private static final int DEFAULT_BPM = 120;

    
    @SuppressWarnings("unused")
    private static final int SIGNED_MIN = -33;
    @SuppressWarnings("unused")
    private static final int SIGNED_MAX = 54;

    
    private static final int MIDI_TO_NBS_OFFSET = 21;

    
    private static final int DEFAULT_PPQ = 480;

    
    private static final int NBS_PPQ = 4;

    
    private static final int VANILLA_INSTRUMENT_COUNT = 10;

    
    private static final long GRID_BYTE_LIMIT = 1048576L;

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
        double tickRatio = computeTickRatio(ppq);

        // 只给"确实含 NOTE_ON"的有效 track 编 layer
        List<Track> usable = new ArrayList<>();
        for (Track t : sequence.getTracks()) if (hasNoteOn(t)) usable.add(t);

        
        
        
        List<RawEvent> raws = new ArrayList<>();
        int rawMaxTick = 0;

        for (int ti = 0; ti < usable.size(); ti++) {
            Track track = usable.get(ti);

            int channel = -1;
            int gmProgram = 0;   
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
            final int nbsInstrument = clampToVanilla(mapGmToNbs(resolvedProgram, isDrum));

            for (int i = 0; i < track.size(); i++) {
                MidiEvent me = track.get(i);
                if (me.getMessage() instanceof ShortMessage sm) {
                    if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
                        int nbsTick = (tickRatio > 0)
                                ? (int) Math.round(me.getTick() / tickRatio)
                                : (int) me.getTick();
                        if (nbsTick < 0) nbsTick = 0;
                        
                        if (nbsTick > Short.MAX_VALUE) nbsTick = Short.MAX_VALUE;

                        raws.add(new RawEvent(nbsTick, ti, nbsInstrument,
                                sm.getData1(), sm.getData2()));
                        if (nbsTick > rawMaxTick) rawMaxTick = nbsTick;
                    }
                }
            }
        }

        if (raws.isEmpty()) return null;

        
        long lim = GRID_BYTE_LIMIT / (8L * Math.max(1, rawMaxTick + 1));
        int allowedLayers = (int) Math.max(1, Math.min(256, lim));
        int skipped = 0;

        
        
        
        
        
        
        
        
        
        
        
        
        
        Map<Integer, List<RawEvent>> byTrack = new HashMap<>();
        for (RawEvent r : raws) byTrack.computeIfAbsent(r.track, k -> new ArrayList<>()).add(r);

        List<Integer> trackIds = new ArrayList<>(byTrack.keySet());
        trackIds.sort(Comparator.naturalOrder());

        int layerBase = 0;
        int maxVoices = 0;
        for (int ti : trackIds) {
            List<RawEvent> list = byTrack.get(ti);
            list.sort(Comparator.comparingInt((RawEvent r) -> r.tick)
                    .thenComparingInt(r -> r.note));

            List<Integer> voiceLastTick = new ArrayList<>();
            for (RawEvent r : list) {
                int v = -1;
                for (int i = 0; i < voiceLastTick.size(); i++) {
                    
                    if (voiceLastTick.get(i) < r.tick) { v = i; break; }
                }
                if (v < 0) {
                    
                    if (layerBase + voiceLastTick.size() >= allowedLayers) { skipped++; continue; }
                    v = voiceLastTick.size();
                    voiceLastTick.add(-1);
                }
                voiceLastTick.set(v, r.tick);
                r.layer = layerBase + v;
            }
            if (voiceLastTick.size() > maxVoices) maxVoices = voiceLastTick.size();
            layerBase += voiceLastTick.size();
        }

        
        List<NoteEvent> events = new ArrayList<>(raws.size());
        for (RawEvent r : raws) {
            events.add(new NoteEvent(r.tick, r.layer, r.instrument, r.note, r.velocity));
        }

        events.sort(Comparator.comparingLong((NoteEvent e) -> e.tick)
                .thenComparingInt(e -> e.layer));

        
        
        
        List<Long> packed = new ArrayList<>();
        int maxTick = 0, maxLayer = 0;

        for (NoteEvent evt : events) {
            int nbsKey = evt.note - MIDI_TO_NBS_OFFSET;   

            
            switch (evt.instrument) {
                case 1:  nbsKey += 24; break;  
                case 11: nbsKey += 24; break;  
                case 12: nbsKey += 24; break;  
                case 5:  nbsKey += 12; break;  
                case 6:  nbsKey -= 12; break;  
                case 8:  nbsKey -= 12; break;  
                case 7:  nbsKey -= 24; break;  
                case 9:  nbsKey -= 24; break;  
                case 10: nbsKey -= 24; break;  
                case 13: nbsKey -= 24; break;  
                case 16: case 17: case 18: case 19: nbsKey -= 24; break; 
            }

            while (nbsKey < 0) nbsKey += 12;
            while (nbsKey > 87) nbsKey -= 12;

            int signedNoteId = nbsKey - 33;

            int nbsTick = (int) evt.tick;
            if (nbsTick < 0) nbsTick = 0;
            if (nbsTick > Short.MAX_VALUE) nbsTick = Short.MAX_VALUE;

            packed.add(packNote(nbsTick, evt.layer, evt.instrument, signedNoteId));

            if (nbsTick > maxTick) maxTick = nbsTick;
            if (evt.layer > maxLayer) maxLayer = evt.layer;
        }

        if (packed.isEmpty()) return null;

        long[] notesArray = packed.stream().mapToLong(Long::longValue).toArray();

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

        
        song.uniqueNotes.clear();
        Set<Long> uniqueKeys = new LinkedHashSet<>();
        for (long p : notesArray) {
            int instrument = (int) ((p >> 32) & 0xFF);
            int noteId = Note.extractNoteId(p);
            long key = ((long) instrument << 8) | (noteId & 0xFF);
            if (uniqueKeys.add(key)) {
                song.uniqueNotes.add(new Note(Note.INSTRUMENTS[instrument], (byte) noteId));
            }
        }

        song.fileName = midiFile.getName();
        song.displayName = stripExt(midiFile.getName());
        song.name = song.displayName;
        song.author = "Imported";
        song.originalAuthor = "";
        song.description = "";
        song.importFileName = midiFile.getName();

        song.formatVersion = 0;
        song.vanillaInstrumentCount = (byte) VANILLA_INSTRUMENT_COUNT;
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

        
        int conflicts = 0;
        Set<Long> seen = new HashSet<>();
        for (long p : notesArray) {
            long k = (p & 0xFFFFL) | (((p >>> 16) & 0xFFFFL) << 32);
            if (!seen.add(k)) conflicts++;
        }
        System.out.println("[MidiToNbsImporter] DIAG notes=" + notesArray.length
                + " maxTick=" + maxTick + " maxLayer=" + maxLayer
                + " | length=" + song.length + " height=" + song.height
                + " tempo=" + song.tempo
                + " | bpm=" + bpm + " ppq=" + ppq + " tickRatio=" + tickRatio);
        System.out.println("[MidiToNbsImporter] DIAG (tick,layer)冲突=" + conflicts
                + " ← 必须为 0（非 0 会导致 ONBS 提前终止、后面全空）");
        System.out.println("[MidiToNbsImporter] DIAG 每track最大voice=" + maxVoices
                + " layer上限=" + allowedLayers
                + " 网格≈" + ((long) safeLength * safeHeight * 8L / 1024) + "KB");
        if (skipped > 0) {
            System.out.println("[MidiToNbsImporter] ⚠ 因 layer 上限丢弃音符=" + skipped
                    + "（可忽略，或缩短歌曲 / 增大 GRID_BYTE_LIMIT）");
        }

        return song;
    }

    
    private static int clampToVanilla(int instrument) {
        return (instrument < 0 || instrument >= VANILLA_INSTRUMENT_COUNT) ? 0 : instrument;
    }

    
    private static int mapGmToNbs(int gmProgram, boolean isDrum) {
        if (isDrum) {
            
            return 1;
        }
        if (gmProgram >= 0  && gmProgram <= 7)  return 0;   // HARP (钢琴/电钢)
        if (gmProgram >= 8  && gmProgram <= 15) return 0;   
        if (gmProgram >= 16 && gmProgram <= 23) return 0;   // HARP/手风琴
        if (gmProgram >= 24 && gmProgram <= 31) return 5;   
        if (gmProgram >= 32 && gmProgram <= 39) return 1;   
        if (gmProgram >= 40 && gmProgram <= 55) return 0;   // HARP (弦乐/竖琴)
        if (gmProgram >= 56 && gmProgram <= 63) return 16;  
        if (gmProgram >= 64 && gmProgram <= 71) return 6;   
        if (gmProgram >= 72 && gmProgram <= 79) return 6;   // FLUTE (笛/哨)
        if (gmProgram >= 80 && gmProgram <= 95) return 15;  
        if (gmProgram >= 96 && gmProgram <= 119) return 0;  // HARP (合成垫/音效)
        if (gmProgram >= 112 && gmProgram <= 127) return 2; 
        return 0; 
    }

    
    private static double computeTickRatio(int ppq) {
        return ppq / (double) NBS_PPQ;
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

    
    private static long packNote(int tick, int layer, int instrument, int signedNoteId) {
        long packed = ((long) tick       & 0xFFFFL)
                | ((long) layer          & 0xFFFFL) << 16
                | ((long) instrument     & 0xFFL)   << 32;
        return Note.packNoteId(packed, signedNoteId);
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

    
    private static final class RawEvent {
        final int tick;
        int layer;
        final int track;
        final int instrument;
        final int note;
        final int velocity;
        RawEvent(int tick, int track, int instrument, int note, int velocity) {
            this.tick = tick; this.track = track;
            this.instrument = instrument; this.note = note; this.velocity = velocity;
            this.layer = 0;
        }
    }

    private static class NoteEvent {
        final long tick;
        final int layer;
        final int instrument;  
        final int note;        
        final int velocity;
        NoteEvent(long tick, int layer, int instrument, int note, int velocity) {
            this.tick = tick; this.layer = layer;
            this.instrument = instrument; this.note = note; this.velocity = velocity;
        }
    }
}