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

    // ★ 无参构造：兜底默认值（修 new Song() 字段为 0 → Create Event 崩溃）
    public Song() {
        ensureDefaults();
    }

    /**
     * ★★★ 兜底默认值：保证写出/加载字段有效 ★★★
     *
     * 关键点：vanillaInstrumentCount 绝不能为 0。
     * DJ/GML 的 blocks_set_instruments 用它建乐器表，为 0 → 表空 → undefined → 崩溃。
     * 实测正常文件 vanillaInstrumentCount = 10（原版 0-9）。
     */
    public void ensureDefaults() {
        if (notes == null) notes = new long[0];
        if (fileName == null) fileName = "";
        if (name == null) name = "";
        if (displayName == null) displayName = "";
        if (author == null) author = "";
        if (originalAuthor == null) originalAuthor = "";
        if (description == null) description = "";
        if (importFileName == null) importFileName = "";
        if (searchableFileName == null) searchableFileName = "";
        if (searchableName == null) searchableName = "";

        if (vanillaInstrumentCount == 0) vanillaInstrumentCount = 10;  // 原版 0-9 共 10 个
        if (formatVersion == 0) formatVersion = 0;                      // 标准 NBS 旧格式 = 0
        if (height <= 0) height = 1;                                    // ★ 至少 1 层（save 里还会按 maxLayer+1 校正）
        if (tempo <= 0) tempo = 1000;                                   // ≈ BPM 10
        if (timeSignature == 0) timeSignature = 4;
        if (loopStartTick < 0) loopStartTick = 0;
    }
    public void ensureNames(String baseName) {
        if (baseName == null) baseName = "";
        String noExt = stripExt(baseName);

        if (this.name == null || this.name.isEmpty()) this.name = noExt;
        if (this.displayName == null || this.displayName.isEmpty())
            this.displayName = this.name.isEmpty() ? noExt : this.name;
        String fn = (this.fileName == null) ? "" : this.fileName.trim();
        if (fn.isEmpty()) fn = baseName;
        if (!fn.toLowerCase().endsWith(".nbs")) fn += ".nbs";
        this.fileName = fn;

        if (this.searchableFileName == null || this.searchableFileName.isEmpty())
            this.searchableFileName = fn.toLowerCase();
        if (this.searchableName == null || this.searchableName.isEmpty())
            this.searchableName = this.name.toLowerCase();
    }

    private static String stripExt(String name) {
        return name == null ? "" : name.replaceAll("\\.(?i)(nbs|mid)$", "");
    }

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
       ✅✅✅ save(File) —— 写成「标准 NBS」（与 SongLoader / Note Block Studio 一致）
       ✅ 字节序：LITTLE_ENDIAN
       ✅ noteId 存盘：字节 = noteId + 33（NBS 官方，读回 -33 还原）
       ✅ 格式：旧格式（length = maxTick+1 > 0，不走 newFormat 分支）
       ✅ 音符区：外层/内层 jump 编码，均从 -1 起算，各以 0 结束
       ✅ 图层区：每条 = int长度(4) + "Grand Piano"(11字节) + volume(1) = 16 字节定长
       ✅ 文件尾：自定义乐器数量 = 0（writeIntLE(raf, 0)），与实测正常文件一致
       ========================================================= */
    public void save(File out) throws IOException {
        if (out != null) {
            boolean internalNameValid = this.fileName != null
                    && !this.fileName.trim().isEmpty()
                    && !this.fileName.contains("_____");
            if (!internalNameValid) {
                ensureNames(out.getName());   // 用 out 名兜底各字段（幂等）
            }
            // 规范化：去后缀 + 强制 .nbs
            String base = stripExt(this.fileName);
            if (base.isEmpty()) base = (this.name != null && !this.name.isEmpty()) ? this.name : "song";
            this.fileName = base + ".nbs";
            if (this.displayName == null || this.displayName.isEmpty()
                    || this.displayName.contains("_____")) {
                this.displayName = this.name.isEmpty() ? base : this.name;
            }
            // 磁盘文件名 = 规范化后的 fileName
            if (!out.getName().equals(this.fileName)) {
                out = new File(out.getParentFile(), this.fileName);
            }
        }

        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(0);

            long[] src = (notes != null && notes.length > 0) ? notes : foldedNotes;
            if (src == null) src = new long[0];

            List<NoteData> all = new ArrayList<>(src.length);
            for (long packed : src) all.add(NoteData.fromPacked(packed));
            all.sort(Comparator.comparingInt((NoteData n) -> n.tick)
                    .thenComparingInt(n -> n.layer));

            // ★★★ 核心校正：height 必须 >= maxLayer + 1 ★★★
            // 实测 _____.nbs：header.height=12 但音符引用 layer 0~4049。
            // 若 height < maxLayer+1，DJ/GML 按 height 建的乐器表对高 layer 音符越界
            // → blocks_set_instruments 收到 undefined → 崩溃 "REAL argument incorrect type undefined"。
            // 校正后 height 覆盖所有实际图层，彻底消除越界。
            int maxLayer = computeMaxLayer(all);
            int neededHeight = maxLayer + 1;
            if ((this.height & 0xFFFF) < neededHeight) {
                this.height = (short) Math.max(1, Math.min(Short.MAX_VALUE, neededHeight));
            }

            int headerLength = Math.max(1, computeMaxTick(all));

            // ===== 头部（顺序与 SongLoader.loadSong 逐字段对称）=====
            // ★ 旧格式：length > 0（不走 newFormat 分支）
            writeShortLE(raf, headerLength);       // length（= maxTick + 1）
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
            //   （SongLoader 只在 newFormat 时才读这些；实测正常文件也是旧格式）

            // ===== 音符区 =====
            writeNotesLE(raf, all, headerLength);

            // ===== 图层区（Layers）=====
            // 每条 = int长度(4) + "Grand Piano"(11字节) + volume(1字节) = 16 字节定长
            // 与实测正常文件尾部结构完全一致（逐条 "0B 00 00 00 Grand Pianod"）
            writeLayersLE(raf, this.height & 0xFFFF);

            // ===== 自定义乐器数量 = 0 =====
            // 与实测正常文件末尾 "00 00 00 00" 一致（防加载器读到垃圾值）
            writeIntLE(raf, 0);
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

    /**
     * ★ 图层区：按 height 逐条写出，每条 = int长度(4) + "Grand Piano"(11字节) + volume(1字节) = 16 字节定长
     *
     * 实测正常文件每条正是这个布局（"0B 00 00 00"=长度11 + "Grand Piano" + 0x64='d'=volume）。
     * save() 已校正 height = maxLayer + 1，故此处条数一定覆盖所有被引用的图层，不会越界。
     */
    private static void writeLayersLE(RandomAccessFile raf, int layerCount) throws IOException {
        if (layerCount < 1) layerCount = 1;
        for (int i = 0; i < layerCount; i++) {
            writeNbsStringLE(raf, "Grand Piano"); // int长度(4) + 名称(11字节)
            writeByte(raf, 100);                  // volume = 100（0x64 = 'd'）
        }
    }

    /** noteId → 存盘字节：+33（NBS 官方，与 SongLoader 的 -33 互逆） */
    private static int noteIdToRaw(int noteId) {
        return (noteId + 33) & 0xFF;
    }

    private static int computeMaxTick(List<NoteData> list) {
        int max = 0;
        for (NoteData n : list) if (n.tick > max) max = n.tick;
        return max + 1;
    }

    /** ★ 计算实际最大图层索引（校正 height 用） */
    private static int computeMaxLayer(List<NoteData> list) {
        int max = 0;
        for (NoteData n : list) if (n.layer > max) max = n.layer;
        return max;
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
        writeIntLE(raf, bytes.length);   // int 长度（小端），与 BinaryReader.readString = readInt+readBytes 对称
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
            // ★ 用 Note.extractNoteId 做有符号还原（与 Note.packNoteId 写入对称，修负数音符 -1→255 错音高）
            int noteId     = Note.extractNoteId(packed);
            return new NoteData(tick, layer, instrument, noteId);
        }
    }
}
