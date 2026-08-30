package semmiedev.disc_jockey;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import semmiedev.disc_jockey.gui.SongListWidget;

public class Song {

    /** ✅ 原始 NBS 音符（永不修改，永不写盘） */
    public long[] notes = new long[0];

    /** ✅ 运行时八度折叠缓存（由 NoteClamper 生成，播放优先使用） */
    public long[] foldedNotes = null;

    public short length;
    public short height;
    public short tempo;
    public short loopStartTick;

    public String fileName;
    public String name;
    public String author;
    public String originalAuthor;
    public String description;
    public String displayName;

    public byte autoSaving;
    public byte autoSavingDuration;
    public byte timeSignature;
    public byte vanillaInstrumentCount;
    public byte formatVersion;
    public byte loop;
    public byte maxLoopCount;

    public int minutesSpent;
    public int leftClicks;
    public int rightClicks;
    public int blocksAdded;
    public int blocksRemoved;

    public String importFileName;

    public final ArrayList<Note> uniqueNotes = new ArrayList<>();

    public SongListWidget.SongEntry entry;

    public String searchableFileName;
    public String searchableName;

    @Override
    public String toString() {
        return this.displayName;
    }

    public double millisecondsToTicks(long milliseconds) {
        double songSpeed = this.tempo / 100.0D / 20.0D;
        double oneMsTo20TickFraction = 0.02D;
        return milliseconds * oneMsTo20TickFraction * songSpeed;
    }

    public double ticksToMilliseconds(double ticks) {
        double songSpeed = this.tempo / 100.0D / 20.0D;
        double oneMsTo20TickFraction = 0.02D;
        return ticks / oneMsTo20TickFraction / songSpeed;
    }

    public double getLengthInSeconds() {
        return ticksToMilliseconds(this.length) / 1000.0D;
    }

    /* =========================================================
       ✅✅✅ save(File) —— 写成 .nbs，与 SongLoader/BinaryReader 完全对称
       ✅ 字节序：LITTLE_ENDIAN（BinaryReader 是 LITTLE_ENDIAN）
       ✅ noteId 存盘：字节 = noteId + 33（NBS 官方，读回 -33 还原）
       ✅ 格式：旧格式（length>0，头部不含 formatVersion 等，音符区无额外字节）
       ✅ 音符区：外层 tick jump / 内层 layer jump，均从 -1 起算
       ========================================================= */
    public void save(File out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(0);

            long[] src = (notes != null && notes.length > 0) ? notes : foldedNotes;
            if (src == null) src = new long[0];

            List<NoteData> all = new ArrayList<>(src.length);
            for (long packed : src) all.add(NoteData.fromPacked(packed));
            all.sort(Comparator.comparingInt((NoteData n) -> n.tick)
                    .thenComparingInt(n -> n.layer));

            int headerLength = (this.length > 0) ? (this.length & 0xFFFF) : computeMaxTick(all);

            // ===== 头部（顺序与 SongLoader.loadSong 逐字段对称）=====
            // ★★★ 旧格式：length > 0（不走 newFormat 分支）★★★
            writeShortLE(raf, headerLength);       // length
            writeShortLE(raf, this.height & 0xFFFF);
            writeNbsStringLE(raf, this.name);
            writeNbsStringLE(raf, this.author);
            writeNbsStringLE(raf, this.originalAuthor);
            writeNbsStringLE(raf, this.description);
            writeShortLE(raf, this.tempo & 0xFFFF);
            writeByte(raf, this.autoSaving);
            writeByte(raf, this.autoSavingDuration);
            writeByte(raf, this.timeSignature);
            writeIntLE(raf, this.minutesSpent);
            writeIntLE(raf, this.leftClicks);
            writeIntLE(raf, this.rightClicks);
            writeIntLE(raf, this.blocksAdded);
            writeIntLE(raf, this.blocksRemoved);
            writeNbsStringLE(raf, this.importFileName);
            // ★ 旧格式：此处不含 formatVersion / vanillaInstrumentCount / loop / maxLoopCount / loopStartTick
            //   （SongLoader 只在 newFormat 时才读这些）

            // ===== 音符区（旧格式：每条只写 instrument + noteIdRaw，无额外字节）=====
            writeNotesLE(raf, all, headerLength);
        }
    }

    /** 音符区：外层 tick jump / 内层 layer jump，均从 -1 起算，各自以 0 结束 */
    private static void writeNotesLE(RandomAccessFile raf, List<NoteData> all, int headerLength) throws IOException {
        if (all.isEmpty()) { writeShortLE(raf, 0); return; }

        Map<Integer, List<NoteData>> byTick = new HashMap<>();
        for (NoteData n : all) byTick.computeIfAbsent(n.tick, k -> new ArrayList<>()).add(n);

        List<Integer> tickKeys = new ArrayList<>(byTick.keySet());
        tickKeys.sort(Comparator.naturalOrder());

        int lastTick = -1;
        for (int t : tickKeys) {
            List<NoteData> layerList = byTick.get(t);
            writeShortLE(raf, (t - lastTick) & 0xFFFF); // 外层：tick jump
            lastTick = t;

            layerList.sort(Comparator.comparingInt(n -> n.layer));
            int lastLayer = -1;
            for (NoteData n : layerList) {
                writeShortLE(raf, (n.layer - lastLayer) & 0xFFFF); // 内层：layer jump
                lastLayer = n.layer;
                writeByte(raf, n.instrument & 0xFF);
                writeByte(raf, noteIdToRaw(n.noteId));  // ★ noteId + 33（NBS 官方）
            }
            writeShortLE(raf, 0); // 内层结束（layer jump = 0）
        }
        writeShortLE(raf, 0); // 外层结束（tick jump = 0）
    }

    /** ★★★ noteId → 存盘字节：+33（NBS 官方，与 SongLoader 的 -33 互逆）★★★ */
    private static int noteIdToRaw(int noteId) {
        return (noteId + 33) & 0xFF;
    }

    private static int computeMaxTick(List<NoteData> list) {
        int max = 0;
        for (NoteData n : list) if (n.tick > max) max = n.tick;
        return max + 1;
    }

    /* ---- LITTLE_ENDIAN 写入工具（与 BinaryReader 对称）---- */
    private static void writeByte(RandomAccessFile raf, int v) throws IOException {
        raf.writeByte(v & 0xFF);
    }
    private static void writeShortLE(RandomAccessFile raf, int v) throws IOException {
        raf.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) (v & 0xFFFF)).array());
    }
    private static void writeIntLE(RandomAccessFile raf, int v) throws IOException {
        raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array());
    }
    private static void writeNbsStringLE(RandomAccessFile raf, String s) throws IOException {
        if (s == null) s = "";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeIntLE(raf, bytes.length);   // ★ int 长度（小端），与 BinaryReader.readString = readInt+readBytes 对称
        raf.write(bytes);
    }

    /** 从 packed 解码（与 SongLoader 读回 packed 值完全一致） */
    private static final class NoteData {
        final int tick, layer, instrument, noteId;
        NoteData(int tick, int layer, int instrument, int noteId) {
            this.tick = tick; this.layer = layer; this.instrument = instrument; this.noteId = noteId;
        }
        static NoteData fromPacked(long packed) {
            int tick       = (int)  (packed & 0xFFFFL);
            int layer      = (int) ((packed >>> 16) & 0xFFFFL);
            int instrument = (int) ((packed >>> 32) & 0xFFL);
            int noteId     = (int) ((packed >>> 40) & 0xFFL);  // ★ 原始有符号值（SongLoader 用 Note.extractNoteId）
            // ✅ 若你的 Note.extractNoteId 做有符号还原（-128→负数），则用：
            // noteId = Note.extractNoteId(packed);
            return new NoteData(tick, layer, instrument, noteId);
        }
    }
}