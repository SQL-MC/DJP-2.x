package semmiedev.disc_jockey;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

/* =========================================================
   ✅ 完整覆盖版 NbsToMidiExporter
   ✅ 修复：移除 Main.mc 相关代码，避免编译失败
   ✅ 导出成功仅通过 Logger 输出完整路径
   ✅【新增】拍号（Time Signature）+ 小节线（Bar Lines）
   ✅ 其余逻辑 100% 保留，未删减
   ========================================================= */
public class NbsToMidiExporter {

    private static final int DEFAULT_TEMPO_BPM = 120;
    private static final int TICKS_PER_QUARTER = 480;

    /* =========================================================
       ✅ exportAll：批量导出（原有逻辑，未改动）
       ========================================================= */
    public static void exportAll() {
        for (Song song : SongLoader.SONGS) {
            try {
                exportSong(song);
            } catch (Exception e) {
                Main.LOGGER.error("Failed to export MIDI for {}", song.displayName, e);
            }
        }
    }

    private static final int NOTE_ON  = 144;  // 0x90
    private static final int NOTE_OFF = 128;  // 0x80
    private static final int VELOCITY = 80;

    /* =========================================================
       ✅ exportSong：核心导出方法
       ✅【新增】拍号 + 小节线
       ========================================================= */
    public static void exportSong(Song song) throws IOException {
        if (song == null || song.notes == null || song.notes.length == 0) {
            return;
        }

        String safeName = ((song.displayName != null) ? song.displayName : song.fileName)
                .replaceAll("[^a-zA-Z0-9_\\-\\s]", "_");

        File outputDir = new File(Main.songsFolder.getParentFile(), "midi");
        if (!outputDir.exists()) outputDir.mkdirs();

        File outputFile = new File(outputDir, safeName + ".mid");

        try {
            Sequence sequence = new Sequence(Sequence.PPQ, TICKS_PER_QUARTER);

            double bpm = song.tempo / 100.0D;
            if (bpm <= 0.0D) bpm = DEFAULT_TEMPO_BPM;

            /* ---------- 速度轨道 ---------- */
            Track tempoTrack = sequence.createTrack();
            MetaMessage tempoMsg = createTempoMessage(bpm);
            tempoTrack.add(new MidiEvent(tempoMsg, 0L));

            /* =========================================================
               ✅【新增】拍号（4/4）
               ========================================================= */
            MetaMessage timeSigMsg = createTimeSignature(4, 2);
            tempoTrack.add(new MidiEvent(timeSigMsg, 0L));

            MetaMessage nameMsg = new MetaMessage();
            nameMsg.setMessage(0x03,
                    ((song.displayName != null) ? song.displayName : song.fileName)
                            .getBytes(StandardCharsets.UTF_8),
                    0);
            tempoTrack.add(new MidiEvent(nameMsg, 0L));

            /* ---------- 音符轨道 ---------- */
            Track noteTrack = sequence.createTrack();

            double tickRatio = (double) TICKS_PER_QUARTER / bpm / 60.0D / 20.0D;
            long ticksPerBar = (long) (tickRatio * 20.0D * 4.0D); // 4 beats per bar
            int barCount = 1;

            for (long packed : song.notes) {
                int tick         = (int)  (packed & 0xFFFFL);
                int layer        = (int) ((packed >> 16) & 0xFFFFL);
                int instrumentId = (int) ((packed >> 32) & 0xFFL);
                int noteId       = (int) ((packed >> 40) & 0xFFL);

                int midiNote = 57 + noteId;
                long midiTick = (long) (tick * tickRatio);
                long duration = (long) (tickRatio * 2.0D);
                if (duration < 1L) duration = 1L;

                int channel = Math.min(layer % 16, 15);

                ShortMessage noteOn = new ShortMessage();
                noteOn.setMessage(NOTE_ON | channel, midiNote, VELOCITY);
                noteTrack.add(new MidiEvent(noteOn, midiTick));

                ShortMessage noteOff = new ShortMessage();
                noteOff.setMessage(NOTE_OFF | channel, midiNote, 0);
                noteTrack.add(new MidiEvent(noteOff, midiTick + duration));

                /* =========================================================
                   ✅【新增】小节线标记（每 4 拍）
                   ========================================================= */
                if (tick > 0 && tick % (20 * 4) == 0) {
                    MetaMessage barMarker = createBarMarker(barCount++);
                    noteTrack.add(new MidiEvent(barMarker, midiTick));
                }
            }

            /* ---------- 写入文件 ---------- */
            MidiSystem.write(sequence, 1, outputFile);

            Main.LOGGER.info("[DJ] Exported MIDI: {}", outputFile.getAbsolutePath());

        } catch (InvalidMidiDataException e) {
            throw new IOException("Invalid MIDI data", e);
        }
    }

    /* =========================================================
       ✅ 创建速度（Tempo）MetaMessage
       ========================================================= */
    private static MetaMessage createTempoMessage(double bpm)
            throws InvalidMidiDataException {
        int microsecondsPerQuarter = (int) (6.0E7D / bpm);
        byte[] data = new byte[3];
        data[0] = (byte) ((microsecondsPerQuarter >> 16) & 0xFF);
        data[1] = (byte) ((microsecondsPerQuarter >> 8)  & 0xFF);
        data[2] = (byte)  (microsecondsPerQuarter        & 0xFF);
        MetaMessage msg = new MetaMessage();
        msg.setMessage(0x51, data, 3);
        return msg;
    }

    /* =========================================================
       ✅【新增】创建拍号（Time Signature）MetaMessage
       ========================================================= */
    private static MetaMessage createTimeSignature(int numerator, int denominatorPower)
            throws InvalidMidiDataException {
        byte[] data = new byte[4];
        data[0] = (byte) numerator;             // 分子
        data[1] = (byte) denominatorPower;      // 分母幂次（2 = 4分音符）
        data[2] = 24;                           // 每拍 MIDI clocks
        data[3] = 8;                            // 每 24 clocks 的 32nd notes
        MetaMessage msg = new MetaMessage();
        msg.setMessage(0x58, data, 4);
        return msg;
    }

    /* =========================================================
       ✅【新增】创建小节线标记（Bar Marker）
       ========================================================= */
    private static MetaMessage createBarMarker(int barNumber)
            throws InvalidMidiDataException {
        String text = "BAR:" + barNumber;
        MetaMessage msg = new MetaMessage();
        msg.setMessage(0x01, text.getBytes(StandardCharsets.UTF_8), text.length());
        return msg;
    }
}