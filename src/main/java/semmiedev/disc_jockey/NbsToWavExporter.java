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

/**
 * ✅ NbsToWavExporter —— 纯 Java 合成导出真实音频 WAV（零依赖、零 SoundFont、体积不增加）
 *
 * 链路：Song(NBS) → 事件调度(复用 NbsToMidiExporter 的音高/乐器映射) → 振荡器合成 PCM → .wav
 *
 * ⚠️ 与 NbsToMidiExporter 的关系：
 *    - 音高映射(noteId→midi)、八度偏移、PITCH_CLAMP、踩镲压制、instrument 分类 全部与 Exporter 保持一致
 *    - 这里不再写 .mid，而是把同样的事件**渲染成 PCM 采样**
 *
 * ⚠️ 音色说明：无 SoundFont，用基础振荡器(正弦/锯齿/噪声) + ADSR 包络合成
 *    - 足够听清旋律/和声/节奏，可用于分享、二次转码(MP3/ogg)
 *    - 追求高质量 GM 音色请另行用导出的 .mid + 外部音源渲染
 *
 * ✅ v3 改进：彻底消除"刺啦/呲呲"失真
 *    - 白噪声 → 粉红噪声(PinkNoise, Voss-McCartney)：能量向低频倾斜，天生不刺耳
 *    - 鼓大幅去噪声化：kick 以 sine 下潜为主、hat 改为"暗帽"(低通, 彻底去掉嘶嘶)
 *    - 全局抗混叠低通(~7kHz) + 去直流：消除锯齿波/高频混叠造成的"怪声/刺啦"
 */
public class NbsToWavExporter {

    /* ==================== 音频参数 ==================== */
    private static final int SAMPLE_RATE = 44100;   // Hz
    private static final int CHANNELS = 2;           // 立体声
    private static final int BITS = 16;
    private static final double SECONDS_PER_TICK_DEFAULT = 0.1; // tempo=1000 → 10 tick/s

    /* ==================== 合成参数 ==================== */
    private static final double ATTACK = 0.006;
    private static final double DECAY = 0.18;
    private static final double SUSTAIN = 0.65;
    private static final double RELEASE = 0.10;
    private static final double TAIL_SECONDS = 0.5;  // 结尾余音

    /* ==================== 全局去刺耳(抗混叠 / 去直流) ==================== */
    /** 主低通截止系数(0~1)。0.35 ≈ 0.35*22050 ≈ 7.7kHz，滤掉刺耳超高频，保留明亮度 */
    private static final double MASTER_LOWPASS = 0.35;
    /** 鼓噪声亮度(越小越暗、越不刺耳)。hat 用，彻底告别"呲呲" */
    private static final double HAT_CUTOFF = 0.18;   // ≈ 4kHz 暗帽

    /* ==================== 与 NbsToMidiExporter 一致的映射常量 ==================== */
    private static final int MIDI_TO_NBS_OFFSET = 21;
    private static final int PITCH_CLAMP_LOW = 48;
    private static final int PITCH_CLAMP_HIGH = 96;
    private static final int DRUM_CHANNEL = 9;

    /* ==================== 力度(与 Exporter 一致) ==================== */
    private static final int VELOCITY_BASE = 120;
    private static final int VELOCITY_MIN = 25;
    private static final double VELOCITY_DECAY = 0.72;

    /* ============================================================
       ✅ 对外入口（与 NbsToMidiExporter.exportSong 同名同形，方便复用）
       ============================================================ */
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
            // 1) 解析 NBS → 渲染事件(复用映射逻辑)
            List<WavNote> events = buildEvents(song, src);
            if (events.isEmpty()) return;

            // 2) 计算总时长
            double totalSec = 0;
            for (WavNote n : events) totalSec = Math.max(totalSec, n.startSec + n.durSec);
            totalSec += TAIL_SECONDS;

            // 3) 合成 PCM
            double[][] pcm = synthesize(events, totalSec);

            // 4) 写 WAV
            if (out.getParentFile() != null && !out.getParentFile().exists()) out.getParentFile().mkdirs();
            writeWav(out, pcm, SAMPLE_RATE);

            System.out.println("[WAV Export] notes=" + events.size()
                    + " duration=" + String.format("%.1f", totalSec) + "s"
                    + " -> " + out.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("WAV export failed", e);
        }
    }

    /* ============================================================
       ✅ 第一步：NBS → 渲染事件(与 Exporter 完全一致的映射)
       ============================================================ */
    private static List<WavNote> buildEvents(Song song, long[] src) {
        double secPerTick = (song.tempo > 0)
                ? 100.0 / song.tempo   // NBS: tempo = ticks-per-second * 100
                : SECONDS_PER_TICK_DEFAULT;

        // 密度计数(用于力度衰减)
        Map<Integer, Integer> tickCount = new HashMap<>(src.length);
        for (long p : src) tickCount.merge((int) (p & 0xFFFF), 1, Integer::sum);

        List<WavNote> events = new ArrayList<>(src.length);
        for (long p : src) {
            int tick = (int) (p & 0xFFFF);
            int layer = (int) ((p >> 16) & 0xFFFF);
            int instr = (int) ((p >> 32) & 0xFF);
            int noteId = Note.extractNoteId(p);

            int midi = (noteId + 33) + MIDI_TO_NBS_OFFSET;

            // ★ 与 Exporter 一致的乐器八度偏移
            switch (instr) {
                case 1: case 11: case 12: midi -= 12; break;
                case 5:                  midi -= 12; break;
            }

            // ★ 与 Exporter 一致的音高安全区
            while (midi > PITCH_CLAMP_HIGH) midi -= 12;
            while (midi < PITCH_CLAMP_LOW)  midi += 12;
            if (midi < 0) midi = 0;
            if (midi > 127) midi = 127;

            // ★ 力度(与 Exporter 一致, 含踩镲压制)
            int simul = tickCount.getOrDefault(tick, 1);
            int vel = scaledVelocity(VELOCITY_BASE, simul);
            if (instr == 4) vel = Math.max(10, (int) (vel * 0.4)); // 踩镲压制
            double amp = vel / 127.0;

            // ★ 时长(与 Exporter durationFor 一致, 单位 tick → 秒)
            double durSec = durationTicks(instr) * secPerTick;

            // ★ 声像(与 Exporter panFor 一致)
            int ch = channelFor(instr, layer);
            double pan = (panFor(ch) - 64) / 64.0; // -1 ~ +1
            pan = Math.max(-1, Math.min(1, pan));

            events.add(new WavNote(tick * secPerTick, durSec, midi, amp, instr, pan));
        }
        Collections.sort(events, Comparator.comparingDouble(e -> e.startSec));
        return events;
    }

    /* ============================================================
       ✅ 第二步：合成 PCM(振荡器 + ADSR, 逐音符混合)
       ============================================================ */
    private static double[][] synthesize(List<WavNote> events, double totalSec) {
        int totalSamples = (int) (totalSec * SAMPLE_RATE) + 1;
        double[] left = new double[totalSamples];
        double[] right = new double[totalSamples];
        PinkNoise pink = new PinkNoise(12345); // 可复现的柔和噪声(替代刺耳白噪)

        // 鼓滤波状态: [0]=kick, [1]=snare(lp), [2]=hat, [3]=snare(hp, 用于带通)
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

            // 鼓用粉红噪声分频段, 旋律用振荡器
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
                // 声像分配
                left[s]  += v * (1 - n.pan * 0.7);
                right[s] += v * (1 + n.pan * 0.7);
            }
        }

        // ★ v3: 去直流 + 全局抗混叠低通(消除"怪声/刺啦") + 归一化
        removeDCAndAntiAlias(left, right, MASTER_LOWPASS);

        return new double[][]{left, right};
    }

    /** 去直流(DC 偏移会导致浑浊/噗噗声) + 单极低通抗混叠(去掉刺耳超高频) */
    private static void removeDCAndAntiAlias(double[] left, double[] right, double cutoff) {
        int n = left.length;
        // 1) 估算直流分量
        double dcL = 0, dcR = 0;
        for (int i = 0; i < n; i++) { dcL += left[i]; dcR += right[i]; }
        dcL /= n; dcR /= n;
        // 2) 去直流 + 主低通(单极点, 一阶)
        double lpL = 0, lpR = 0;
        double a = Math.max(0, Math.min(0.99, cutoff));
        for (int i = 0; i < n; i++) {
            double cl = left[i] - dcL;
            double cr = right[i] - dcR;
            lpL += a * (cl - lpL); left[i]  = lpL;
            lpR += a * (cr - lpR); right[i] = lpR;
        }
    }

    /** ADSR 包络 */
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

    /** 按乐器族合成音色 */
    private static double tone(double phase, int instr) {
        double s1 = Math.sin(2 * Math.PI * phase);
        double s2 = Math.sin(4 * Math.PI * phase);
        double s3 = Math.sin(6 * Math.PI * phase);
        if (instr == 0)        return s1 * 0.7 + s2 * 0.3;          // harp/piano: 柔和
        if (instr == 1)        return s1 * 0.8 + s2 * 0.2;          // bass: 低沉
        if (instr == 5)        return saw(phase) * 0.7 + s1 * 0.3;  // guitar: 锯齿
        if (instr == 6)        return s1;                            // flute: 纯正弦
        if (instr == 7)        return s1 + s3 * 0.6;                // bell: 明亮谐波
        if (instr == 8)        return s1 + s3 * 0.5;                // chime
        if (instr == 9)        return s1 * 0.6 + s2 * 0.4;          // xylophone
        if (instr == 11 || instr == 12) return s1 * 0.8 + s2 * 0.2; // didgeridoo/bit: bassy
        if (instr == 13)       return s1 * 0.5 + s2 * 0.5;          // cow_bell
        return s1 * 0.6 + s2 * 0.25 + s3 * 0.15;                    // 默认
    }

    /** 锯齿波 */
    private static double saw(double phase) {
        double f = phase - Math.floor(phase); // 0~1
        return 2.0 * f - 1.0;
    }

    /* ============================================================
       ✅ 鼓点合成(v3: 粉红噪声 + 大幅去噪声化, 彻底告别"呲呲/刺啦")
       ============================================================ */

    /**
     * 不同鼓分频段处理(全部基于柔和的粉红噪声):
     *   instr 2 (kick):  低频 sine 下潜(主体, 0.85) + 极少量低通噪声 → "咚"，几乎无嘶嘶
     *   instr 3 (snare): 带通粉红噪声(中频) + tonal body → "哒"
     *   instr 4 (hat):   暗帽 = 低通粉红噪声(≈4kHz)，电平压低 → 无高频嘶嘶
     */
    private static double drumSample(int instr, double t, double env, PinkNoise pink, double[] f) {
        double p = pink.next(); // 粉红噪声: 能量偏低频, 天然柔和(不像白噪那样刺耳)

        if (instr == 2) {
            // kick: 主体是低频 sine 下潜(130→60Hz), 噪声只占 15% 且被低通压暗
            double pitch = 130.0 - 70.0 * Math.min(1.0, t / 0.15);
            double sine = Math.sin(2 * Math.PI * Math.max(20, pitch) * t);
            double lp = lowpass(p, 0.12, f[0]); f[0] = lp;
            return (sine * 0.85 + lp * 0.15) * env;
        } else if (instr == 3) {
            // snare: 带通(去超低 + 去超高)粉红噪声 + 中低频 tonal body
            double hp = highpass(p, 0.07, f[3]); f[3] = hp; // 去超低, 避免糊
            double bp = lowpass(hp, 0.45, f[1]); f[1] = bp; // 保留中频
            double body = Math.sin(2 * Math.PI * 200.0 * t) * Math.exp(-t * 22);
            return (bp * 0.55 + body * 0.45) * env;
        } else { // instr == 4, hat: 暗帽, 彻底去掉嘶嘶
            double dark = lowpass(p, HAT_CUTOFF, f[2]); f[2] = dark;
            return dark * env * 0.45; // 电平压低, 不刺耳
        }
    }

    /** 一阶低通滤波(截止系数 cutoff: 0~1, 越大越暗)。prev 为上一采样状态(原地更新需调用方回写) */
    private static double lowpass(double in, double cutoff, double prev) {
        double a = Math.max(0, Math.min(0.99, cutoff));
        return prev + a * (in - prev);
    }

    /** 一阶高通滤波(去低频) */
    private static double highpass(double in, double cutoff, double prev) {
        double lp = lowpass(in, cutoff, prev);
        return in - lp;
    }

    /* ============================================================
       ✅ 粉红噪声(Voss-McCartney, Paul Kellet 一阶近似)
       —— 相比白噪声, 能量按 ~3dB/oct 向低频倾斜, 听感温暖、不刺耳
       ============================================================ */
    private static final class PinkNoise {
        private final Random rng;
        private double b0 = 0, b1 = 0, b2 = 0;

        PinkNoise(long seed) { this.rng = new Random(seed); }

        double next() {
            double w = rng.nextDouble() * 2 - 1;          // 白噪声 (-1~1)
            b0 = 0.99765 * b0 + w * 0.0990460;
            b1 = 0.96300 * b1 + w * 0.2965164;
            b2 = 0.57000 * b2 + w * 1.0526913;
            double pink = b0 + b1 + b2 + w * 0.1848;
            return pink * 0.20; // 缩放到约 ±0.5, 避免过载
        }
    }

    /* ============================================================
       ✅ 第三步：写标准 RIFF/WAVE (PCM 16-bit 小端, 立体声)
       ============================================================ */
    private static void writeWav(File out, double[][] stereo, int sr) throws java.io.IOException {
        int frameCount = stereo[0].length;
        int dataLen = frameCount * CHANNELS * (BITS / 8);
        int totalLen = 44 + dataLen;

        ByteBuffer bb = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt(36 + dataLen);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);                    // fmt chunk size
        bb.putShort((short) 1);           // PCM format
        bb.putShort((short) CHANNELS);
        bb.putInt(sr);                    // sample rate
        bb.putInt(sr * CHANNELS * BITS / 8); // byte rate
        bb.putShort((short) (CHANNELS * BITS / 8)); // block align
        bb.putShort((short) BITS);        // bits per sample
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

    /* ==================== 与 Exporter 一致的辅助方法 ==================== */

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

    /* ==================== 渲染事件数据结构 ==================== */
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
