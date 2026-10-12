package semmiedev.disc_jockey.gui.screen;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.slf4j.Logger;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.MidiToNbsImporter;
import semmiedev.disc_jockey.NbsToMidiExporter;
import semmiedev.disc_jockey.Note;
import semmiedev.disc_jockey.NoteClamper;
import semmiedev.disc_jockey.Song;
import semmiedev.disc_jockey.SongLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 游戏内 NBS 编辑器（钢琴卷帘）。Fabric / NeoForge 通用，无需维护两份。
 *
 * 音高：沿用 MidiToNbsImporter —— signedNoteId = midi - 54，范围 -33..54
 * 打包：沿用 Note.packNoteId(packed, noteId)，packed = tick | layer<<16 | instr<<32
 * tempo：沿用 NbsToWavExporter —— secPerTick = 100.0/tempo → tickMs = 100000/tempo
 * 保存：优先 NbsToMidiExporter.writeNbs(song)，失败回退 song.save(File)
 * 发声：音符盒音色（NBS 是音符盒格式；PianoLib 属 MIDI 侧，此处不使用）
 * 音色：走项目内 Note.fromNbs(4, id) / Note.NBS_V4，16 种乐器与 NBS id 一一对应
 */
public class NoteBlockEditScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MIN_NOTE = -33;
    private static final int MAX_NOTE = 54;
    private static final int ROW_COUNT = MAX_NOTE - MIN_NOTE + 1;   // 88

    /** MIDI = noteId + 54 */
    private static final int MIDI_OFFSET = 54;

    /** MC 音符盒可发声区 */
    private static final int PLAYABLE_LOW = 0;
    private static final int PLAYABLE_HIGH = 24;

    /** NBS 乐器 0..15，顺序与 Note.NBS_V4 完全一致 */
    private static final String[] INST_NAMES = {
            "Harp", "Bass", "BDrum", "Snare", "Click", "Guitar", "Flute", "Bell",
            "Chime", "Xylo", "IronXyl", "CowBell", "Didger", "Bit", "Banjo", "Pling"
    };

    private static volatile SoundEvent[] instSounds;

    // ---- 布局 ----
    private static final int TOOLBAR_H = 56;
    private static final int KEYBOARD_W = 54;
    private static final int BOTTOM_PAD = 24;
    private int gridX, gridY, gridW, gridH;

    private int zoom = 2;
    private int scrollTick = 0;
    private int scrollRow = 0;
    private int curInstrument = 0;

    private final Screen parent;
    private Song song;
    private final List<EditableNote> notes = new ArrayList<>();
    private final Map<Long, EditableNote> noteIndex = new HashMap<>();

    private EditBox nameBox;
    private Button playButton;
    private Button instButton;

    private final List<File> midiFiles = new ArrayList<>();
    private int midiIndex = 0;

    private Thread playThread;
    private volatile boolean playing;
    private volatile int playheadTick = -1;

    public NoteBlockEditScreen(Screen parent, Song song) {
        super(Component.translatable("disc_jockey.editor.title"));
        this.parent = parent;
        this.song = (song != null) ? song : newSong();
        unpack();
    }

    /** 新建空白曲 */
    public NoteBlockEditScreen(Screen parent) { this(parent, null); }

    private static Song newSong() {
        Song s = new Song();
        s.notes = new long[0];
        s.foldedNotes = null;
        s.length = 0;
        s.height = 0;
        s.tempo = 1000;
        s.loopStartTick = 0;
        s.uniqueNotes.clear();
        s.fileName = "new_song.nbs";
        s.displayName = "new_song";
        s.name = "new_song";
        s.author = "";
        s.originalAuthor = "";
        s.description = "";
        s.searchableFileName = "new_song.nbs";
        s.searchableName = "new_song";
        s.entry = null;
        return s;
    }

    // ==================== 数据 ====================

    private static final class EditableNote {
        int tick, layer, instrument, noteId;
        EditableNote(int t, int l, int i, int n) { tick = t; layer = l; instrument = i; noteId = n; }
    }

    private void unpack() {
        notes.clear();
        noteIndex.clear();
        long[] src = song.notes;
        if (src == null) return;
        for (long p : src) {
            int tick       = (int) (p & 0xFFFFL);
            int layer      = (int) ((p >>> 16) & 0xFFFFL);
            int instrument = (int) ((p >>> 32) & 0xFFL);
            int noteId     = Note.extractNoteId(p);
            EditableNote n = new EditableNote(tick, layer, instrument, noteId);
            notes.add(n);
            noteIndex.put(key(tick, noteId), n);
        }
        notes.sort(Comparator.comparingInt(n -> n.tick));
    }

    private void packToSong() {
        notes.sort(Comparator.comparingInt((EditableNote n) -> n.tick)
                .thenComparingInt(n -> n.layer));

        long[] arr = new long[notes.size()];
        int maxTick = 0, maxLayer = 0;
        for (int i = 0; i < notes.size(); i++) {
            EditableNote n = notes.get(i);
            long packed = ((long) n.tick        & 0xFFFFL)
                        | (((long) n.layer      & 0xFFFFL) << 16)
                        | (((long) n.instrument & 0xFFL)   << 32);
            arr[i] = Note.packNoteId(packed, n.noteId);
            if (n.tick > maxTick) maxTick = n.tick;
            if (n.layer > maxLayer) maxLayer = n.layer;
        }
        song.notes = arr;
        song.foldedNotes = null;
        song.length = (short) Math.max(0, Math.min(Short.MAX_VALUE, maxTick + 1));
        song.height = (short) Math.max(1, Math.min(Short.MAX_VALUE, maxLayer + 1));
        try { NoteClamper.clearFoldedNotes(song); } catch (Throwable ignored) {}
        rebuildUniqueNotes();
    }

    /** 与 MidiToNbsImporter 末尾一致 */
    private void rebuildUniqueNotes() {
        song.uniqueNotes.clear();
        Set<Long> seen = new LinkedHashSet<>();
        for (long p : song.notes) {
            int instrument = (int) ((p >>> 32) & 0xFFL);
            int noteId = Note.extractNoteId(p);
            long k = ((long) instrument << 8) | (noteId & 0xFF);
            if (seen.add(k) && instrument >= 0 && instrument < Note.INSTRUMENTS.length) {
                song.uniqueNotes.add(new Note(Note.INSTRUMENTS[instrument], (byte) noteId));
            }
        }
    }

    private static long key(int tick, int noteId) { return ((long) tick) * 200L + (noteId + 100); }

    private int maxTick() {
        int m = 0;
        for (EditableNote n : notes) if (n.tick > m) m = n.tick;
        return m;
    }

    // ==================== 初始化 ====================

    @Override
    protected void init() {
        gridX = KEYBOARD_W + 4;
        gridY = TOOLBAR_H;
        gridW = width - gridX - 6;
        gridH = height - gridY - BOTTOM_PAD;

        nameBox = new EditBox(font, 6, 6, 140, 18, Component.translatable("disc_jockey.editor.name_hint"));
        nameBox.setMaxLength(64);
        nameBox.setValue(song.name != null ? song.name : "");
        nameBox.setResponder(s -> {
            song.name = s;
            song.displayName = s;
            song.searchableName = s.toLowerCase();
        });
        addRenderableWidget(nameBox);

        int x = 150;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.new"), b -> {
            stopPlayback();
            song = newSong();
            notes.clear(); noteIndex.clear();
            scrollTick = 0; scrollRow = 0;
            nameBox.setValue("new_song");
        }).pos(x, 6).size(38, 18).build());
        x += 40;

        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.save"), b -> save(false)).pos(x, 6).size(44, 18).build());
        x += 46;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.save_as"), b -> save(true)).pos(x, 6).size(28, 18).build());
        x += 30;

        File midiDir = new File(Main.songsFolder.getParentFile(), "midi");
        midiFiles.clear();
        File[] fs = midiDir.listFiles(f -> f.isFile()
                && (f.getName().toLowerCase().endsWith(".mid")
                 || f.getName().toLowerCase().endsWith(".midi")));
        if (fs != null) {
            midiFiles.addAll(List.of(fs));
            midiFiles.sort(Comparator.comparing(File::getName));
        }
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.midi"), b -> {
            if (midiFiles.isEmpty()) {
                showMsg(Component.translatable("disc_jockey.editor.no_midi", midiDir.getAbsolutePath())
                        .withStyle(ChatFormatting.RED));
                return;
            }
            loadMidi(midiFiles.get(midiIndex % midiFiles.size()));
            midiIndex++;
        }).pos(x, 6).size(40, 18).build());
        x += 42;

        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.close"), b -> close()).pos(width - 24, 6).size(20, 18).build());

        // ---- 第二行 ----
        int y2 = 30, bx = 6;
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> scrollTick = Math.max(0, scrollTick - visibleTicks() / 2)).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> scrollTick += visibleTicks() / 2).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> scrollRow = Math.max(0, scrollRow - 4)).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> scrollRow = Math.min(Math.max(0, ROW_COUNT - visibleRows()), scrollRow + 4)).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoom = Math.min(4, zoom + 1)).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("−"), b -> zoom = Math.max(0, zoom - 1)).pos(bx, y2).size(18, 18).build());
        bx += 24;

        playButton = Button.builder(Component.translatable("disc_jockey.editor.play"), b -> {
            if (playing) stopPlayback(); else startPlayback();
        }).pos(bx, y2).size(52, 18).build();
        addRenderableWidget(playButton);
        bx += 54;

        instButton = Button.builder(Component.literal(instName(curInstrument)), b -> {
            curInstrument = (curInstrument + 1) % INST_NAMES.length;
            b.setMessage(Component.literal(instName(curInstrument)));
        }).pos(bx, y2).size(76, 18).build();
        addRenderableWidget(instButton);
        bx += 78;

        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.tempo_down"), b -> song.tempo = (short) Math.max(100, song.tempo - 50)).pos(bx, y2).size(20, 18).build());
        bx += 22;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.tempo_up"), b -> song.tempo = (short) Math.min(4000, song.tempo + 50)).pos(bx, y2).size(20, 18).build());

        initInstSounds();   // 开屏即解析音色表
        initInstKeys();     // 开屏即解析乐器翻译键
    }

    private void close() {
        stopPlayback();
        Minecraft mc = Minecraft.getInstance();
        mc.setScreenAndShow(parent != null ? parent : null);
    }

    // ==================== 尺寸 ====================

    private int cellH() { return 5 + zoom * 2; }
    private int cellW() { return 4 + zoom * 3; }
    private int visibleRows() { return Math.max(1, gridH / cellH()); }
    private int visibleTicks() { return Math.max(1, gridW / cellW()); }
    private int rowToNote(int viewRow) { return MAX_NOTE - (scrollRow + viewRow); }
    private int noteToRow(int noteId) {
        int r = (MAX_NOTE - noteId) - scrollRow;
        return (r >= 0 && r < visibleRows()) ? r : -1;
    }

    // ==================== 坐标换算（★核心修复）====================

    /** mouseHandler 返回窗口原始像素，必须换算到 GUI 缩放坐标 */
    private double toGuiX(double raw) {
        Minecraft mc = Minecraft.getInstance();
        return raw * (double) mc.getWindow().getGuiScaledWidth()
                   / (double) mc.getWindow().getWidth();
    }

    private double toGuiY(double raw) {
        Minecraft mc = Minecraft.getInstance();
        return raw * (double) mc.getWindow().getGuiScaledHeight()
                   / (double) mc.getWindow().getHeight();
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;   // 先给按钮 / EditBox

        Minecraft mc = Minecraft.getInstance();
        double mx = toGuiX(mc.mouseHandler.xpos());
        double my = toGuiY(mc.mouseHandler.ypos());
        int button = click.button();

        System.out.println("[DJ-EDIT] btn=" + button
                + " gui=(" + (int) mx + "," + (int) my + ")"
                + " grid=" + gridX + "," + gridY + " " + gridW + "x" + gridH);

        if (button == 2) return false;   // 中键忽略（1=左 3=右）
        if (mx < gridX || mx >= gridX + gridW || my < gridY || my >= gridY + gridH) return false;

        int col = (int) ((mx - gridX) / cellW());
        int row = (int) ((my - gridY) / cellH());
        int tick = scrollTick + col;
        int noteId = rowToNote(row);
        if (noteId < MIN_NOTE || noteId > MAX_NOTE) return false;

        Long k = key(tick, noteId);
        EditableNote exist = noteIndex.get(k);
        if (exist != null) {
            notes.remove(exist);
            noteIndex.remove(k);
            System.out.println("[DJ-EDIT] DEL tick=" + tick + " " + noteName(noteId));
        } else {
            EditableNote n = new EditableNote(tick, nextFreeLayer(tick), curInstrument, noteId);
            notes.add(n);
            noteIndex.put(k, n);
            System.out.println("[DJ-EDIT] ADD tick=" + tick + " " + noteName(noteId)
                    + " instr=" + instName(curInstrument));
            playNoteSound(n.noteId, n.instrument);
        }
        return true;
    }

    private int nextFreeLayer(int tick) {
        Set<Integer> used = new HashSet<>();
        for (EditableNote n : notes) if (n.tick == tick) used.add(n.layer);
        int l = 0;
        while (used.contains(l)) l++;
        return l;
    }

    /** 反射取键码，避免不同版本方法名差异 */
    private static int keyCodeOf(KeyEvent evt) {
        for (String mn : new String[]{"key", "keycode", "keyCode", "getKeyCode"}) {
            try {
                Method m = KeyEvent.class.getMethod(mn);
                Object v = m.invoke(evt);
                if (v instanceof Number num) return num.intValue();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    @Override
    public boolean keyPressed(KeyEvent evt) {
        int code = keyCodeOf(evt);
        if (code == 256) { close(); return true; }                                              // ESC
        if (code == 262) { scrollTick += visibleTicks() / 2; return true; }                     // →
        if (code == 263) { scrollTick = Math.max(0, scrollTick - visibleTicks() / 2); return true; }
        if (code == 265) { scrollRow = Math.max(0, scrollRow - 2); return true; }               // ↑
        if (code == 264) { scrollRow = Math.min(Math.max(0, ROW_COUNT - visibleRows()), scrollRow + 2); return true; }
        if (code == 32)  { if (playing) stopPlayback(); else startPlayback(); return true; }    // Space
        return super.keyPressed(evt);
    }

    // ==================== 音色表（★本轮修复）====================

    /** 首次调用时解析 16 种音色并打印诊断；走项目内 Note.fromNbs / Note.NBS_V4，不猜字段名 */
    private static synchronized void initInstSounds() {
        if (instSounds != null) return;
        SoundEvent[] arr = new SoundEvent[INST_NAMES.length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = resolveInstSound(i);
        }
        instSounds = arr;

        StringBuilder sb = new StringBuilder("[DJ-EDIT] instrument table (NBS_V4):");
        for (int i = 0; i < arr.length; i++) {
            sb.append("\n  [").append(i).append("] ").append(INST_NAMES[i])
              .append(" -> ").append(arr[i] == null
                    ? "NULL (fallback Harp)" : arr[i].location().toString());
        }
        System.out.println(sb);
    }

    private static SoundEvent resolveInstSound(int i) {
        NoteBlockInstrument nb = null;
        try {
            nb = Note.fromNbs(4, i);            // NBS v4 表 = 16 乐器，与 INST_NAMES 顺序一致
        } catch (Throwable ignored) {}
        if (nb == null) {
            try {
                NoteBlockInstrument[] t = Note.NBS_V4;   // 直接取表兜底
                if (i >= 0 && i < t.length) nb = t[i];
            } catch (Throwable ignored) {}
        }
        if (nb == null) return null;

        // 26.3 的 getSoundEvent() 返回 Holder<SoundEvent>，需 .value() 解包
        for (String mn : new String[]{"getSoundEvent", "soundEvent"}) {
            try {
                SoundEvent se = unwrap(nb.getClass().getMethod(mn).invoke(nb));
                if (se != null) return se;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Holder<SoundEvent> / Supplier / SoundEvent 三种形态都兼容 */
    private static SoundEvent unwrap(Object o) {
        if (o instanceof SoundEvent s) return s;
        if (o == null) return null;
        for (String mn : new String[]{"value", "get"}) {
            try {
                Object v = o.getClass().getMethod(mn).invoke(o);
                if (v instanceof SoundEvent s) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static SoundEvent instSound(int i) {
        initInstSounds();
        SoundEvent se = (i >= 0 && i < instSounds.length) ? instSounds[i] : null;
        return se != null ? se : instSounds[0];   // 降级 Harp
    }

    // ==================== 乐器名翻译 ====================

    /** 自定义翻译键：disc_jockey.editor.instrument.<serializedName> */
    private static volatile String[] instKeys;

    /** 首次调用时拼出 16 个翻译键并打印诊断（键名由 NoteBlockInstrument 自己给出，不硬编码） */
    private static synchronized void initInstKeys() {
        if (instKeys != null) return;
        String[] arr = new String[INST_NAMES.length];
        for (int i = 0; i < arr.length; i++) arr[i] = resolveInstKey(i);
        instKeys = arr;

        StringBuilder sb = new StringBuilder("[DJ-EDIT] instrument keys:");
        for (int i = 0; i < arr.length; i++) {
            sb.append("\n  [").append(i).append("] ").append(INST_NAMES[i])
              .append(" -> ").append(arr[i] == null
                    ? "(none, use literal)" : arr[i]);
        }
        System.out.println(sb);
    }

    private static String resolveInstKey(int i) {
        NoteBlockInstrument nb = null;
        try { nb = Note.fromNbs(4, i); } catch (Throwable ignored) {}
        if (nb == null) {
            try {
                NoteBlockInstrument[] t = Note.NBS_V4;
                if (i >= 0 && i < t.length) nb = t[i];
            } catch (Throwable ignored) {}
        }
        if (nb == null) return null;
        for (String mn : new String[]{"getSerializedName", "serializedName"}) {
            try {
                Object v = nb.getClass().getMethod(mn).invoke(nb);
                if (v instanceof String str && !str.isEmpty())
                    return "disc_jockey.editor.instrument." + str;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 取本地化后的乐器名。
     * lang 里有 disc_jockey.editor.instrument.<key> → 用它；
     * 没有 → 回退 INST_NAMES 英文原名（不会漏出键名原文）。
     */
    private static String instName(int i) {
        initInstKeys();
        String key = (i >= 0 && i < instKeys.length) ? instKeys[i] : null;
        if (key == null) return INST_NAMES[i];
        String t = Component.translatable(key).getString();
        return (t == null || t.isEmpty() || t.equals(key)) ? INST_NAMES[i] : t;
    }

    // ==================== 播放（音符盒音色）====================

    private void startPlayback() {
        stopPlayback();
        if (notes.isEmpty()) return;
        final List<EditableNote> snapshot = new ArrayList<>(notes);
        final int end = maxTick() + 2;
        final long tickMs = Math.max(1, 100000L / Math.max(1, song.tempo));

        playing = true;
        playButton.setMessage(Component.translatable("disc_jockey.editor.stop"));

        playThread = new Thread(() -> {
            for (int t = 0; t <= end && playing; t++) {
                playheadTick = t;
                List<EditableNote> at = new ArrayList<>();
                for (EditableNote n : snapshot) if (n.tick == t) at.add(n);
                if (!at.isEmpty()) {
                    Minecraft.getInstance().execute(() -> {
                        for (EditableNote n : at) playNoteSound(n.noteId, n.instrument);
                    });
                }
                if (t < scrollTick || t >= scrollTick + visibleTicks())
                    scrollTick = Math.max(0, t - visibleTicks() / 4);
                try { Thread.sleep(tickMs); } catch (InterruptedException e) { break; }
            }
            playing = false;
            playheadTick = -1;
            Minecraft.getInstance().execute(() -> playButton.setMessage(Component.translatable("disc_jockey.editor.play")));
        }, "DJ-NBSEditor-Play");
        playThread.start();
    }

    private void stopPlayback() {
        playing = false;
        playheadTick = -1;
        if (playThread != null) { playThread.interrupt(); playThread = null; }
        if (playButton != null) playButton.setMessage(Component.translatable("disc_jockey.editor.play"));
    }

    /**
     * 音符盒音色；八度折叠到 MC 可播区 0..24 后换算 pitch。
     * forUI 参数序是 (sound, pitch, volume) —— 传反会导致所有音都变基准音 F#。
     */
    private void playNoteSound(int noteId, int instrument) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        final SoundEvent se = instSound(instrument);
        if (se == null) return;

        int k = noteId;
        while (k > PLAYABLE_HIGH) k -= 12;
        while (k < PLAYABLE_LOW)  k += 12;
        float pitch = (float) Math.pow(2.0D, (k - 12) / 12.0D);

        mc.execute(() -> mc.getSoundManager().play(
                SimpleSoundInstance.forUI(se, pitch, 1.0f)));
    }

    // ==================== 保存 / 导入 ====================

    private void save(boolean saveAs) {
        try {
            packToSong();
            String base = (song.fileName != null && !song.fileName.isEmpty())
                    ? song.fileName.replaceAll("\\.(?i)nbs$", "") : "new_song";
            if (saveAs) base = base + "_edit";
            song.fileName = base + ".nbs";
            song.searchableFileName = song.fileName.toLowerCase();

            File out = new File(Main.songsFolder, base + ".nbs");

            // 优先 writeNbs；若 ByteBuffer 溢出则回退 song.save(File)
            boolean ok;
            try {
                byte[] data = NbsToMidiExporter.writeNbs(song);
                Files.write(out.toPath(), data);
                ok = true;
            } catch (Throwable t) {
                LOGGER.warn("[Editor] writeNbs 失败，回退 song.save(): {}", t.getMessage());
                ok = saveViaSong(out);
            }

            if (ok) {
                showMsg(Component.translatable("disc_jockey.editor.saved", out.getName())
                        .withStyle(ChatFormatting.GREEN));
                SongLoader.loadSongs();
            } else {
                showMsg(Component.translatable("disc_jockey.editor.save_failed_all")
                        .withStyle(ChatFormatting.RED));
            }
        } catch (Exception e) {
            LOGGER.error("[Editor] save failed", e);
            showMsg(Component.translatable("disc_jockey.editor.save_failed", String.valueOf(e.getMessage()))
                        .withStyle(ChatFormatting.RED));
        }
    }

    /** 反射调用 song.save(File) / song.save(Path) / song.save(String) */
    private boolean saveViaSong(File out) {
        try {
            Method m = song.getClass().getMethod("save", File.class);
            m.invoke(song, out);
            return true;
        } catch (Throwable ignored) {}
        try {
            Method m = song.getClass().getMethod("save", java.nio.file.Path.class);
            m.invoke(song, out.toPath());
            return true;
        } catch (Throwable ignored) {}
        try {
            Method m = song.getClass().getMethod("save", String.class);
            m.invoke(song, out.getAbsolutePath());
            return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void loadMidi(File f) {
        try {
            Song imported = MidiToNbsImporter.importMidi(f);
            if (imported == null) {
                showMsg(Component.translatable("disc_jockey.editor.midi_failed", f.getName())
                        .withStyle(ChatFormatting.RED));
                return;
            }
            stopPlayback();
            song = imported;
            if (song.fileName == null || song.fileName.isEmpty()) song.fileName = "new_song.nbs";
            unpack();
            scrollTick = 0; scrollRow = 0;
            nameBox.setValue(song.name != null ? song.name : song.fileName);
            showMsg(Component.translatable("disc_jockey.editor.midi_loaded", f.getName(), notes.size())
                        .withStyle(ChatFormatting.GREEN));
        } catch (Exception e) {
            LOGGER.error("[Editor] midi load failed", e);
            showMsg(Component.translatable("disc_jockey.editor.load_failed", String.valueOf(e.getMessage()))
                        .withStyle(ChatFormatting.RED));
        }
    }

    /** 便捷重载：字符串走 literal（仅用于日志/调试） */
    public static void showMsg(String text) {
        showMsg(Component.literal(text));
    }

    /** 26.3 已移除 Gui.getChat() / player.displayClientMessage()，全反射走 chatListener() */
    public static void showMsg(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || c == null) return;
        try {
            Object gui = mc.gui;
            if (gui != null) {
                for (Method m : gui.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && m.getName().equals("chatListener")) {
                        Object cl = m.invoke(gui);
                        if (cl == null) break;
                        for (Method hm : cl.getClass().getMethods()) {
                            if (hm.getName().equals("handleSystemMessage")
                                    && hm.getParameterCount() == 2
                                    && hm.getParameterTypes()[0].isAssignableFrom(Component.class)) {
                                hm.invoke(cl, c, false);
                                return;
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        Main.LOGGER.info("[Editor] {}", c.getString());
    }

    // ==================== 渲染 ====================

    @Override
    public void extractRenderState(GuiGraphicsExtractor c, int mx, int my, float delta) {
        super.extractRenderState(c, mx, my, delta);

        int ch = cellH(), cw = cellW();
        int rows = visibleRows(), ticks = visibleTicks();

        c.fill(gridX, gridY, gridX + gridW, gridY + gridH, 0xFF101014);

        // 节拍线（NBS 4 tick = 1 拍）
        for (int t = 0; t < ticks; t++) {
            int abs = scrollTick + t;
            int x = gridX + t * cw;
            c.fill(x, gridY, x + 1, gridY + gridH, (abs % 4 == 0) ? 0xFF3A3A44 : 0xFF22222A);
        }

        for (int r = 0; r < rows; r++) {
            int noteId = rowToNote(r);
            if (noteId < MIN_NOTE || noteId > MAX_NOTE) continue;
            int y = gridY + r * ch;
            if (isBlackKey(noteId)) c.fill(gridX, y, gridX + gridW, y + ch, 0x33000000);
            if (noteId >= PLAYABLE_LOW && noteId <= PLAYABLE_HIGH)
                c.fill(gridX, y, gridX + gridW, y + ch, 0x1400FF88);
        }

        for (EditableNote n : notes) {
            if (n.tick < scrollTick || n.tick >= scrollTick + ticks) continue;
            int r = noteToRow(n.noteId);
            if (r < 0) continue;
            int x = gridX + (n.tick - scrollTick) * cw;
            int y = gridY + r * ch;
            int col = isBlackKey(n.noteId) ? 0xFF4FC3F7 : 0xFF8BC34A;
            c.fill(x, y, x + Math.max(2, cw - 1), y + Math.max(2, ch - 1), col);
        }

        if (playheadTick >= scrollTick && playheadTick < scrollTick + ticks) {
            int x = gridX + (playheadTick - scrollTick) * cw;
            c.fill(x, gridY, x + 2, gridY + gridH, 0xFFFFEB3B);
        }

        drawKeyboard(c, ch, rows);

        Component st = Component.translatable("disc_jockey.editor.status",
                notes.size(),
                maxTick() + 1,
                song.tempo,
                String.format("%.1f", song.tempo / 100.0),
                scrollTick,
                zoom,
                instName(curInstrument),
                playing ? Component.translatable("disc_jockey.editor.playing").getString() : "");
        c.text(font, st, gridX, height - 16, 0xFFAAAAAA, false);
    }

    private void drawKeyboard(GuiGraphicsExtractor c, int ch, int rows) {
        c.fill(2, gridY, KEYBOARD_W, gridY + gridH, 0xFF1A1A1E);
        for (int r = 0; r < rows; r++) {
            int noteId = rowToNote(r);
            if (noteId < MIN_NOTE || noteId > MAX_NOTE) continue;
            int y = gridY + r * ch;
            int h = Math.max(2, ch - 1);
            boolean black = isBlackKey(noteId);
            int col = black ? 0xFF2A2A2A : 0xFFE8E8E8;
            if (noteId < PLAYABLE_LOW || noteId > PLAYABLE_HIGH) col = black ? 0xFF202020 : 0xFF9A9A9A;
            c.fill(2, y, KEYBOARD_W, y + h, col);
            if (noteId == PLAYABLE_LOW || noteId == PLAYABLE_HIGH)
                c.fill(2, y, KEYBOARD_W, y + h, 0xFFFFEB3B);
            if (ch >= 9 && !black)
                c.text(font, Component.literal(noteName(noteId)), 4, y + 1, 0xFF333333, false);
        }
    }

    // ==================== 音高工具 ====================

    private static final Set<Integer> WHITE_PC = Set.of(0, 2, 4, 5, 7, 9, 11);

    private static boolean isBlackKey(int noteId) {
        int midi = noteId + MIDI_OFFSET;
        return !WHITE_PC.contains(((midi % 12) + 12) % 12);
    }

    private static String noteName(int noteId) {
        String[] n = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        int midi = noteId + MIDI_OFFSET;
        int pc = ((midi % 12) + 12) % 12;
        return n[pc] + (midi / 12 - 1);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { stopPlayback(); super.onClose(); }
}
