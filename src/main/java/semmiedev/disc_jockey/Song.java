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

    /** NBS_V4 固定：原版乐器数量（Harp..Pling = 16） */
    public static final int VANILLA_INST_COUNT = 20;


    /**
     * NBS 自定义乐器（文件第 4 段，与 NoteBlockStudio 完全同款机制）。
     * file 字段我们用来存 Minecraft 声音事件 id（如 pianolib:a4 / minecraft:entity.player.death）。
     * press 字段复用为音高模式：0 = 跟随音高，1 = 原声（pitch = 1.0）。
     */
    public static final class CustomInstrument {
        public String name;
        public String file;
        public byte pitch;
        public byte press;

        public CustomInstrument() { this("", "", (byte) 45, (byte) 0); }

        public CustomInstrument(String name, String file, byte pitch, byte press) {
            this.name = name == null ? "" : name;
            this.file = file == null ? "" : file;
            this.pitch = pitch;
            this.press = press;
        }

        @Override
        public String toString() { return name + " -> " + file; }
    }

    /** NBS 第 4 段：自定义乐器列表。下标 i 对应音符 instrument = vanillaInstrumentCount + i */
    public final ArrayList<CustomInstrument> customInstruments = new ArrayList<>();

    /** instrument 字节是否是自定义乐器 */
    public boolean isCustomInstrument(int instrumentId) {
        int id = instrumentId & 0xFF;
        if (id >= 16 && id <= 19) return false;
        int vc = (vanillaInstrumentCount > 0) ? (vanillaInstrumentCount & 0xFF) : 10;
        return id >= vc;
    }

    /** 取 instrument 字节对应的自定义乐器，非自定义或越界返回 null */
    public CustomInstrument customInstrumentOf(int instrumentId) {
        if (!isCustomInstrument(instrumentId)) return null;
        int vc = (vanillaInstrumentCount > 0) ? (vanillaInstrumentCount & 0xFF) : 10;
        int idx = (instrumentId & 0xFF) - vc;
        if (idx < 0 || idx >= customInstruments.size()) return null;
        return customInstruments.get(idx);
    }

    /**
     * 登记一个自定义乐器（同声音 id + 同音高模式复用同一条），返回它的 instrument 字节。
     * 超出 255 时返回 -1。
     */
    public int addOrGetCustomInstrument(String soundId, int press) {
        // 自定义乐器一律按 NBS_V4 的 16 个原版乐器之后编号
        this.vanillaInstrumentCount = (byte) VANILLA_INST_COUNT;
        int vc = VANILLA_INST_COUNT;
        byte p = (byte) press;
        String sid = (soundId == null) ? "" : soundId;
        for (int i = 0; i < customInstruments.size(); i++) {
            CustomInstrument c = customInstruments.get(i);
            if (c != null && sid.equals(c.file) && c.press == p) return vc + i;
        }
        if (customInstruments.size() >= (255 - vc)) return -1;
        String nm = sid;
        int slash = nm.indexOf(':');
        if (slash >= 0 && slash + 1 < nm.length()) nm = nm.substring(slash + 1);
        customInstruments.add(new CustomInstrument(nm, sid, (byte) 45, p));
        return vc + customInstruments.size() - 1;
    }

    public String relativePath = "";

    public Lyrics lyrics = null;

    public long[] notes = new long[0];

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

    /** 调号，从音符音高推算。例如 "C", "Am", "F#m"。无法判断时为 null。 */
    public String keySignature = null;

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

    public Song() {
        ensureDefaults();
    }

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

        if (vanillaInstrumentCount == 0) vanillaInstrumentCount = 20;
        if (formatVersion == 0) formatVersion = 0;
        if (height <= 0) height = 1;
        if (tempo <= 0) tempo = 1000;
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

    /**
     * 根据已加载的音符推算调号（Krumhansl-Schmuckler 简化版）。
     * 应在 {@link #notes} 填充完毕后调用一次。结果写入 {@link #keySignature}。
     * <p>
     * NBS 里 noteId 的范围是 -33..54，MIDI 音高 = noteId + 33，故有效 MIDI 为 0..87。
     * 统计 12 个音级的加权出现次数（高八度轻微衰减），再与大小调剖面做循环相关，
     * 取相关性最高的 tonic+mode 作为调号。相关性低于阈值则判为无法确定。
     */
    public void detectKeySignature() {
        this.keySignature = null;
        if (this.notes == null || this.notes.length == 0) return;

        double[] pitchClass = new double[12];
        for (long n : this.notes) {
            int key = (int) ((n >> 41) & 0xFF);
            int pc = (key - 33) % 12;
            if (pc < 0) pc += 12;
            pitchClass[pc] += 1.0;
        }

        double sum = 0;
        for (double d : pitchClass) sum += d;
        if (sum <= 0) return;
        for (int i = 0; i < 12; i++) pitchClass[i] /= sum;

        String[] names = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        double bestCorr = -1;
        String bestKey = null;
        for (int tonic = 0; tonic < 12; tonic++) {
            double cM = correlation(pitchClass, MAJOR_PROFILE, tonic);
            double cN = correlation(pitchClass, MINOR_PROFILE, tonic);
            if (cM > bestCorr) { bestCorr = cM; bestKey = names[tonic] + "maj"; }
            if (cN > bestCorr) { bestCorr = cN; bestKey = names[tonic] + "min"; }
        }
        this.keySignature = bestKey;
    }
    private static final double[] MAJOR_PROFILE = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR_PROFILE = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    /** 把音级分布按 tonic 平移后与参考剖面做余弦相似度。 */
    private static double correlation(double[] profile, double[] ref, int shift) {
        double dot = 0, magP = 0, magR = 0;
        for (int i = 0; i < 12; i++) {
            double p = profile[(i + shift) % 12];
            double r = ref[i];
            dot += p * r;
            magP += p * p;
            magR += r * r;
        }
        if (magP == 0 || magR == 0) return 0;
        return dot / (Math.sqrt(magP) * Math.sqrt(magR));
    }

    public void save(File out) throws IOException {
        if (out != null) {
            boolean internalNameValid = this.fileName != null
                    && !this.fileName.trim().isEmpty()
                    && !this.fileName.contains("_____");
            if (!internalNameValid) {
                ensureNames(out.getName());
            }

            String base = stripExt(this.fileName);
            if (base.isEmpty()) base = (this.name != null && !this.name.isEmpty()) ? this.name : "song";
            this.fileName = base + ".nbs";
            if (this.displayName == null || this.displayName.isEmpty()
                    || this.displayName.contains("_____")) {
                this.displayName = this.name.isEmpty() ? base : this.name;
            }

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

            // 若 height < maxLayer+1，DJ/GML 按 height 建的乐器表对高 layer 音符越界
            // → blocks_set_instruments 收到 undefined → 崩溃 "REAL argument incorrect type undefined"。
            int maxLayer = computeMaxLayer(all);
            int neededHeight = maxLayer + 1;
            if ((this.height & 0xFFFF) < neededHeight) {
                this.height = (short) Math.max(1, Math.min(Short.MAX_VALUE, neededHeight));
            }

            int headerLength = Math.max(1, computeMaxTick(all));

            // ★ NBS v4 新格式头：0 + version(4) + vanillaInstrumentCount(16) + length
            byte version = 4;
            byte vanillaCount = (byte) VANILLA_INST_COUNT;   // NBS_V4 固定 16 个原版乐器
            this.formatVersion = version;
            this.vanillaInstrumentCount = vanillaCount;

            writeShortLE(raf, 0);                 // 新格式标记
            writeByte(raf, version & 0xFF);       // formatVersion = 4
            writeByte(raf, vanillaCount & 0xFF);  // vanillaInstrumentCount = 16
            writeShortLE(raf, headerLength);
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
            // ★ v4 头尾字段
            writeByte(raf, this.loop);
            writeByte(raf, this.maxLoopCount);
            writeShortLE(raf, this.loopStartTick & 0xFFFF);

            writeNotesV4(raf, all, headerLength);
            writeLayersV4(raf, this.height & 0xFFFF);

            // ★ NBS 第 4 段：自定义乐器（NoteBlockStudio 读的就是这里）
            writeCustomInstruments(raf);
        }
    }

    private static void writeNotesLE(RandomAccessFile raf, List<NoteData> all, int headerLength) throws IOException {
        if (all.isEmpty()) { writeShortLE(raf, 0); return; }

        Map<Integer, List<NoteData>> byTick = new HashMap<>();
        for (NoteData n : all) byTick.computeIfAbsent(n.tick, k -> new ArrayList<>()).add(n);

        List<Integer> tickKeys = new ArrayList<>(byTick.keySet());
        tickKeys.sort(Comparator.naturalOrder());

        int lastTick = -1;
        for (int t : tickKeys) {
            List<NoteData> layerList = byTick.get(t);
            writeShortLE(raf, (t - lastTick) & 0xFFFF);
            lastTick = t;

            layerList.sort(Comparator.comparingInt(n -> n.layer));
            int lastLayer = -1;
            for (NoteData n : layerList) {
                writeShortLE(raf, (n.layer - lastLayer) & 0xFFFF);
                lastLayer = n.layer;
                writeByte(raf, n.instrument & 0xFF);
                writeByte(raf, noteIdToRaw(n.noteId));
            }
            writeShortLE(raf, 0);
        }
        writeShortLE(raf, 0);
    }

    /** v4 音符：instrument(1) + key(1) + velocity(1) + panning(1) + pitch(2) */
    private static void writeNotesV4(RandomAccessFile raf, List<NoteData> all, int headerLength) throws IOException {
        if (all.isEmpty()) { writeShortLE(raf, 0); return; }

        Map<Integer, List<NoteData>> byTick = new HashMap<>();
        for (NoteData n : all) byTick.computeIfAbsent(n.tick, k -> new ArrayList<>()).add(n);

        List<Integer> tickKeys = new ArrayList<>(byTick.keySet());
        tickKeys.sort(Comparator.naturalOrder());

        int lastTick = -1;
        for (int t : tickKeys) {
            List<NoteData> layerList = byTick.get(t);
            writeShortLE(raf, (t - lastTick) & 0xFFFF);
            lastTick = t;

            layerList.sort(Comparator.comparingInt(n -> n.layer));
            int lastLayer = -1;
            for (NoteData n : layerList) {
                writeShortLE(raf, (n.layer - lastLayer) & 0xFFFF);
                lastLayer = n.layer;
                writeByte(raf, n.instrument & 0xFF);
                writeByte(raf, noteIdToRaw(n.noteId));
                writeByte(raf, 100);          // velocity
                writeByte(raf, 100);          // panning（100 = 居中）
                writeShortLE(raf, 0);         // pitch 微调 = 0
            }
            writeShortLE(raf, 0);
        }
        writeShortLE(raf, 0);
    }

    /** v4 图层：name + lock(1) + volume(1) + stereo(1) */
    private static void writeLayersV4(RandomAccessFile raf, int layerCount) throws IOException {
        if (layerCount < 1) layerCount = 1;
        for (int i = 0; i < layerCount; i++) {
            writeNbsStringLE(raf, "Grand Piano");
            writeByte(raf, 0);      // lock
            writeByte(raf, 100);    // volume
            writeByte(raf, 100);    // stereo（100 = 居中）
        }
    }

    /** 第 4 段：byte 数量 + 每项 [name, file, pitch, press] */
    private void writeCustomInstruments(RandomAccessFile raf) throws IOException {
        List<CustomInstrument> list = (customInstruments == null) ? new ArrayList<CustomInstrument>() : customInstruments;
        int n = Math.min(255, list.size());
        writeByte(raf, n & 0xFF);
        for (int i = 0; i < n; i++) {
            CustomInstrument ci = list.get(i);
            if (ci == null) ci = new CustomInstrument();
            writeNbsStringLE(raf, ci.name);
            writeNbsStringLE(raf, ci.file);
            writeByte(raf, ci.pitch & 0xFF);
            writeByte(raf, ci.press & 0xFF);
        }
    }

    private static void writeLayersLE(RandomAccessFile raf, int layerCount) throws IOException {
        if (layerCount < 1) layerCount = 1;
        for (int i = 0; i < layerCount; i++) {
            writeNbsStringLE(raf, "Grand Piano");
            writeByte(raf, 100);                  // volume = 100（0x64 = 'd'）
        }
    }

    private static int noteIdToRaw(int noteId) {
        return (noteId + 33) & 0xFF;
    }

    private static int computeMaxTick(List<NoteData> list) {
        int max = 0;
        for (NoteData n : list) if (n.tick > max) max = n.tick;
        return max + 1;
    }

    private static int computeMaxLayer(List<NoteData> list) {
        int max = 0;
        for (NoteData n : list) if (n.layer > max) max = n.layer;
        return max;
    }

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
        writeIntLE(raf, bytes.length);
        raf.write(bytes);
    }

    private static final class NoteData {
        final int tick, layer, instrument, noteId;
        NoteData(int tick, int layer, int instrument, int noteId) {
            this.tick = tick; this.layer = layer; this.instrument = instrument; this.noteId = noteId;
        }
        static NoteData fromPacked(long packed) {
            int tick       = (int)  (packed & 0xFFFFL);
            int layer      = (int) ((packed >>> 16) & 0xFFFFL);
            int instrument = (int) ((packed >>> 32) & 0xFFL);

            int noteId     = Note.extractNoteId(packed);
            return new NoteData(tick, layer, instrument, noteId);
        }
    }
}