package semmiedev.disc_jockey.midi;

import javax.sound.midi.*;
import java.io.File;
import java.io.FileOutputStream;   
import java.io.IOException;


public class MidiRecorder {

    private static final int PPQ = 480;
    private static final int CHANNEL = 0;
    private static final int VELOCITY_DEFAULT = 96;

    private Sequence sequence;
    private Track track;
    private long startTick;
    private boolean recording;

    private static final MidiRecorder INSTANCE = new MidiRecorder();
    public static MidiRecorder get() { return INSTANCE; }

    private MidiRecorder() {}

    
    public void start() {
        try {
            sequence = new Sequence(Sequence.PPQ, PPQ);
            track = sequence.createTrack();
            addTempo(120);
            startTick = System.nanoTime();
            recording = true;
        } catch (InvalidMidiDataException t) {   
            recording = false;
            t.printStackTrace();
        }
    }

    
    public boolean stopAndSave(String filePath) {
        if (!recording) return false;
        recording = false;
        try {
            MetaMessage end = new MetaMessage();
            end.setMessage(0x2F, new byte[0], 0);
            track.add(new MidiEvent(end, track.size() > 0
                    ? track.get(track.size() - 1).getTick() + 1 : 0));
            
            try (FileOutputStream fos = new FileOutputStream(new File(filePath))) {
                MidiSystem.write(sequence, 1, fos);
            }
            return true;
        } catch (InvalidMidiDataException t) {   
            t.printStackTrace();
            return false;
        } catch (IOException t) {                
            t.printStackTrace();
            return false;
        }
    }

    
    public void noteOn(int pianoLibId, int velocity) {
        if (!recording) return;
        int midiNote = pianoLibId + 21;
        long tick = currentTick();
        try {
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, CHANNEL, midiNote, clampVel(velocity));
            track.add(new MidiEvent(on, tick));
        } catch (InvalidMidiDataException ignored) {}
    }

    
    public void noteOff(int pianoLibId) {
        if (!recording) return;
        int midiNote = pianoLibId + 21;
        long tick = currentTick();
        try {
            ShortMessage off = new ShortMessage();
            off.setMessage(ShortMessage.NOTE_OFF, CHANNEL, midiNote, 0);
            track.add(new MidiEvent(off, tick));
        } catch (InvalidMidiDataException ignored) {}
    }

    public boolean isRecording() { return recording; }

    private long currentTick() {
        long nanos = System.nanoTime() - startTick;
        double ticksPerNs = (120.0 / 60.0) / PPQ;
        return (long) (nanos * 1e-9 * ticksPerNs * PPQ);
    }

    private void addTempo(int bpm) throws InvalidMidiDataException {
        int mpqn = 60_000_000 / bpm;
        MetaMessage tempo = new MetaMessage();
        tempo.setMessage(0x51, new byte[]{
                (byte) (mpqn >> 16), (byte) (mpqn >> 8), (byte) mpqn
        }, 3);
        track.add(new MidiEvent(tempo, 0));
    }

    private int clampVel(int v) { return Math.max(1, Math.min(127, v)); }
}