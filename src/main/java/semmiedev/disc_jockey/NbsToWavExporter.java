package semmiedev.disc_jockey;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;


public class NbsToWavExporter {

    
    private static final int SAMPLE_RATE = 44100;   
    private static final int CHANNELS = 2;           
    private static final int BITS = 16;
    private static final double SECONDS_PER_TICK_DEFAULT = 0.1; // tempo=1000 → 10 tick/s

    
    private static final double ATTACK = 0.006;
    private static final double DECAY = 0.18;
    private static final double SUSTAIN = 0.65;
    private static final double RELEASE = 0.10;
    private static final double TAIL_SECONDS = 0.5;  

    
    
    private static final double MASTER_LOWPASS = 0.35;
    
    private static final double HAT_CUTOFF = 0.18;   

    
    private static final int MIDI_TO_NBS_OFFSET = 21;
    private static final int PITCH_CLAMP_LOW = 48;
    private static final int PITCH_CLAMP_HIGH = 96;
    private static final int DRUM_CHANNEL = 9;

    
    private static final int VELOCITY_BASE = 120;
    private static final int VELOCITY_MIN = 25;
    private static final double VELOCITY_DECAY = 0.72;

    
    public static void exportSong(Song song) {
        if (song == null) return;
        String base = (song.fileName != null && !song.fileName.isEmpty())
                ? song.fileName.replaceAll("\\.(?i)nbs$", "") : "exported_song";
        File dir = new File("config/disc_jockey/wav");
        if (!dir.exists()) dir.mkdirs();
        exportSong(song, new File(dir, base + ".wav"));
    }

    public static void exportSong(Song song, File out) {
        if (song == null || out == null) return;
        long[] src = (song.notes != null && song.notes.length > 0) ? song.notes
                : (song.foldedNotes != null) ? song.foldedNotes : new long[0];
        if (src.length == 0) return;

        try {
            
            List<WavNote> events = buildEvents(song, src);
            if (events.isEmpty()) return;

            
            double totalSec = 0;
            for (WavNote n : events) totalSec = Math.max(totalSec, n.startSec + n.durSec);
            totalSec += TAIL_SECONDS;

            
            double[][] pcm = synthesize(events, totalSec);

            
            if (out.getParentFile() != null && !out.getParentFile().exists()) out.getParentFile().mkdirs();
            writeWav(out, pcm, SAMPLE_RATE);

            System.out.println("[WAV Export] notes=" + events.size()
                    + " duration=" + String.format("%.1f", totalSec) + "s"
                    + " -> " + out.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("WAV export failed", e);
        }
    }

    
    private static List<WavNote> buildEvents(Song song, long[] src) {
        double secPerTick = (song.tempo > 0)
                ? 100.0 / song.tempo   
                : SECONDS_PER_TICK_DEFAULT;

        
        Map<Integer, Integer> tickCount = new HashMap<>(src.length);
        for (long p : src) tickCount.merge((int) (p & 0xFFFF), 1, Integer::sum);

        List<WavNote> events = new ArrayList<>(src.length);
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

            
            while (midi > PITCH_CLAMP_HIGH) midi -= 12;
            while (midi < PITCH_CLAMP_LOW)  midi += 12;
            if (midi < 0) midi = 0;
            if (midi > 127) midi = 127;

            
            int simul = tickCount.getOrDefault(tick, 1);
            int vel = scaledVelocity(VELOCITY_BASE, simul);
            if (instr == 4) vel = Math.max(10, (int) (vel * 0.4)); 
            double amp = vel / 127.0;

            
            double durSec = durationTicks(instr) * secPerTick;

            
            int ch = channelFor(instr, layer);
            double pan = (panFor(ch) - 64) / 64.0; 
            pan = Math.max(-1, Math.min(1, pan));

            events.add(new WavNote(tick * secPerTick, durSec, midi, amp, instr, pan));
        }
        Collections.sort(events, Comparator.comparingDouble(e -> e.startSec));
        return events;
    }

    
    private static double[][] synthesize(List<WavNote> events, double totalSec) {
        int totalSamples = (int) (totalSec * SAMPLE_RATE) + 1;
        double[] left = new double[totalSamples];
        double[] right = new double[totalSamples];
        PinkNoise pink = new PinkNoise(12345); 

        
        double[] f = new double[4];

        for (WavNote n : events) {
            int start = (int) (n.startSec * SAMPLE_RATE);
            int end = (int) ((n.startSec + n.durSec) * SAMPLE_RATE);
            if (start < 0) start = 0;
            if (end > totalSamples) end = totalSamples;
            if (end <= start) continue;

            double freq = 440.0 * Math.pow(2.0, (n.midi - 69) / 12.0);
            double phase = 0;
            double phaseInc = freq / SAMPLE_RATE;
            boolean isDrum = isDrum(n.instr);

            
            for (int s = start; s < end; s++) {
                double t = (s - start) / (double) SAMPLE_RATE;
                double env = adsr(t, n.durSec);
                double sample;
                if (isDrum) {
                    // ★ v3: 粉红噪声 + 去噪声化, 消除"刺啦/呲呲"
                    sample = drumSample(n.instr, t, env, pink, f);
                } else {
                    phase += phaseInc;
                    sample = tone(phase, n.instr) * env;
                }
                double v = sample * n.amp;
                
                left[s]  += v * (1 - n.pan * 0.7);
                right[s] += v * (1 + n.pan * 0.7);
            }
        }

        // ★ v3: 去直流 + 全局抗混叠低通(消除"怪声/刺啦") + 归一化
        removeDCAndAntiAlias(left, right, MASTER_LOWPASS);

        return new double[][]{left, right};
    }

    
    private static void removeDCAndAntiAlias(double[] left, double[] right, double cutoff) {
        int n = left.length;
        
        double dcL = 0, dcR = 0;
        for (int i = 0; i < n; i++) { dcL += left[i]; dcR += right[i]; }
        dcL /= n; dcR /= n;
        
        double lpL = 0, lpR = 0;
        double a = Math.max(0, Math.min(0.99, cutoff));
        for (int i = 0; i < n; i++) {
            double cl = left[i] - dcL;
            double cr = right[i] - dcR;
            lpL += a * (cl - lpL); left[i]  = lpL;
            lpR += a * (cr - lpR); right[i] = lpR;
        }
    }

    
    private static double adsr(double t, double dur) {
        if (t < 0) return 0;
        double relStart = Math.max(ATTACK + DECAY, dur - RELEASE);
        if (t < ATTACK) return t / ATTACK;
        if (t < ATTACK + DECAY) {
            double k = (t - ATTACK) / DECAY;
            return 1.0 + (SUSTAIN - 1.0) * k;
        }
        if (t < relStart) return SUSTAIN;
        double k = (t - relStart) / Math.max(0.001, dur - relStart);
        return SUSTAIN * (1.0 - k);
    }

    
    private static double tone(double phase, int instr) {
        double s1 = Math.sin(2 * Math.PI * phase);
        double s2 = Math.sin(4 * Math.PI * phase);
        double s3 = Math.sin(6 * Math.PI * phase);
        if (instr == 0)        return s1 * 0.7 + s2 * 0.3;          // harp/piano: 柔和
        if (instr == 1)        return s1 * 0.8 + s2 * 0.2;          
        if (instr == 5)        return saw(phase) * 0.7 + s1 * 0.3;  
        if (instr == 6)        return s1;                            
        if (instr == 7)        return s1 + s3 * 0.6;                
        if (instr == 8)        return s1 + s3 * 0.5;                
        if (instr == 9)        return s1 * 0.6 + s2 * 0.4;          
        if (instr == 11 || instr == 12) return s1 * 0.8 + s2 * 0.2; // didgeridoo/bit: bassy
        if (instr == 13)       return s1 * 0.5 + s2 * 0.5;          
        return s1 * 0.6 + s2 * 0.25 + s3 * 0.15;                    
    }

    
    private static double saw(double phase) {
        double f = phase - Math.floor(phase); 
        return 2.0 * f - 1.0;
    }

    

    
    private static double drumSample(int instr, double t, double env, PinkNoise pink, double[] f) {
        double p = pink.next(); 

        if (instr == 2) {
            
            double pitch = 130.0 - 70.0 * Math.min(1.0, t / 0.15);
            double sine = Math.sin(2 * Math.PI * Math.max(20, pitch) * t);
            double lp = lowpass(p, 0.12, f[0]); f[0] = lp;
            return (sine * 0.85 + lp * 0.15) * env;
        } else if (instr == 3) {
            
            double hp = highpass(p, 0.07, f[3]); f[3] = hp; 
            double bp = lowpass(hp, 0.45, f[1]); f[1] = bp; 
            double body = Math.sin(2 * Math.PI * 200.0 * t) * Math.exp(-t * 22);
            return (bp * 0.55 + body * 0.45) * env;
        } else { 
            double dark = lowpass(p, HAT_CUTOFF, f[2]); f[2] = dark;
            return dark * env * 0.45; 
        }
    }

    
    private static double lowpass(double in, double cutoff, double prev) {
        double a = Math.max(0, Math.min(0.99, cutoff));
        return prev + a * (in - prev);
    }

    
    private static double highpass(double in, double cutoff, double prev) {
        double lp = lowpass(in, cutoff, prev);
        return in - lp;
    }

    
    private static final class PinkNoise {
        private final Random rng;
        private double b0 = 0, b1 = 0, b2 = 0;

        PinkNoise(long seed) { this.rng = new Random(seed); }

        double next() {
            double w = rng.nextDouble() * 2 - 1;          
            b0 = 0.99765 * b0 + w * 0.0990460;
            b1 = 0.96300 * b1 + w * 0.2965164;
            b2 = 0.57000 * b2 + w * 1.0526913;
            double pink = b0 + b1 + b2 + w * 0.1848;
            return pink * 0.20; 
        }
    }

    
    private static void writeWav(File out, double[][] stereo, int sr) throws java.io.IOException {
        int frameCount = stereo[0].length;
        int dataLen = frameCount * CHANNELS * (BITS / 8);
        int totalLen = 44 + dataLen;

        ByteBuffer bb = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt(36 + dataLen);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);                    
        bb.putShort((short) 1);           
        bb.putShort((short) CHANNELS);
        bb.putInt(sr);                    
        bb.putInt(sr * CHANNELS * BITS / 8); 
        bb.putShort((short) (CHANNELS * BITS / 8)); 
        bb.putShort((short) BITS);        
        bb.put("data".getBytes());
        bb.putInt(dataLen);

        // 归一化(防止削波) —— 在已去直流/抗混叠的基础上再做峰值保护
        double peak = 0;
        for (int i = 0; i < frameCount; i++) {
            double m = Math.max(Math.abs(stereo[0][i]), Math.abs(stereo[1][i]));
            if (m > peak) peak = m;
        }
        double gain = (peak > 0.95) ? 0.95 / peak : 1.0;

        for (int i = 0; i < frameCount; i++) {
            for (int c = 0; c < CHANNELS; c++) {
                double v = Math.max(-1, Math.min(1, stereo[c][i] * gain));
                bb.putShort((short) (v * 32767));
            }
        }

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(out))) {
            dos.write(bb.array());
        }
    }

    

    private static int channelFor(int instr, int layer) {
        if (isDrum(instr)) return DRUM_CHANNEL;
        int m = ((layer % 15) + 15) % 15;
        return (m < 9) ? m : (m + 1);
    }

    private static boolean isDrum(int instr) {
        return instr == 2 || instr == 3 || instr == 4;
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

    private static int durationTicks(int instr) {
        if (isDrum(instr)) return 1;
        if (instr == 0) return 4;
        return 3;
    }

    
    private static final class WavNote {
        final double startSec, durSec;
        final int midi, instr;
        final double amp, pan;
        WavNote(double startSec, double durSec, int midi, double amp, int instr, double pan) {
            this.startSec = startSec; this.durSec = durSec;
            this.midi = midi; this.amp = amp; this.instr = instr; this.pan = pan;
        }
    }
}
