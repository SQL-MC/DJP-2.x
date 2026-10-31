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
import semmiedev.disc_jockey.util.BlitCompat;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.lang.reflect.Field;
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
 * 游戏内 NBS 编辑器（钢琴卷帘）。Fabric / NeoForge 通用。
 *
 * 鼠标：
 *   左键拖拽      = 框选（★ 拖到网格边缘自动滚动，越靠边越快）
 *   左键单击      = 放 / 删音符
 *   右键点音符    = 加入 / 移出选区
 *   滚轮          = 上下移动音高
 *   Shift+滚轮    = 左右移动 tick
 *   Ctrl+滚轮     = 缩放
 * 键盘：Ctrl+Z/Y/A/C/X/V/D、Delete/Backspace、ESC、方向键、Space
 *
 * ★ 键码体系（MC 26.3 实测）：KeyEvent 用的是【SDL 扫描码】
 *   Ctrl=224  A=4  C=6  D=7  V=25  X=27  Z=29  ESC=41
 *   modifiers 是 SDL Keymod：SHIFT=3  CTRL=192  ALT=768
 */
public class NoteBlockEditScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MIN_NOTE = -33;
    private static final int MAX_NOTE = 54;
    private static final int ROW_COUNT = MAX_NOTE - MIN_NOTE + 1;

    private static final int MIDI_OFFSET = 54;
    private static final int PLAYABLE_LOW = 0;
    private static final int PLAYABLE_HIGH = 24;

    /** NBS 乐器英文短名（0..15） */
    private static final String[] INST_NAMES = {
        "Harp", "Bass", "BDrum", "Snare", "Click", "Guitar", "Flute", "Bell",
        "Chime", "Xylo", "IronXyl", "CowBell", "Didger", "Bit", "Banjo", "Pling"
    };
    private static final String[] INST_KEY = {
        "disc_jockey.editor.instrument.harp",
        "disc_jockey.editor.instrument.bass",
        "disc_jockey.editor.instrument.basedrum",
        "disc_jockey.editor.instrument.snare",
        "disc_jockey.editor.instrument.hat",
        "disc_jockey.editor.instrument.guitar",
        "disc_jockey.editor.instrument.flute",
        "disc_jockey.editor.instrument.bell",
        "disc_jockey.editor.instrument.chime",
        "disc_jockey.editor.instrument.xylophone",
        "disc_jockey.editor.instrument.iron_xylophone",
        "disc_jockey.editor.instrument.cow_bell",
        "disc_jockey.editor.instrument.didgeridoo",
        "disc_jockey.editor.instrument.bit",
        "disc_jockey.editor.instrument.banjo",
        "disc_jockey.editor.instrument.pling"
    };
    /** 取翻译键（越界兜底为竖琴） */
    private static String instKey(int i) {
        return (i >= 0 && i < INST_KEY.length) ? INST_KEY[i] : INST_KEY[0];
    }
    /** 取可翻译的乐器名组件 */
    private static net.minecraft.network.chat.Component instName(int i) {
        return net.minecraft.network.chat.Component.translatable(instKey(i));
    }

    /**
     * 每种音色对应的图标贴图（项目自带，assets/disc_jockey/textures/item/）。
     * 都是从 MC 里解出来的 16x16 物品图，索引与 INST_NAMES / Note.NBS_V4 一致。
     * 没有专属图的（Bell/Chime/Xylo/IronXyl/CowBell/Bit/Pling/Harp）统一用 noteblock。
     */
    private static final String[] INST_TEX = {
            "noteblock",       // 0  Harp
            "oak_planks",      // 1  Bass
            "stone",           // 2  BDrum
            "sand",            // 3  Snare
            "glass",           // 4  Click / Hat
            "white_wool",      // 5  Guitar
            "clay",            // 6  Flute
            "gold_block",       // 7  Bell
            "packed_ice",       // 8  Chime
            "bone_block_top",       // 9  Xylo
            "iron_block",       // 10 IronXyl
            "soul_sand",       // 11 CowBell
            "pumpkin_side",   // 12 Didger
            "emerald_block",       // 13 Bit
            "hay_block_side",  // 14 Banjo
            "glowstone"        // 15 Pling
    };

    /**
     * 每种音色的色块颜色（取发声方块本色）。
     * 图标只在格子够大时才画，小缩放全靠这 16 色区分音色 ——
     * 所以即使图标没出来，也不会再退回「只有蓝/绿两色」。
     */
    private static final int[] INST_COLOR = {
            0xFF9C7A3C, // 0  Harp      音符盒（木）
            0xFFA0783C, // 1  Bass      橡木木板
            0xFF8A8A8A, // 2  BDrum     石头
            0xFFDBCB8B, // 3  Snare     沙子
            0xFFBFE5E5, // 4  Click     玻璃
            0xFFEDEDED, // 5  Guitar    白色羊毛
            0xFF9E8C7A, // 6  Flute     黏土
            0xFFD4AF37, // 7  Bell      金块
            0xFF7EC8E3, // 8  Chime     浮冰
            0xFFE3DCC0, // 9  Xylo      骨块
            0xFFC8C8C8, // 10 IronXyl   铁块
            0xFFE08A2E, // 11 CowBell   南瓜
            0xFF2ECC71, // 12 Didger    绿宝石块
            0xFF8B4A4A, // 13 Bit       下界岩
            0xFFC8A34A, // 14 Banjo     干草捆
            0xFFEAD36B  // 15 Pling     萤石
    };

    /** 格子达到这个尺寸才叠加图标（16x16 贴图的最小需求） */
    private static final int ICON_MIN_CELL = 8;
    /**
     * 音色图标总开关。
     *
     * 默认 false：只按 16 种音色着色，不画贴图。
     * 原因：assets/disc_jockey/textures/item/*.png 在运行时解析不到时，
     * MC 会渲染「缺失贴图」的黑紫棋盘格，反而比纯色更糟。
     * 确认 png 已打进 jar 之后，把这里改成 true 即可启用图标。
     */
    /**
     * 音色图标总开关。
     * true = 贴图存在就画图标；贴图不存在则自动退回纯色（不会出现黑紫缺失贴图）。
     */
    private static final boolean ICONS_ENABLED = true;

    /** 图标贴图缓存（Identifier 构造方式 26.3 / 26.4 不同，反射兜底） */
    /**
     * \u515c\u5e95\u7528\u7684 MC \u539f\u7248\u65b9\u5757\u8d34\u56fe\uff08assets/minecraft/textures/block/\uff09\u3002
     * \u7d22\u5f15\u4e0e INST_TEX \u4e00\u81f4\uff1b\u9879\u76ee\u91cc\u53eb noteblock\uff0cMC \u91cc\u53eb note_block\u3002
     */
    private static final String[] INST_TEX_VANILLA = {
            "note_block",      // 0  Harp
            "oak_planks",      // 1  Bass
            "stone",           // 2  BDrum
            "sand",            // 3  Snare
            "glass",           // 4  Click / Hat
            "white_wool",      // 5  Guitar
            "clay",            // 6  Flute
            "note_block",      // 7  Bell
            "note_block",      // 8  Chime
            "note_block",      // 9  Xylo
            "note_block",      // 10 IronXyl
            "note_block",      // 11 CowBell
            "emerald_block",   // 12 Didger
            "note_block",      // 13 Bit
            "hay_block_side",  // 14 Banjo
            "note_block"       // 15 Pling
    };

    /** \u515c\u5e95\u4e5f\u5931\u8d25\u8fc7\uff0c\u4e0d\u518d\u91cd\u590d\u63a2\u6d4b */
    private static final boolean[] instTexDead = new boolean[INST_TEX.length];
    private static volatile boolean vanillaFallbackLogged = false;

    private static final Identifier[] instTexCache = new Identifier[INST_TEX.length];
    private static volatile boolean instTexTried = false;

    /** 每个音色的贴图是否已向 TextureManager 注册过 */
    private static final boolean[] instTexRegistered = new boolean[INST_TEX.length];
    private static volatile boolean texRegLogged = false;
    private static final boolean TEXTURE_REGISTER = true;

    private static volatile SoundEvent[] instSounds;
    private static String[] instKeys;

    private static final int MAX_UNDO = 50;

    private static final int TOOLBAR_H = 56;
    private static final int KEYBOARD_W = 54;
    private static final int BOTTOM_PAD = 24;
    private int gridX, gridY, gridW, gridH;

    private int zoom = 2;
    private int scrollTick = 0;
    private int scrollRow = 0;
    private int curInstrument = 0;
    private Button pauseButton;
    private boolean paused = false;
        private volatile int resumeFromTick = 0;

    // ---- 边缘自动滚动 ----
    private static final int EDGE_PX = 30;      // 触发区宽度（像素）
    private static final int EDGE_MAX_SPEED = 5;
    private int mouseX = -1, mouseY = -1;

    private final Screen parent;
    private Song song;
    private final List<EditableNote> notes = new ArrayList<>();
    private final Map<Long, EditableNote> noteIndex = new HashMap<>();

    private final Set<EditableNote> selection = new HashSet<>();
    private final List<int[]> clipboard = new ArrayList<>();
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>();
    private int hoverTick = -1;
    private int hoverNote = Integer.MIN_VALUE;

    private boolean boxSelecting = false;
    private int anchorTick = -1;
    private int anchorNote = Integer.MIN_VALUE;
    private int dragCurTick = -1;
    private int dragCurNote = Integer.MIN_VALUE;

    private boolean ctrlHeld = false;
    private boolean shiftHeld = false;

    private static boolean diagDone = false;

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

    private static final class EditableNote {
        int tick, layer, instrument, noteId;
        EditableNote(int t, int l, int i, int n) { tick = t; layer = l; instrument = i; noteId = n; }
    }

    private void unpack() {
        notes.clear();
        noteIndex.clear();
        selection.clear();
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

    private int[] snapshot() {
        int[] a = new int[notes.size() * 4];
        for (int i = 0; i < notes.size(); i++) {
            EditableNote n = notes.get(i);
            a[i * 4    ] = n.tick;
            a[i * 4 + 1] = n.layer;
            a[i * 4 + 2] = n.instrument;
            a[i * 4 + 3] = n.noteId;
        }
        return a;
    }

    private void restore(int[] a) {
        notes.clear();
        noteIndex.clear();
        selection.clear();
        if (a == null) return;
        for (int i = 0; i + 3 < a.length; i += 4) {
            EditableNote n = new EditableNote(a[i], a[i + 1], a[i + 2], a[i + 3]);
            notes.add(n);
            noteIndex.put(key(n.tick, n.noteId), n);
        }
        notes.sort(Comparator.comparingInt(x -> x.tick));
    }

    private void pushUndo() {
        undoStack.add(snapshot());
        if (undoStack.size() > MAX_UNDO) undoStack.remove(0);
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) { System.out.println("[DJ-EDIT] UNDO: 栈空"); return; }
        redoStack.add(snapshot());
        restore(undoStack.remove(undoStack.size() - 1));
        System.out.println("[DJ-EDIT] UNDO -> " + notes.size() + " 音符");
    }

    private void redo() {
        if (redoStack.isEmpty()) { System.out.println("[DJ-EDIT] REDO: 栈空"); return; }
        undoStack.add(snapshot());
        restore(redoStack.remove(redoStack.size() - 1));
        System.out.println("[DJ-EDIT] REDO -> " + notes.size() + " 音符");
    }

    private List<EditableNote> selectedList() {
        List<EditableNote> r = new ArrayList<>();
        for (EditableNote n : notes) if (selection.contains(n)) r.add(n);
        r.sort(Comparator.comparingInt(x -> x.tick));
        return r;
    }

    private void selectAll() {
        selection.clear();
        selection.addAll(notes);
        System.out.println("[DJ-EDIT] SELECT ALL -> " + selection.size());
    }

    private void selectBox(int t0, int t1, int n0, int n1, boolean additive) {
        if (!additive) selection.clear();
        int cnt = 0;
        for (EditableNote n : notes) {
            if (n.tick >= t0 && n.tick <= t1 && n.noteId >= n0 && n.noteId <= n1) {
                selection.add(n);
                cnt++;
            }
        }
        System.out.println("[DJ-EDIT] BOX SELECT tick " + t0 + ".." + t1
                + " note " + n0 + ".." + n1 + " -> " + cnt + " 个（共 " + selection.size() + "）");
    }

    private void finishBoxSelect(boolean additive) {
        if (!boxSelecting) return;
        boxSelecting = false;
        if (anchorTick < 0 || dragCurTick < 0) return;

        int t0 = Math.min(anchorTick, dragCurTick);
        int t1 = Math.max(anchorTick, dragCurTick);
        int n0 = Math.min(anchorNote, dragCurNote);
        int n1 = Math.max(anchorNote, dragCurNote);

        if (t0 == t1 && n0 == n1) {
            toggleNoteAt(t0, n0);
        } else {
            selectBox(t0, t1, n0, n1, additive);
        }
    }

    private void toggleNoteAt(int tick, int noteId) {
        Long k = key(tick, noteId);
        EditableNote exist = noteIndex.get(k);
        if (exist != null) {
            pushUndo();
            notes.remove(exist);
            noteIndex.remove(k);
            selection.remove(exist);
            System.out.println("[DJ-EDIT] DEL tick=" + tick + " " + noteName(noteId));
        } else {
            pushUndo();
            EditableNote n = new EditableNote(tick, nextFreeLayer(tick), curInstrument, noteId);
            notes.add(n);
            noteIndex.put(k, n);
            System.out.println("[DJ-EDIT] ADD tick=" + tick + " " + noteName(noteId)
                    + " instr=" + instName(curInstrument));
            playNoteSound(n.noteId, n.instrument);
        }
    }

    private void copySelection() {
        List<EditableNote> sel = selectedList();
        clipboard.clear();
        if (sel.isEmpty()) { System.out.println("[DJ-EDIT] COPY: 无选中"); return; }
        int minTick = Integer.MAX_VALUE;
        for (EditableNote n : sel) minTick = Math.min(minTick, n.tick);
        for (EditableNote n : sel) {
            clipboard.add(new int[]{n.tick - minTick, n.noteId, n.instrument});
        }
        int span = 0;
        for (EditableNote n : sel) span = Math.max(span, n.tick - minTick);
        System.out.println("[DJ-EDIT] COPY -> " + clipboard.size()
                + " 个（跨度 " + span + " tick，基准音 "
                + (sel.isEmpty() ? "?" : noteName(sel.get(0).noteId)) + "）");
    }

    private void cutSelection() {
        copySelection();
        deleteSelection();
    }

    private void pasteAt(int tick) {
        if (clipboard.isEmpty()) { System.out.println("[DJ-EDIT] PASTE: 剪贴板空"); return; }
        if (tick < 0) tick = 0;
        pushUndo();
        int added = 0;
        for (int[] c : clipboard) {
            int nt = tick + c[0];
            if (nt > Short.MAX_VALUE) continue;
            int noteId = c[1];
            if (noteId < MIN_NOTE || noteId > MAX_NOTE) continue;
            Long k = key(nt, noteId);
            if (noteIndex.containsKey(k)) continue;
            EditableNote n = new EditableNote(nt, nextFreeLayer(nt), c[2], noteId);
            notes.add(n);
            noteIndex.put(k, n);
            added++;
        }
        System.out.println("[DJ-EDIT] PASTE @tick=" + tick + " -> " + added + "/" + clipboard.size() + " 个");
    }

    private void duplicateSelection() {
        List<EditableNote> sel = selectedList();
        if (sel.isEmpty()) { System.out.println("[DJ-EDIT] DUP: 无选中"); return; }
        copySelection();
        int maxT = 0;
        for (EditableNote n : sel) maxT = Math.max(maxT, n.tick);
        pasteAt(maxT + 1);
    }

    private void deleteSelection() {
        List<EditableNote> sel = selectedList();
        if (sel.isEmpty()) return;
        pushUndo();
        for (EditableNote n : sel) {
            notes.remove(n);
            noteIndex.remove(key(n.tick, n.noteId));
        }
        selection.clear();
        System.out.println("[DJ-EDIT] DELETE SELECTION -> " + sel.size() + " 个");
    }

    private void moveSelection(int dt) {
        List<EditableNote> sel = selectedList();
        if (sel.isEmpty() || dt == 0) return;
        pushUndo();
        for (EditableNote n : sel) { notes.remove(n); noteIndex.remove(key(n.tick, n.noteId)); }
        for (EditableNote n : sel) {
            int nt = Math.max(0, Math.min(Short.MAX_VALUE, n.tick + dt));
            n.tick = nt;
            notes.add(n);
            noteIndex.put(key(n.tick, n.noteId), n);
        }
        notes.sort(Comparator.comparingInt(x -> x.tick));
        System.out.println("[DJ-EDIT] MOVE " + (dt > 0 ? "+" : "") + dt + " tick -> " + sel.size() + " 个");
    }

    private void transposeSelection(int dn) {
        List<EditableNote> sel = selectedList();
        if (sel.isEmpty() || dn == 0) return;
        pushUndo();
        for (EditableNote n : sel) { notes.remove(n); noteIndex.remove(key(n.tick, n.noteId)); }
        for (EditableNote n : sel) {
            int nn = n.noteId + dn;
            if (nn < MIN_NOTE) nn = MIN_NOTE;
            if (nn > MAX_NOTE) nn = MAX_NOTE;
            n.noteId = nn;
            notes.add(n);
            noteIndex.put(key(n.tick, n.noteId), n);
        }
        notes.sort(Comparator.comparingInt(x -> x.tick));
        System.out.println("[DJ-EDIT] TRANSPOSE " + (dn > 0 ? "+" : "") + dn + " -> " + sel.size() + " 个");
    }

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
            pushUndo();
            song = newSong();
            notes.clear(); noteIndex.clear(); selection.clear();
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

        int y2 = 30, bx = 6;
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> scrollTick = Math.max(0, scrollTick - visibleTicks() / 2)).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> scrollTick += visibleTicks() / 2).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> scrollRow = Math.max(0, scrollRow - 4)).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> scrollRow = Math.min(Math.max(0, ROW_COUNT - visibleRows()), scrollRow + 4)).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoom = Math.min(6, zoom + 1)).pos(bx, y2).size(18, 18).build());
        bx += 20;
        addRenderableWidget(Button.builder(Component.literal("−"), b -> zoom = Math.max(0, zoom - 1)).pos(bx, y2).size(18, 18).build());
        bx += 24;

        playButton = Button.builder(Component.translatable("disc_jockey.editor.play"), b -> {
            if (playing) stopPlayback(); else startPlayback();
        }).pos(bx, y2).size(52, 18).build();
        addRenderableWidget(playButton);
        bx += 54;

        instButton = Button.builder(instName(curInstrument), b -> {
            curInstrument = (curInstrument + 1) % INST_NAMES.length;
            b.setMessage(instName(curInstrument));
        }).pos(bx, y2).size(76, 18).build();
        addRenderableWidget(instButton);
        bx += 78;

        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.tempo_down"), b -> song.tempo = (short) Math.max(100, song.tempo - 50)).pos(bx, y2).size(20, 18).build());
        bx += 22;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.tempo_up"), b -> song.tempo = (short) Math.min(4000, song.tempo + 50)).pos(bx, y2).size(20, 18).build());
        bx += 24;

        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.del"), b -> deleteSelection()).pos(bx, y2).size(22, 18).build());
        bx += 24;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.copy"), b -> copySelection()).pos(bx, y2).size(22, 18).build());
        bx += 24;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.cut"), b -> cutSelection()).pos(bx, y2).size(22, 18).build());
        bx += 24;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.paste"), b -> pasteAt(hoverTick >= 0 ? hoverTick : scrollTick)).pos(bx, y2).size(22, 18).build());
        bx += 24;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.dup"), b -> duplicateSelection()).pos(bx, y2).size(22, 18).build());
        bx += 24;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.sel_all"), b -> selectAll()).pos(bx, y2).size(22, 18).build());
        bx += 24;
        addRenderableWidget(Button.builder(Component.translatable("disc_jockey.editor.undo"), b -> undo()).pos(bx, y2).size(22, 18).build());
        bx += 24;
        pauseButton = Button.builder(Component.literal("暂停"), b -> togglePause()).pos(bx, y2).size(22, 18).build();
        addRenderableWidget(pauseButton);
        initInstSounds();
        initInstKeys();
    }

    private void close() {
        stopPlayback();
        Minecraft mc = Minecraft.getInstance();
        Screen target = (parent != null) ? parent : null;

        try {
            Method m = mc.getClass().getMethod("setScreenAndShow", Screen.class);
            m.invoke(mc, target);
            return;
        } catch (Throwable ignored) {}

        try {
            Method m = Main.class.getMethod("setScreenCompatStatic", Minecraft.class, Screen.class);
            m.invoke(null, mc, target);
            return;
        } catch (Throwable ignored) {}

        try {
            Method m = mc.getClass().getMethod("setScreen", Screen.class);
            m.invoke(mc, target);
        } catch (Throwable ignored) {}
    }

    private int cellH() { return 4 + zoom * 3; }   // ★ 与 cellW 同比例：zoom=4 → 16x16，刚好放得下图标
    private int cellW() { return 4 + zoom * 3; }
    private int visibleRows() { return Math.max(1, gridH / cellH()); }
    private int visibleTicks() { return Math.max(1, gridW / cellW()); }
    private int rowToNote(int viewRow) { return MAX_NOTE - (scrollRow + viewRow); }
    private int noteToRow(int noteId) {
        int r = (MAX_NOTE - noteId) - scrollRow;
        return (r >= 0 && r < visibleRows()) ? r : -1;
    }
    private int maxScrollRow() { return Math.max(0, ROW_COUNT - visibleRows()); }

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

    // ==================== 滚动：边缘自动 + 滚轮 ====================

    /** 距离 → 速度（越靠边越快） */
    private static int edgeSpeed(int dist) {
        if (dist <= 0) return 0;
        int v = 1 + dist / 8;
        return Math.min(EDGE_MAX_SPEED, v);
    }

    /** 框选拖拽到边缘时自动滚动视图 */
    private void autoScrollEdge(double mx, double my) {
        if (!boxSelecting) return;

        int dx = 0, dy = 0;
        if (mx < gridX + EDGE_PX)                 dx = -edgeSpeed((int) (gridX + EDGE_PX - mx));
        else if (mx > gridX + gridW - EDGE_PX)    dx =  edgeSpeed((int) (mx - (gridX + gridW - EDGE_PX)));

        if (my < gridY + EDGE_PX)                 dy = -edgeSpeed((int) (gridY + EDGE_PX - my));
        else if (my > gridY + gridH - EDGE_PX)    dy =  edgeSpeed((int) (my - (gridY + gridH - EDGE_PX)));

        if (dx == 0 && dy == 0) return;

        scrollTick = Math.max(0, scrollTick + dx);
        scrollRow  = Math.max(0, Math.min(maxScrollRow(), scrollRow + dy));
    }

    /** 滚轮：上下=音高，Shift=左右 tick，Ctrl=缩放 */
    private boolean handleScroll(double sx, double sy) {
        double v = (sy != 0) ? sy : sx;
        int dir = (v > 0) ? 1 : (v < 0 ? -1 : 0);
        if (dir == 0) return true;

        if (ctrlHeld) {
            zoom = Math.max(0, Math.min(6, zoom + dir));
            scrollRow = Math.min(scrollRow, maxScrollRow());
            return true;
        }
        if (shiftHeld) {
            int step = Math.max(1, visibleTicks() / 8);
            scrollTick = Math.max(0, scrollTick - dir * step);   // 上滚=往左
            return true;
        }
        scrollRow = Math.max(0, Math.min(maxScrollRow(), scrollRow - dir * 3));  // 上滚=看高音
        return true;
    }

    // 26.3 滚动方法签名不确定，多套重载（不带 @Override，避免签名不匹配）
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        return handleScroll(sx, sy);
    }

    public boolean mouseScrolled(double mx, double my, double sy) {
        return handleScroll(0, sy);
    }

    public boolean mouseScrolled(double sx, double sy) {
        return handleScroll(sx, sy);
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;

        if (nameBox != null) nameBox.setFocused(false);

        Minecraft mc = Minecraft.getInstance();
        double mx = toGuiX(mc.mouseHandler.xpos());
        double my = toGuiY(mc.mouseHandler.ypos());
        int button = click.button();

        if (button == 2) return false;
        if (mx < gridX || mx >= gridX + gridW || my < gridY || my >= gridY + gridH) return false;

        int col = (int) ((mx - gridX) / cellW());
        int row = (int) ((my - gridY) / cellH());
        int tick = scrollTick + col;
        int noteId = rowToNote(row);
        if (noteId < MIN_NOTE || noteId > MAX_NOTE) return false;

        if (boxSelecting) {
            dragCurTick = tick;
            dragCurNote = noteId;
            finishBoxSelect(shiftHeld);
            return true;
        }

        Long k = key(tick, noteId);
        EditableNote exist = noteIndex.get(k);

        if (button == 3 && exist != null) {
            if (selection.contains(exist)) {
                selection.remove(exist);
                System.out.println("[DJ-EDIT] UNSELECT(right) tick=" + tick + " " + noteName(noteId));
            } else {
                selection.add(exist);
                System.out.println("[DJ-EDIT] SELECT(right) tick=" + tick + " " + noteName(noteId)
                        + " (共 " + selection.size() + ")");
            }
            return true;
        }

        if (button == 1 && shiftHeld && exist != null) {
            if (selection.contains(exist)) {
                selection.remove(exist);
                System.out.println("[DJ-EDIT] UNSELECT tick=" + tick + " " + noteName(noteId));
            } else {
                selection.add(exist);
                System.out.println("[DJ-EDIT] SELECT tick=" + tick + " " + noteName(noteId)
                        + " (共 " + selection.size() + ")");
            }
            return true;
        }

        if (button != 1) return false;

        anchorTick = tick;
        anchorNote = noteId;
        dragCurTick = tick;
        dragCurNote = noteId;
        boxSelecting = true;
        return true;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return boxSelecting;
    }

    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        return boxSelecting;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (!boxSelecting) return false;
        finishBoxSelect(false);
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (!boxSelecting) return false;
        finishBoxSelect(false);
        return true;
    }

    private int nextFreeLayer(int tick) {
        Set<Integer> used = new HashSet<>();
        for (EditableNote n : notes) if (n.tick == tick) used.add(n.layer);
        int l = 0;
        while (used.contains(l)) l++;
        return l;
    }

    // ==================== 键码体系：MC 26.3 = SDL ====================

    private static final int SD_A = 4, SD_C = 6, SD_D = 7, SD_V = 25,
                             SD_X = 27, SD_Y = 28, SD_Z = 29;
    private static final int SD_ESC = 41, SD_BACKSPACE = 42, SD_SPACE = 44, SD_DELETE = 76,
                             SD_RIGHT = 79, SD_LEFT = 80, SD_DOWN = 81, SD_UP = 82;
    private static final int SD_LCTRL = 224, SD_LSHIFT = 225, SD_RCTRL = 228, SD_RSHIFT = 229;

    /** SDL Keymod */
    private static final int KM_SHIFT = 0x0003;   // LSHIFT 1 | RSHIFT 2
    private static final int KM_CTRL  = 0x00C0;   // LCTRL 64 | RCTRL 128
    private static final int KM_ALT   = 0x0300;

    /** GLFW 旧码兜底 */
    private static final int GL_A = 65, GL_C = 67, GL_D = 68, GL_V = 86,
                             GL_X = 88, GL_Y = 89, GL_Z = 90;
    private static final int GL_ESC = 256, GL_BACKSPACE = 259, GL_DELETE = 261,
                             GL_RIGHT = 262, GL_LEFT = 263, GL_DOWN = 264,
                             GL_UP = 265, GL_SPACE = 32;
    private static final int GL_LCTRL = 341, GL_RCTRL = 345, GL_LSHIFT = 340, GL_RSHIFT = 344;

    private static int readCode(Object evt) {
        if (evt == null) return 0;
        for (String fn : new String[]{"scancode", "key"}) {
            try {
                Field f = evt.getClass().getDeclaredField(fn);
                f.setAccessible(true);
                Object v = f.get(evt);
                if (v instanceof Number n) return n.intValue();
            } catch (Throwable ignored) {}
        }
        for (String mn : new String[]{"scancode", "key", "keycode"}) {
            try {
                Method m = evt.getClass().getMethod(mn);
                Object v = m.invoke(evt);
                if (v instanceof Number n) return n.intValue();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static int readMods(Object evt) {
        if (evt == null) return -1;
        try {
            Method m = evt.getClass().getMethod("modifiers");
            Object v = m.invoke(evt);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}
        try {
            Field f = evt.getClass().getDeclaredField("modifiers");
            f.setAccessible(true);
            Object v = f.get(evt);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}
        return -1;
    }

    private static Boolean boolMethod(Object evt, String mn) {
        if (evt == null) return null;
        try {
            Method m = evt.getClass().getMethod(mn);
            Object v = m.invoke(evt);
            if (v instanceof Boolean b) return b;
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isCtrl(Object evt, int mods, int code) {
        Boolean b = boolMethod(evt, "hasControlDown");
        if (b != null) return b;
        if (mods >= 0 && (mods & KM_CTRL) != 0) return true;
        if (code == SD_LCTRL || code == SD_RCTRL || code == GL_LCTRL || code == GL_RCTRL) return true;
        return ctrlHeld;
    }

    private boolean isShift(Object evt, int mods, int code) {
        Boolean b = boolMethod(evt, "hasShiftDown");
        if (b != null) return b;
        if (mods >= 0 && (mods & KM_SHIFT) != 0) return true;
        if (code == SD_LSHIFT || code == SD_RSHIFT || code == GL_LSHIFT || code == GL_RSHIFT) return true;
        return shiftHeld;
    }

    private static boolean isCtrlCode(int c) {
        return c == SD_LCTRL || c == SD_RCTRL || c == GL_LCTRL || c == GL_RCTRL;
    }

    private static boolean isShiftCode(int c) {
        return c == SD_LSHIFT || c == SD_RSHIFT || c == GL_LSHIFT || c == GL_RSHIFT;
    }

    private static void dumpOnce(Object evt) {
        if (diagDone) return;
        diagDone = true;
        try {
            System.out.println("[DJ-EDIT] ==== KeyEvent dump (SDL) ====\nclass="
                    + evt.getClass().getName()
                    + "\ncode=" + readCode(evt) + " mods=" + readMods(evt));
        } catch (Throwable t) {
            System.out.println("[DJ-EDIT] dump 失败: " + t);
        }
    }

    private boolean tryHotkey(int code, boolean ctrl) {
        if (ctrl) {
            if (code == SD_A || code == GL_A) { selectAll(); return true; }
            if (code == SD_C || code == GL_C) { copySelection(); return true; }
            if (code == SD_X || code == GL_X) { cutSelection(); return true; }
            if (code == SD_V || code == GL_V) { pasteAt(hoverTick >= 0 ? hoverTick : scrollTick); return true; }
            if (code == SD_D || code == GL_D) { duplicateSelection(); return true; }
            if (code == SD_Z || code == GL_Z) { undo(); return true; }
            if (code == SD_Y || code == GL_Y) { redo(); return true; }
            return false;
        }
        if (code == SD_DELETE || code == GL_DELETE
         || code == SD_BACKSPACE || code == GL_BACKSPACE) {
            deleteSelection();
            return true;
        }
        if (code == SD_ESC || code == GL_ESC) {
            if (boxSelecting) { boxSelecting = false; System.out.println("[DJ-EDIT] CANCEL BOX"); return true; }
            if (!selection.isEmpty()) {
                selection.clear();
                System.out.println("[DJ-EDIT] CLEAR SELECTION");
                return true;
            }
            close();
            return true;
        }
        if (code == SD_RIGHT || code == GL_RIGHT) { scrollTick += visibleTicks() / 2; return true; }
        if (code == SD_LEFT  || code == GL_LEFT)  { scrollTick = Math.max(0, scrollTick - visibleTicks() / 2); return true; }
        if (code == SD_UP    || code == GL_UP)    { scrollRow = Math.max(0, scrollRow - 2); return true; }
        if (code == SD_DOWN  || code == GL_DOWN)  { scrollRow = Math.min(maxScrollRow(), scrollRow + 2); return true; }
        if (code == SD_SPACE || code == GL_SPACE) {
            togglePause();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent evt) {
        dumpOnce(evt);

        int code = readCode(evt);
        int mods = readMods(evt);

        if (isCtrlCode(code))  ctrlHeld  = true;
        if (isShiftCode(code)) shiftHeld = true;

        boolean ctrl  = isCtrl(evt, mods, code);
        boolean shift = isShift(evt, mods, code);

        System.out.println("[DJ-EDIT] KEY code=" + code + " mods=" + mods
                + " ctrl=" + ctrl + " shift=" + shift);

        if (code != 0 && tryHotkey(code, ctrl)) return true;

        return super.keyPressed(evt);
    }

    @Override
    public boolean keyReleased(KeyEvent evt) {
        int code = readCode(evt);
        if (isCtrlCode(code))  ctrlHeld  = false;
        if (isShiftCode(code)) shiftHeld = false;
        return super.keyReleased(evt);
    }

    // ==================== 播放 ====================

    private void startPlayback(int startTick) {
        final int from = Math.max(0, startTick);   // 先算好，stopPlayback 会清 playheadTick
        stopPlayback();
        if (notes.isEmpty()) return;
        final List<EditableNote> snapshot = new ArrayList<>(notes);
        final int end = maxTick() + 2;
        final long tickMs = Math.max(1, 100000L / Math.max(1, song.tempo));

        playing = true;
        paused = false;
        playButton.setMessage(Component.translatable("disc_jockey.editor.stop"));

        playThread = new Thread(() -> {
            for (int t = from; t <= end && playing; t++) {
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
            // 暂停导致的中断：保留 playheadTick，供继播使用
            if (!paused) {
                playheadTick = -1;
                Minecraft.getInstance().execute(() ->
                        playButton.setMessage(Component.translatable("disc_jockey.editor.play")));
            }
        }, "DJ-NBSEditor-Play");
        playThread.start();
    }

    /** 从头播放 */
    private void startPlayback() {
        startPlayback(0);
    }

    private void stopPlayback() {
        playing = false;
        playheadTick = -1;
        if (playThread != null) { playThread.interrupt(); playThread = null; }
        if (playButton != null) playButton.setMessage(Component.translatable("disc_jockey.editor.play"));
    }

    private void playNoteSound(int noteId, int instrument) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        SoundEvent se = instSound(instrument);
        if (se == null) return;

        int k = noteId;
        while (k > PLAYABLE_HIGH) k -= 12;
        while (k < PLAYABLE_LOW)  k += 12;
        float pitch = (float) Math.pow(2.0D, (k - 12) / 12.0D);

        mc.execute(() -> mc.getSoundManager().play(
                SimpleSoundInstance.forUI(se, pitch, 1.0f)));
    }

    private static synchronized void initInstSounds() {
        if (instSounds != null) return;
        SoundEvent[] arr = new SoundEvent[INST_NAMES.length];
        for (int i = 0; i < arr.length; i++) arr[i] = resolveInstSound(i);
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
        try { nb = Note.fromNbs(4, i); } catch (Throwable ignored) {}
        if (nb == null) {
            try {
                NoteBlockInstrument[] t = Note.NBS_V4;
                if (i >= 0 && i < t.length) nb = t[i];
            } catch (Throwable ignored) {}
        }
        if (nb == null) return null;

        for (String mn : new String[]{"getSoundEvent", "soundEvent"}) {
            try {
                SoundEvent se = unwrap(nb.getClass().getMethod(mn).invoke(nb));
                if (se != null) return se;
            } catch (Throwable ignored) {}
        }
        return null;
    }

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
        return se != null ? se : instSounds[0];
    }

    private static synchronized void initInstKeys() {
        if (instKeys != null) return;
        String[] arr = new String[INST_NAMES.length];
        for (int i = 0; i < arr.length; i++) arr[i] = resolveInstKey(i);
        instKeys = arr;

        StringBuilder sb = new StringBuilder("[DJ-EDIT] instrument keys:");
        for (int i = 0; i < arr.length; i++) {
            sb.append("\n  [").append(i).append("] ").append(INST_NAMES[i])
              .append(" -> ").append(arr[i] == null ? "(none, use literal)" : arr[i]);
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
                if (v instanceof String s && !s.isEmpty())
                    return "block.minecraft.note_block." + s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void save(boolean saveAs) {
        try {
            packToSong();
            String base = (song.fileName != null && !song.fileName.isEmpty())
                    ? song.fileName.replaceAll("\\.(?i)nbs$", "") : "new_song";
            if (saveAs) base = base + "_edit";
            song.fileName = base + ".nbs";
            song.searchableFileName = song.fileName.toLowerCase();

            File out = new File(Main.songsFolder, base + ".nbs");

            boolean ok = false;
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
            selection.clear();
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

    public static void showMsg(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
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

    /** 从 GuiGraphicsExtractor 反射取鼠标坐标（26.4 未必有 mouseX()/mouseY()，取不到按 0 处理） */
    private static int extractorInt(Object c, String name, int def) {
        try {
            java.lang.reflect.Method m = c.getClass().getMethod(name);
            Object v = m.invoke(c);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {}
        for (java.lang.reflect.Method m : c.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0 && m.getReturnType() == int.class) {
                try { return (Integer) m.invoke(c); } catch (Throwable ignored) {}
            }
        }
        return def;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor c, int mx, int my, float delta) {
        mouseX = mx;
        mouseY = my;

        // ★ 框选拖拽时，靠近边缘自动滚动（必须在计算 hover / drag 之前）
        autoScrollEdge(mx, my);

        super.extractRenderState(c, mx, my, delta);

        int ch = cellH(), cw = cellW();
        int rows = visibleRows(), ticks = visibleTicks();

        c.fill(gridX, gridY, gridX + gridW, gridY + gridH, 0xFF101014);

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

        hoverTick = -1;
        hoverNote = Integer.MIN_VALUE;
        boolean inGrid = (mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH);
        if (inGrid) {
            hoverTick = scrollTick + (int) ((mx - gridX) / cw);
            int hn = rowToNote((int) ((my - gridY) / ch));
            if (hn >= MIN_NOTE && hn <= MAX_NOTE) hoverNote = hn;
        }

        // ★ 框选中：即使鼠标拖出网格，也用 clamp 后的坐标更新终点（保证边缘滚动时框选持续扩展）
        if (boxSelecting) {
            int cx = (int) Math.max(0, Math.min(gridW - 1, mx - gridX));
            int cy = (int) Math.max(0, Math.min(gridH - 1, my - gridY));
            dragCurTick = scrollTick + cx / cw;
            int dn = rowToNote(cy / ch);
            dragCurNote = Math.max(MIN_NOTE, Math.min(MAX_NOTE, dn));
        }

        if (hoverTick >= 0 && hoverNote >= MIN_NOTE && hoverNote <= MAX_NOTE) {
            int hx = gridX + (hoverTick - scrollTick) * cw;
            int hr = noteToRow(hoverNote);
            if (hr >= 0) {
                int hy = gridY + hr * ch;
                c.fill(hx, hy, hx + Math.max(2, cw - 1), hy + Math.max(2, ch - 1), 0x40FFFFFF);
            }
        }

        for (EditableNote n : notes) {
            if (n.tick < scrollTick || n.tick >= scrollTick + ticks) continue;
            int r = noteToRow(n.noteId);
            if (r < 0) continue;
            int x = gridX + (n.tick - scrollTick) * cw;
            int y = gridY + r * ch;
            boolean sel = selection.contains(n);
            // ★ 按实际音色着色（原来只分黑键/白键的蓝/绿两色，看不出音色）
            int col = sel ? 0xFFFFEB3B : instColor(n.instrument);
            int bw = Math.max(2, cw - 1);
            int bh = Math.max(2, ch - 1);
            c.fill(x, y, x + bw, y + bh, col);
            // 格子够大时叠加乐器图标；贴图取不到就静默保持纯色，绝不开天窗
            if (ICONS_ENABLED && !sel && bw >= ICON_MIN_CELL && bh >= ICON_MIN_CELL) {
                Identifier tex = instTexture(n.instrument);
                if (tex != null && resourceExists(n.instrument, tex)) {
                    // 图标画满整个格子（不再内缩 2px），小格子下更容易看清材质纹理
                    if (blitIcon(c, tex, x, y, bw, bh) && !iconDrawnLogged) {
                        iconDrawnLogged = true;
                        LOGGER.info("[Editor] \u2713 \u5df2\u7ed8\u5236\u97f3\u8272\u56fe\u6807\uff1a{}\uff0c\u5c3a\u5bf8={}x{}\uff08\u683c\u5b50={}x{}\uff09",
                                tex, bw, bh, cw, ch);
                    }
                }
            }
        }

        if (boxSelecting && anchorTick >= 0 && dragCurTick >= 0
                && anchorNote != Integer.MIN_VALUE && dragCurNote != Integer.MIN_VALUE) {
            int t0 = Math.min(anchorTick, dragCurTick), t1 = Math.max(anchorTick, dragCurTick);
            int n0 = Math.min(anchorNote, dragCurNote), n1 = Math.max(anchorNote, dragCurNote);

            if (t0 == t1 && n0 == n1) {
                int x0 = gridX + (t0 - scrollTick) * cw;
                int r0 = noteToRow(n0);
                if (r0 >= 0) {
                    int y0 = gridY + r0 * ch;
                    c.fill(x0, y0, x0 + Math.max(2, cw - 1), y0 + Math.max(2, ch - 1), 0x80FFFFFF);
                }
            } else {
                int x0 = gridX + (t0 - scrollTick) * cw;
                int x1 = gridX + (t1 - scrollTick + 1) * cw;
                int rTop = noteToRow(n1), rBot = noteToRow(n0);
                if (rTop < 0) rTop = 0;
                if (rBot < 0) rBot = rows - 1;
                int y0 = gridY + rTop * ch;
                int y1 = gridY + (rBot + 1) * ch;

                c.fill(x0, y0, x1, y1, 0x30FFFF00);
                c.fill(x0, y0, x1, y0 + 1, 0xFFFFEB3B);
                c.fill(x0, y1 - 1, x1, y1, 0xFFFFEB3B);
                c.fill(x0, y0, x0 + 1, y1, 0xFFFFEB3B);
                c.fill(x1 - 1, y0, x1, y1, 0xFFFFEB3B);
            }
        }

        // 边缘自动滚动提示条
        if (boxSelecting) {
            int tint = 0x30FFFF00;
            c.fill(gridX, gridY, gridX + gridW, gridY + 3, tint);
            c.fill(gridX, gridY + gridH - 3, gridX + gridW, gridY + gridH, tint);
            c.fill(gridX, gridY, gridX + 3, gridY + gridH, tint);
            c.fill(gridX + gridW - 3, gridY, gridX + gridW, gridY + gridH, tint);
        }

        if (playheadTick >= scrollTick && playheadTick < scrollTick + ticks) {
            int x = gridX + (playheadTick - scrollTick) * cw;
            c.fill(x, gridY, x + 2, gridY + gridH, 0xFFFFEB3B);
        }

        drawKeyboard(c, ch, rows);

        String st = "notes=" + notes.size()
                + " | ticks=" + (maxTick() + 1)
                + " | tempo=" + song.tempo + "(" + String.format("%.1f", song.tempo / 100.0) + " tps)"
                + " | tick@" + scrollTick
                + " | row@" + scrollRow
                + " | zoom=" + zoom
                + " | instr=" + instName(curInstrument)
                + " | sel=" + selection.size()
                + " | clip=" + clipboard.size()
                + (ctrlHeld ? " | CTRL" : "")
                + (boxSelecting ? " | ▧BOX(边缘滚动)" : "")
                + (playing ? " | ▶PLAYING" : "");
        c.text(font, Component.literal(st), gridX, height - 16, 0xFFAAAAAA, false);
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

    private static final Set<Integer> WHITE_PC = Set.of(0, 2, 4, 5, 7, 9, 11);

    /** 音色 → 色块颜色，越界返回 Harp 色 */
    private static int instColor(int instrument) {
        if (instrument >= 0 && instrument < INST_COLOR.length) return INST_COLOR[instrument];
        return INST_COLOR[0];
    }

    /**
     * 音色 → 项目自带贴图（assets/disc_jockey/textures/item/<名>.png）。
     * Identifier 工厂方法名 26.3 / 26.4 不同（fromNamespaceAndPath / withDefaultNamespace /
     * parse），三级反射兜底；全失败返回 null（调用方退回纯色，不崩溃）。
     * 只尝试一次，失败后不再重复反射。
     */

    // ==================== 贴图存在性校验 ====================
    //
    // 之前出现「黑紫棋盘」= MC 的 missing-texture，说明 blit 调用成功但
    // 资源包里找不到该 Identifier 对应的 png。这里在首次绘制前用
    // ResourceManager 实际查一次；不存在就不画（保持纯色），
    // 并把缺失的贴图名打进日志，方便定位是路径写错还是没打进 jar。

    /** 每个音色的贴图可用状态：null=未查过，TRUE=可用，FALSE=缺失 */
    private static final Boolean[] instTexOk = new Boolean[INST_TEX.length];

    private static volatile java.lang.reflect.Method rmGetter;
    private static volatile java.lang.reflect.Method resGetter;
    private static volatile boolean rmTried = false;
    /**
     * \u63a2\u9488\u662f\u5426\u53ef\u4fe1\u3002\u5224\u636e\uff1aMC \u539f\u7248\u65b9\u5757\u8d34\u56fe\uff08minecraft:block/note_block\u3001stone\uff09\u5fc5\u5b9a\u5b58\u5728\uff0c
     * \u82e5\u8fde\u5b83\u4eec\u90fd\u62a5\u201c\u7f3a\u5931\u201d\uff0c\u8bf4\u660e\u662f\u8d44\u6e90\u7ba1\u7406\u5668\u53cd\u5c04\u53d6\u9519\u4e86\uff0c
     * \u6b64\u65f6\u4e0d\u518d\u7528\u63a2\u9488\u62e6\u622a\u7ed8\u5236\uff0c\u76f4\u63a5\u753b \u2014\u2014 \u907f\u514d\u628a\u672c\u6765\u80fd\u663e\u793a\u7684\u56fe\u6807\u8bef\u5224\u6210\u7f3a\u5931\u3002
     */
    private static volatile boolean probeTrusted = true;

    /**
     * 运行时确认 assets/disc_jockey/textures/item/<名>.png 是否真的在资源包里。
     * 只查一次，结果缓存；查不到 ResourceManager 时返回 true（按可用处理，
     * 由 blit 自己兜底），避免因为反射失败而永远不画图标。
     */
    private static boolean resourceExists(int instrument, Identifier id) {
        // 探针不可信（连 MC 原版贴图都报缺失）时不再拦截，直接画，避免误判成缺失
        if (!probeTrusted) return true;
        if (instrument >= 0 && instrument < instTexOk.length) {
            Boolean v = instTexOk[instrument];
            if (v != null) return v;
        }
        boolean ok = true;
        try {
            Object mc = mcInstance();
            if (mc != null && rmGetter != null) {
                Object rm = rmGetter.invoke(mc);
                if (rm != null && resGetter != null) {
                    Object r = resGetter.invoke(rm, id);
                    if (r instanceof java.util.Optional) {
                        ok = ((java.util.Optional<?>) r).isPresent();   // 1.20.6+ 返回 Optional
                    } else {
                        ok = (r != null);                                // 旧版直接返回 Resource / 缺失返回 null
                    }
                }
            }
        } catch (Throwable ignored) {
            ok = true;
        }
        if (instrument >= 0 && instrument < instTexOk.length) {
            instTexOk[instrument] = ok;
            if (!ok) {
                LOGGER.warn("[Editor] \u8d34\u56fe\u7f3a\u5931\uff08\u9000\u56de\u7eaf\u8272\uff09\uff1a{} "
                                + "\u2192 assets/disc_jockey/textures/item/{}.png "
                                + "\u4e0d\u5728\u8d44\u6e90\u5305\u91cc\uff0c\u8bf7\u786e\u8ba4\u5df2\u6253\u8fdb jar",
                        id, INST_TEX[instrument]);
            }
        }
        return ok;
    }

    /** 反射拿 Minecraft 实例 + getResourceManager / getResource，只做一次 */
    private static void ensureResourceManager() {
        if (rmTried) return;
        rmTried = true;
        try {
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
            java.lang.reflect.Method inst = null;
            for (java.lang.reflect.Method m : mcCls.getMethods()) {
                if (m.getParameterCount() == 0 && mcCls.isAssignableFrom(m.getReturnType())) { inst = m; break; }
            }
            if (inst == null) return;
            Object mc = inst.invoke(null);
            Object rm = null;
            for (java.lang.reflect.Method m : mcCls.getMethods()) {
                if (m.getName().equals("getResourceManager") && m.getParameterCount() == 0) {
                    rmGetter = m; rm = m.invoke(mc); break;
                }
            }
            if (rm == null) return;
            for (java.lang.reflect.Method m : rm.getClass().getMethods()) {
                if (m.getName().equals("getResource") && m.getParameterCount() == 1) { resGetter = m; break; }
            }
            LOGGER.info("[Editor] \u8d44\u6e90\u63a2\u9488\uff1aResourceManager={} | getResource={}",
                    rm.getClass().getName(), (resGetter == null ? "\u672a\u627e\u5230" : resGetter.toString()));
            probe("noteblock");
            probe("stone");
            boolean v1 = vanillaProbe("note_block");
            boolean v2 = vanillaProbe("stone");
            if (!v1 && !v2) {
                probeTrusted = false;
                LOGGER.warn("[Editor] \u8fde MC \u539f\u7248\u8d34\u56fe\u90fd\u67e5\u4e0d\u5230\uff0c\u63a2\u9488\u4e0d\u53ef\u4fe1\uff0c\u6539\u4e3a\u76f4\u63a5\u7ed8\u5236\uff08\u4e0d\u518d\u62e6\u622a\uff09");
            }
            dumpDjTextures();
        } catch (Throwable ignored) {}
    }

    /** \u5217\u51fa\u8fd0\u884c\u65f6 disc_jockey \u547d\u540d\u7a7a\u95f4\u4e0b textures/ \u5f00\u5934\u7684\u8d44\u6e90\uff0c\u7528\u4e8e\u5b9a\u4f4d png \u5b9e\u9645\u8def\u5f84 */
    private static void dumpDjTextures() {
        try {
            Object mc = mcInstance();
            if (mc == null || rmGetter == null) return;
            Object rm = rmGetter.invoke(mc);
            if (rm == null) return;
            java.lang.reflect.Method listM = null;
            for (java.lang.reflect.Method m : rm.getClass().getMethods()) {
                if (!m.getName().equals("listResources")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 2 && ps[0] == String.class && ps[1] == java.util.function.Predicate.class) {
                    listM = m; break;
                }
            }
            if (listM == null) {
                LOGGER.info("[Editor] \u65e0 listResources(String,Predicate) \u91cd\u8f7d\uff0c\u8df3\u8fc7\u8d44\u6e90\u6e05\u5355\u679a\u4e3e");
                return;
            }
            java.util.function.Predicate<Object> pred = o -> {
                try {
                    if (!(o instanceof Identifier)) return false;
                    Identifier id = (Identifier) o;
                    return "disc_jockey".equals(id.getNamespace()) && id.getPath().startsWith("textures/");
                } catch (Throwable t) { return false; }
            };
            Object res = listM.invoke(rm, "textures/", pred);
            if (res instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) res;
                StringBuilder sb = new StringBuilder();
                int i = 0;
                for (Object k : map.keySet()) {
                    if (i++ >= 40) break;
                    sb.append("\n  ").append(k);
                }
                LOGGER.info("[Editor] disc_jockey \u547d\u540d\u7a7a\u95f4\u4e0b textures \u8d44\u6e90\u5171 {} \u6761{}",
                        map.size(), (map.isEmpty()
                                ? "\uff08\u5b8c\u5168\u4e3a\u7a7a\uff01png \u6ca1\u8fdb\u8d44\u6e90\u5305\uff09" : sb.toString()));
            } else {
                LOGGER.info("[Editor] listResources \u8fd4\u56de\u7c7b\u578b={}", (res == null ? "null" : res.getClass().getName()));
            }
        } catch (Throwable t) {
            LOGGER.info("[Editor] \u8d44\u6e90\u6e05\u5355\u679a\u4e3e\u5931\u8d25\uff1a{}", t);
        }
    }

    /** \u53cd\u5c04\u9020 disc_jockey:<path> \u7684 Identifier\uff08\u517c\u5bb9 26.3 / 26.4 \u5de5\u5382\u65b9\u6cd5\u540d\u5dee\u5f02\uff09 */
    private static Identifier djId(String path) {
        try {
            Class<?> ic = Identifier.class;
            try {
                java.lang.reflect.Method m = ic.getMethod("fromNamespaceAndPath", String.class, String.class);
                Object o = m.invoke(null, "disc_jockey", path);
                if (o instanceof Identifier) return (Identifier) o;
            } catch (Throwable ignored) {
                try {
                    java.lang.reflect.Method m = ic.getMethod("withDefaultNamespace", String.class);
                    Object o = m.invoke(null, "disc_jockey:" + path);
                    if (o instanceof Identifier) return (Identifier) o;
                } catch (Throwable ignored2) {
                    try {
                        java.lang.reflect.Method m = ic.getMethod("parse", String.class);
                        Object o = m.invoke(null, "disc_jockey:" + path);
                        if (o instanceof Identifier) return (Identifier) o;
                    } catch (Throwable ignored3) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** \u63a2 MC \u539f\u7248\u8d34\u56fe\u662f\u5426\u5b58\u5728\uff08\u539f\u7248\u8d44\u6e90\u5fc5\u5b9a\u5b58\u5728\uff0c\u7528\u6765\u6821\u9a8c\u63a2\u9488\u672c\u8eab\u662f\u5426\u53ef\u4fe1\uff09 */
    private static boolean vanillaProbe(String texName) {
        try {
            Identifier id = buildId("minecraft", "textures/block/" + texName + ".png");
            if (id == null) {
                LOGGER.info("[Editor] \u539f\u7248\u63a2\u9488 {} \uff1aIdentifier \u521b\u5efa\u5931\u8d25", texName);
                return false;
            }
            Object mc = mcInstance();
            if (mc == null || rmGetter == null || resGetter == null) return false;
            Object r = resGetter.invoke(rmGetter.invoke(mc), id);
            boolean ok;
            String verdict;
            if (r instanceof java.util.Optional) {
                ok = ((java.util.Optional<?>) r).isPresent();
                verdict = ok ? "\u5b58\u5728" : "\u7f3a\u5931(Optional.empty)";
            } else if (r == null) {
                ok = false;
                verdict = "\u7f3a\u5931(null)";
            } else {
                ok = true;
                verdict = "\u5b58\u5728(" + r.getClass().getSimpleName() + ")";
            }
            LOGGER.info("[Editor] \u539f\u7248\u63a2\u9488 {} \u2192 {} \uff1a{}", texName, id, verdict);
            return ok;
        } catch (Throwable t) {
            LOGGER.info("[Editor] \u539f\u7248\u63a2\u9488 {} \u5f02\u5e38\uff1a{}", texName, t);
            return false;
        }
    }

    /** \u4e00\u6b21\u6027\u63a2\u9488\uff1a\u76f4\u63a5\u67e5\u67d0\u4e2a\u8d34\u56fe\u80fd\u4e0d\u80fd\u53d6\u5230\uff0c\u7ed3\u679c\u6253\u5370\u5230\u65e5\u5fd7 */
    private static void probe(String texName) {
        try {
            Identifier id = djId("textures/item/" + texName + ".png");
            if (id == null) { LOGGER.info("[Editor] \u63a2\u9488 {}\uff1aIdentifier \u521b\u5efa\u5931\u8d25", texName); return; }
            Object mc = mcInstance();
            Object r = null;
            if (mc != null && rmGetter != null && resGetter != null) {
                r = resGetter.invoke(rmGetter.invoke(mc), id);
            }
            String verdict;
            if (r instanceof java.util.Optional) verdict = ((java.util.Optional<?>) r).isPresent() ? "\u5b58\u5728" : "\u7f3a\u5931(Optional.empty)";
            else if (r == null) verdict = "\u7f3a\u5931(null)";
            else verdict = "\u5b58\u5728(" + r.getClass().getSimpleName() + ")";
            LOGGER.info("[Editor] \u63a2\u9488 {} \u2192 {} \uff1a{}", texName, id, verdict);
        } catch (Throwable t) {
            LOGGER.info("[Editor] \u63a2\u9488 {} \u5f02\u5e38\uff1a{}", texName, t);
        }
    }

    private static Object mcInstance() {
        try {
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
            for (java.lang.reflect.Method m : mcCls.getMethods()) {
                if (m.getParameterCount() == 0 && mcCls.isAssignableFrom(m.getReturnType())) return m.invoke(null);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** \u6309 namespace + path \u53cd\u5c04\u9020 Identifier\uff1a26.3 / 26.4 \u5de5\u5382\u65b9\u6cd5\u540d\u4e0d\u540c\uff0c\u4e09\u7ea7\u515c\u5e95 */
    private static Identifier buildId(String ns, String path) {
        try {
            Class<?> ic = Identifier.class;
            try {
                java.lang.reflect.Method m = ic.getMethod("fromNamespaceAndPath", String.class, String.class);
                Object o = m.invoke(null, ns, path);
                if (o instanceof Identifier) return (Identifier) o;
            } catch (Throwable ignored) {
                try {
                    java.lang.reflect.Method m = ic.getMethod("withDefaultNamespace", String.class);
                    Object o = m.invoke(null, ns + ":" + path);
                    if (o instanceof Identifier) return (Identifier) o;
                } catch (Throwable ignored2) {
                    try {
                        java.lang.reflect.Method m = ic.getMethod("parse", String.class);
                        Object o = m.invoke(null, ns + ":" + path);
                        if (o instanceof Identifier) return (Identifier) o;
                    } catch (Throwable ignored3) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * \u53ea\u63a2\u4e00\u6b21\u8d44\u6e90\u662f\u5426\u5b58\u5728\uff08\u4e0d\u6253\u65e5\u5fd7\u3001\u4e0d\u7f13\u5b58\uff09\u3002
     * \u53cd\u5c04\u5931\u8d25\u65f6\u8fd4\u56de true\uff08\u6309\u53ef\u7528\u5904\u7406\uff0c\u7531 blit \u81ea\u5df2\u515c\u5e95\uff09\uff0c
     * \u907f\u514d\u56e0\u4e3a\u53cd\u5c04\u95ee\u9898\u800c\u6c38\u8fdc\u4e0d\u753b\u56fe\u6807\u3002
     */
    private static boolean probeResource(Identifier id) {
        if (!probeTrusted) return true;
        try {
            Object mc = mcInstance();
            if (mc != null && rmGetter != null) {
                Object rm = rmGetter.invoke(mc);
                if (rm != null && resGetter != null) {
                    Object r = resGetter.invoke(rm, id);
                    if (r instanceof java.util.Optional) {
                        return ((java.util.Optional<?>) r).isPresent();
                    }
                    return r != null;
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * \u53d6\u97f3\u8272\u56fe\u6807\uff0c\u4e24\u7ea7\u9009\u62e9\uff1a
     *   \u2460 mod \u81ea\u5e26\u8d34\u56fe assets/disc_jockey/textures/item/<name>.png
     *   \u2461 \u515c\u5e95 MC \u539f\u7248\u65b9\u5757\u8d34\u56fe assets/minecraft/textures/block/<name>.png
     * \u539f\u7248\u8d44\u6e90\u4e00\u5b9a\u5b58\u5728\uff0c\u6240\u4ee5\u5373\u4f7f mod \u8d34\u56fe\u6ca1\u6253\u8fdb jar\uff0c
     * \u56fe\u6807\u4e5f\u4e0d\u4f1a\u9000\u5316\u6210\u7eaf\u8272\u6216\u9ed1\u7d2b\u68cb\u76d8\u3002
     */
    private static Identifier instTexture(int instrument) {
        if (instrument < 0 || instrument >= INST_TEX.length) return null;
        if (instTexDead[instrument]) return null;
        Identifier cached = instTexCache[instrument];
        if (cached != null) return cached;
        ensureResourceManager();

        Identifier id = buildId("disc_jockey", "textures/item/" + INST_TEX[instrument] + ".png");
        if (id != null && probeResource(id)) {
            ensureTexture(instrument, id);
            instTexCache[instrument] = id;
            return id;
        }

        String vn = (instrument < INST_TEX_VANILLA.length) ? INST_TEX_VANILLA[instrument] : INST_TEX[instrument];
        Identifier vid = buildId("minecraft", "textures/block/" + vn + ".png");
        if (vid != null && probeResource(vid)) {
            ensureTexture(instrument, vid);
            instTexCache[instrument] = vid;
            if (!vanillaFallbackLogged) {
                vanillaFallbackLogged = true;
                LOGGER.info("[Editor] mod \u81ea\u5e26\u8d34\u56fe\u4e0d\u53ef\u7528\uff0c\u5df2\u5207\u6362\u4e3a MC \u539f\u7248\u65b9\u5757\u8d34\u56fe\uff08minecraft:block/...\uff09");
            }
            return vid;
        }

        instTexDead[instrument] = true;
        instTexTried = true;
        LOGGER.warn("[Editor] \u56fe\u6807\u8d34\u56fe\u5747\u4e0d\u53ef\u7528\uff08instrument={}\uff09\uff0c\u9000\u56de\u7eaf\u8272", instrument);
        return null;
    }

    // ==================== 贴图注册 ====================
    //
    // 26.x 的贴图 Identifier 是「全路径」形式：命名空间:textures/子目录/名.png
    //   证据：日志里 MC 自己打的图集名 minecraft:textures/atlas/blocks.png-atlas
    // 之前写成 disc_jockey:item/noteblock（旧版省略 textures/ 与 .png 的写法），
    // ResourceManager 查不到，blit 也拿不到已注册纹理，于是画成
    // MISSING_TEXTURE（黑紫棋盘）。这里统一改全路径，并在 blit 前把 id
    // 注册进 TextureManager（26.3 实测有 registerForNextReload / register 两个入口）。

    /** 反射拿 TextureManager 实例 */
    private static Object textureManager() {
        try {
            Object mc = mcInstance();
            if (mc == null) return null;
            for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getName().contains("TextureManager")) {
                    return m.invoke(mc);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 让 TextureManager 认识这个贴图 id；失败也不影响后续 blit 尝试 */
    private static void ensureTexture(int instrument, Identifier id) {
        if (!TEXTURE_REGISTER) return;
        if (instrument >= 0 && instrument < instTexRegistered.length) {
            if (instTexRegistered[instrument]) return;
            instTexRegistered[instrument] = true;
        }
        try {
            Object tm = textureManager();
            if (tm == null) return;
            String used = null;
            // ★ 先走「真加载」：register(id, SimpleTexture) 会立刻把贴图传上 GPU。
            //   而 registerForNextReload(id) 只是登记「下次资源重载时才加载」，
            //   本次会话内 getTexture 拿不到纹理 -> blit 抛异常 -> 图标只剩纯色。
            try {
                java.lang.reflect.Method regM = null;
                for (java.lang.reflect.Method m : tm.getClass().getMethods()) {
                    if (m.getName().equals("register") && m.getParameterCount() == 2) { regM = m; break; }
                }
                if (regM != null) {
                    Class<?> st = Class.forName("net.minecraft.client.renderer.texture.SimpleTexture");
                    Object tex = st.getConstructor(Identifier.class).newInstance(id);
                    regM.invoke(tm, id, tex);
                    used = "register(SimpleTexture)";
                }
            } catch (Throwable t) {
                LOGGER.info("[Editor] register(SimpleTexture) 不可用（{}），改用 registerForNextReload", t);
            }
            if (used == null) {
                java.lang.reflect.Method reloadM = null;
                for (java.lang.reflect.Method m : tm.getClass().getMethods()) {
                    if (m.getName().equals("registerForNextReload") && m.getParameterCount() == 1) { reloadM = m; break; }
                }
                if (reloadM != null) {
                    reloadM.invoke(tm, id);
                    used = "registerForNextReload";
                }
            }
            if (used != null && !texRegLogged) {
                texRegLogged = true;
                LOGGER.info("[Editor] \u8d34\u56fe\u5df2\u6ce8\u518c\u5230 TextureManager\uff08{}\uff09\uff1a{}", used, id);
            }
        } catch (Throwable t) {
            if (!texRegLogged) {
                texRegLogged = true;
                LOGGER.info("[Editor] \u8d34\u56fe\u6ce8\u518c\u5931\u8d25\uff08\u4ecd\u4f1a\u5c1d\u8bd5\u7ed8\u5236\uff09\uff1a{}", t);
            }
        }
    }

    // ==================== 图标绘制：按真实 blit 签名适配 ====================
    //
    // 为什么不再直接用 BlitCompat.blit：
    //   BlitCompat 的形参名是 (gfx, texture, x, y, u, v, w, h, tw, th)，
    //   但 26.4 实测命中的真实方法是
    //     blit(Identifier, int, int, int, int, float, float, float, float)
    //   即 (texture, x, y, width, height, u0, v0, uWidth, vHeight) ——
    //   后面 4 个是「0..1 归一化」的 float，不是像素 UV。
    //   于是 BlitCompat 传的 u/v 落到了 width/height 上（=0），
    //   调用不报错但什么都画不出来 —— 这正是之前只剩纯色块的原因。
    //
    // 这里自己按形参「类型」判定该用哪种实参排布：
    //   · 末 4 位是 float  → 归一化：tex,x,y,w,h, 0f,0f,1f,1f
    //   · 末 4 位是 int    → 像素  ：tex,x,y, 0,0,w,h, 16,16
    // 命中后缓存 Method，之后零反射。

    private static volatile java.lang.reflect.Method iconBlit;
    private static volatile int iconBlitPlan = -1;
    private static volatile Object iconBlitPipe;
    private static volatile boolean iconBlitTried = false;
    private static volatile boolean iconBlitWarned = false;
    private static volatile boolean iconDrawnLogged = false;

    /**
     * 把 16x16 图标铺满 w x h；失败返回 false，调用方保持纯色。
     *
     * 不再猜签名：按参数【类型】自动排布。
     *   - 找出 Identifier 参数下标 ti
     *   - 其余参数里，数值型归为 8 个位置参数，对象型（RenderPipeline）填管线
     *   - plan 0 像素 UV  ：x, y, u=0,      v=0,      w, h, tw=16, th=16
     *     （对应签名 int,int,float,float,int,int,int,int —— 26.3 带管线）
     *   - plan 1 归一化 UV：x, y, w,       h,       0,  0,  1,     1
     *     （对应签名 int,int,int,int,float,float,float,float —— 26.4 无管线）
     * 按 float 出现的位置挑 plan，挑错再退试另一种。
     */
    private static boolean blitIcon(GuiGraphicsExtractor c, Identifier tex, int x, int y, int w, int h)  {
        if (c == null || tex == null) return false;

        java.lang.reflect.Method m = iconBlit;
        if (m != null && iconBlitPlan >= 0) {
            try {
                m.invoke(c, fillArgs(m, iconBlitPlan, iconBlitPipe, tex, x, y, w, h));
                return true;
            } catch (Throwable t) {
                iconBlit = null;
                iconBlitPlan = -1;
            }
        }
        if (iconBlitTried) return false;
        iconBlitTried = true;

        Object pipe = null;
        try { pipe = BlitCompat.guiPipeline(); } catch (Throwable ignored) {}

        StringBuilder diag = new StringBuilder();
        StringBuilder errs = new StringBuilder();

        // \u7b2c 0 \u8f6e\uff1a\u53ea\u8bd5\u5e26\u7ba1\u7ebf\u7684\u91cd\u8f7d\uff1b\u7b2c 1 \u8f6e\uff1a\u624d\u9000\u56de\u65e0\u7ba1\u7ebf\u3002
        for (int pass = 0; pass < 2 && iconBlit == null; pass++) {
            for (java.lang.reflect.Method cand : GuiGraphicsExtractor.class.getMethods()) {
                if (!"blit".equals(cand.getName())) continue;
                Class<?>[] p = cand.getParameterTypes();
                if (pass == 0) diag.append(sig(cand)).append(' ');

                int ti = -1;
                for (int k = 0; k < p.length; k++) {
                    if (p[k] == Identifier.class || p[k].isAssignableFrom(Identifier.class)
                            || Identifier.class.isAssignableFrom(p[k])) { ti = k; break; }
                }
                if (ti < 0) continue;

                java.util.List<Integer> nums = new java.util.ArrayList<>();
                java.util.List<Integer> pipes = new java.util.ArrayList<>();
                for (int k = 0; k < p.length; k++) {
                    if (k == ti) continue;
                    if (isInt(p[k]) || isFloat(p[k])) nums.add(k);
                    else pipes.add(k);
                }
                if (nums.size() != 8) continue;
                if (!pipes.isEmpty() && pipe == null) continue;
                if (pass == 0 && pipes.isEmpty()) continue;
                if (pass == 1 && !pipes.isEmpty()) continue;

                // float \u843d\u5728\u7b2c 2,3 \u4e2a\u6570\u503c\u4f4d -> \u50cf\u7d20 UV\uff1b\u843d\u5728\u672b\u5c3e 4 \u4f4d -> \u5f52\u4e00\u5316 UV
                boolean pixFirst = isFloat(p[nums.get(2)]) || isFloat(p[nums.get(3)]);
                int[] order = pixFirst ? new int[]{0, 1} : new int[]{1, 0};
                for (int plan : order) {
                    try {
                        cand.invoke(c, fillArgs(cand, plan, pipe, tex, x, y, w, h));
                        iconBlit = cand;
                        iconBlitPlan = plan;
                        iconBlitPipe = pipe;
                        LOGGER.info("[Editor] \u2713 \u97f3\u8272\u56fe\u6807 blit \u5c31\u7eea\uff1a{}\uff0c\u6392\u5e03={}",
                                sig(cand), plan == 0 ? "\u50cf\u7d20UV" : "\u5f52\u4e00\u5316UV");
                        return true;
                    } catch (Throwable t) {
                        errs.append(sig(cand)).append("/plan").append(plan)
                            .append(" -> ").append(t).append("; ");
                    }
                }
            }
        }

        if (!iconBlitWarned) {
            iconBlitWarned = true;
            LOGGER.warn("[Editor] \u627e\u4e0d\u5230\u53ef\u7528 blit\uff08\u56fe\u6807\u9000\u56de\u7eaf\u8272\uff09\u3002\u5019\u9009\uff1a{} \u2225 \u5931\u8d25\u539f\u56e0\uff1a{}", diag, errs);
        }
        return false;
    }

    /** 按 plan 把实参排布到形参上（数值按形参声明类型做 int/float/double 转换） */
    private static Object[] fillArgs(java.lang.reflect.Method m, int plan, Object pipe,
                                     Identifier tex, int x, int y, int w, int h) {
        Class<?>[] p = m.getParameterTypes();
        Object[] a = new Object[p.length];

        int ti = -1;
        for (int i = 0; i < p.length; i++) {
            if (p[i].isAssignableFrom(Identifier.class) || Identifier.class.isAssignableFrom(p[i])) {
                ti = i;
                break;
            }
        }
        java.util.List<Integer> nums = new java.util.ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            if (i == ti) continue;
            if (isInt(p[i]) || isFloat(p[i])) nums.add(i);
            else a[i] = pipe;
        }
        a[ti] = tex;

        int[] vals;
        if (plan == 0) vals = new int[]{x, y, 0, 0, w, h, 16, 16};        // 像素 UV
        else           vals = new int[]{x, y, w, h, 0, 0, 1, 1};          // 归一化 UV

        for (int k = 0; k < nums.size() && k < vals.length; k++) {
            int idx = nums.get(k);
            a[idx] = num(vals[k], p[idx]);
        }
        return a;
    }

    private static Object num(int v, Class<?> p) {
        if (p == float.class) return (float) v;
        if (p == double.class) return (double) v;
        if (p == long.class) return (long) v;
        if (p == short.class) return (short) v;
        if (p == byte.class) return (byte) v;
        return v;
    }

    private static boolean isInt(Class<?> p) {
        return p == int.class || p == Integer.class;
    }

    private static boolean isFloat(Class<?> p) {
        return p == float.class || p == Float.class || p == double.class || p == Double.class;
    }

    private static String sig(java.lang.reflect.Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

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
    private void togglePause() {
        if (playing) {
            resumeFromTick = Math.max(0, playheadTick);   // ① 先存进度
            paused = true;
            playing = false;
            if (playThread != null) { playThread.interrupt(); playThread = null; }
            pauseButton.setMessage(Component.literal("继播"));
        } else if (paused) {
            startPlayback(resumeFromTick);                 // ② 从存的进度续播
        } else {
            startPlayback(0);
        }
    }
    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { stopPlayback(); super.onClose(); }
}