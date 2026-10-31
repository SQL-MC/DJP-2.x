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
import net.minecraft.resources.Identifier;
import semmiedev.disc_jockey.util.BlitCompat;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.MidiToNbsImporter;
import semmiedev.disc_jockey.NbsToMidiExporter;
import semmiedev.disc_jockey.Note;
import semmiedev.disc_jockey.NoteClamper;
import semmiedev.disc_jockey.Song;
import semmiedev.disc_jockey.SongLoader;

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

    private static final String[] INST_NAMES = {
            "Harp", "Bass", "BDrum", "Snare", "Click", "Guitar", "Flute", "Bell",
            "Chime", "Xylo", "IronXyl", "CowBell", "Didger", "Bit", "Banjo", "Pling"
    };

    private static final String[] INST_SUFFIX = {
            "harp", "bass", "basedrum", "snare", "hat", "guitar", "flute", "bell",
            "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo",
            "bit", "banjo", "pling"
    };

    /**
     * 内置中文名（lang 文件没配、或当前语言不是 zh_cn 时的兜底）。
     * 保证乐器按钮 / 状态栏始终显示中文，不再依赖 MC 原版语言包。
     */
    private static final String[] INST_CN = {
            "竖琴",       // 0  Harp
            "贝斯",       // 1  Bass
            "底鼓",       // 2  BDrum      basedrum
            "军鼓",       // 3  Snare      snare
            "踩镲",       // 4  Click      hat
            "吉他",       // 5  Guitar
            "长笛",       // 6  Flute
            "钟",         // 7  Bell
            "风铃",       // 8  Chime
            "木琴",       // 9  Xylo       xylophone
            "铁木琴",     // 10 IronXyl    iron_xylophone
            "牛铃",       // 11 CowBell    cow_bell
            "迪吉里杜管", // 12 Didger     didgeridoo
            "电子音",     // 13 Bit        bit
            "班卓琴",     // 14 Banjo
            "拨弦"        // 15 Pling
    };

    /**
     * 每种音色对应的图标贴图（项目自带，assets/disc_jockey/textures/item/）。
     * 16 种各有专属图，索引与 INST_NAMES / Note.NBS_V4 一致。
     * ★ Didger(12) = 南瓜、Bit(13) = 绿宝石块（MC 原始对应关系）。
     */
    private static final String[] INST_TEX = {
            "noteblock",       // 0  Harp      音符盒
            "oak_planks",      // 1  Bass      橡木木板
            "stone",           // 2  BDrum     石头
            "sand",            // 3  Snare     沙子
            "glass",           // 4  Click     玻璃
            "white_wool",      // 5  Guitar    白色羊毛
            "clay",            // 6  Flute     黏土
            "gold_block",      // 7  Bell      金块
            "packed_ice",      // 8  Chime     浮冰
            "bone_block_top",  // 9  Xylo      骨块
            "iron_block",      // 10 IronXyl   铁块
            "soul_sand",       // 11 CowBell   灵魂沙
            "pumpkin_side",    // 12 Didger    南瓜
            "emerald_block",   // 13 Bit       绿宝石块
            "hay_block_side",  // 14 Banjo     干草捆
            "glowstone"        // 15 Pling     萤石
    };

    /**
     * 每种音色的色块颜色（取发声方块本色，与 INST_TEX 一一对应）。
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
            0xFF7A5C3E, // 11 CowBell   灵魂沙
            0xFFE08A2E, // 12 Didger    南瓜
            0xFF2ECC71, // 13 Bit       绿宝石块
            0xFFC8A34A, // 14 Banjo     干草捆
            0xFFEAD36B  // 15 Pling     萤石
    };

    /** 格子达到这个尺寸才叠加图标（16x16 贴图的最小需求） */
    /**
     * 格子达到这个尺寸才叠加图标。
     * ★ 改回 8：图标绘制已换成【图集 sprite】路线，
     *   方块图集自带 4 级 mipmap（日志 2048x2048x4），
     *   缩小到 9x8 不会走样，不会再出黑白黄斑。
     */
    private static final int ICON_MIN_CELL = 8;

    /**
     * 音色图标总开关。
     * true = 贴图存在就画图标；贴图不存在则自动退回纯色（不会出现黑紫缺失贴图）。
     *
     * ★ 已恢复 true（不删减功能）：图标绘制统一走 BlitCompat.blitFull（见 blitIcon）。
     *   之前用 blit() 在归一化路径下会把 UV 宽度算成 w/tw（9x9 格子 / 16x16 贴图 ≈ 0.56），
     *   只采样贴图左上角再拉伸，音符密集时整片糊成黑白黄斑 —— 那是「裁切放大」不是缺失贴图。
     *   blitFull 固定采样整张（UV 0..1）、目标尺寸独立，是真正的缩放，因此斑块不再出现。
     *   blitFull 若在本版本真的画不了（如 Texture view 未就绪），会返回 false，
     *   调用前已 fill 过底色块，自然退回 16 色纯色，不会出现黑紫棋盘。
     *   ★ 兜底开关：若仍出问题，把这里改回 false 即可，一个布尔值的事。
     */
    private static final boolean ICONS_ENABLED = true;

    /**
     * 兜底用的 MC 原版方块贴图（assets/minecraft/textures/block/）。
     * 索引与 INST_TEX 一致；项目里叫 noteblock，MC 里叫 note_block。
     */
    private static final String[] INST_TEX_VANILLA = {
            "note_block",      // 0  Harp
            "oak_planks",      // 1  Bass
            "stone",           // 2  BDrum
            "sand",            // 3  Snare
            "glass",           // 4  Click
            "white_wool",      // 5  Guitar
            "clay",            // 6  Flute
            "gold_block",      // 7  Bell
            "packed_ice",      // 8  Chime
            "bone_block_top",  // 9  Xylo
            "iron_block",      // 10 IronXyl
            "soul_sand",       // 11 CowBell
            "pumpkin_side",    // 12 Didger    南瓜
            "emerald_block",   // 13 Bit       绿宝石块
            "hay_block_side",  // 14 Banjo
            "glowstone"        // 15 Pling
    };

    /** 兜底也失败过，不再重复探测 */
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
    private Button pauseButton;
    private volatile boolean paused = false;

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

        // ★ 暂停 / 继播：暂停保留进度，继播时从原 tick 续播
        pauseButton = Button.builder(Component.literal("\u6682\u505c"), b -> togglePause())
                .pos(bx, y2).size(30, 18).build();
        addRenderableWidget(pauseButton);
        bx += 32;

        preloadInstTextures();
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

    private int cellH() { return 5 + zoom * 2; }
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
            zoom = Math.max(0, Math.min(4, zoom + dir));
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

    private void startPlayback() { startPlayback(0); }

    private void startPlayback(int startTick) {
        stopPlayback();
        if (notes.isEmpty()) return;
        final List<EditableNote> snapshot = new ArrayList<>(notes);
        final int end = maxTick() + 2;
        final long tickMs = Math.max(1, 100000L / Math.max(1, song.tempo));
        final int from = Math.max(0, startTick);

        paused = false;
        playing = true;
        playButton.setMessage(Component.translatable("disc_jockey.editor.stop"));
        if (pauseButton != null) pauseButton.setMessage(Component.literal("\u6682\u505c"));

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
            if (paused) return;
            playing = false;
            playheadTick = -1;
            Minecraft.getInstance().execute(() -> {
                playButton.setMessage(Component.translatable("disc_jockey.editor.play"));
                if (pauseButton != null) pauseButton.setMessage(Component.literal("\u6682\u505c"));
            });
        }, "DJ-NBSEditor-Play");
        playThread.start();
    }

    private void stopPlayback() {
        paused = false;
        playing = false;
        playheadTick = -1;
        if (playThread != null) { playThread.interrupt(); playThread = null; }
        if (playButton != null) playButton.setMessage(Component.translatable("disc_jockey.editor.play"));
        if (pauseButton != null) pauseButton.setMessage(Component.literal("\u6682\u505c"));
    }

    /** 暂停 / 继播：与顶部「暂停」按钮、空格键共用一套逻辑 */
    private void togglePause() {
        if (playing) {
            paused = true;
            playing = false;
            if (playThread != null) { playThread.interrupt(); playThread = null; }
            if (playButton != null) playButton.setMessage(Component.translatable("disc_jockey.editor.play"));
            if (pauseButton != null) pauseButton.setMessage(Component.literal("\u7ee7\u64ad"));
            System.out.println("[DJ-EDIT] PAUSE @ tick " + playheadTick);
        } else {
            int at = (playheadTick > 0) ? playheadTick : 0;
            System.out.println("[DJ-EDIT] RESUME @ tick " + at);
            startPlayback(at);
        }
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

    private static String instName(int i) {
        if (i < 0 || i >= INST_SUFFIX.length) return INST_NAMES[0];
        String key = "disc_jockey.editor.instrument." + INST_SUFFIX[i];
        String t = Component.translatable(key).getString();
        if (t != null && !t.isEmpty() && !t.equals(key)) return t;
        return (i < INST_CN.length) ? INST_CN[i] : INST_NAMES[i];
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor c) {
        int mx = c.mouseX();
        int my = c.mouseY();
        float delta = c.partialTicks();

        mouseX = mx;
        mouseY = my;

        // ★ 框选拖拽时，靠近边缘自动滚动（必须在计算 hover / drag 之前）
        autoScrollEdge(mx, my);

        super.extractRenderState(c);

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
            // ★ 按实际音色着色（原来只分黑键/白键的蓝/绿两色）
            int col = sel ? 0xFFFFEB3B : instColor(n.instrument);
            int bw = Math.max(2, cw - 1);
            int bh = Math.max(2, ch - 1);
            c.fill(x, y, x + bw, y + bh, col);
            if (!sel && bw >= ICON_MIN_CELL && bh >= ICON_MIN_CELL) {
                if (drawNoteIcon(c, n.instrument, x, y, bw, bh) && !iconDrawnLogged) {
                    iconDrawnLogged = true;
                    LOGGER.info("[Editor] \u2713 \u5df2\u7ed8\u5236\u97f3\u8272\u56fe\u6807\uff1a\u5c3a\u5bf8={}x{}\uff08\u683c\u5b50={}x{}\uff09",
                            bw, bh, cw, ch);
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

    private static int instColor(int instrument) {
        if (instrument >= 0 && instrument < INST_COLOR.length) return INST_COLOR[instrument];
        return INST_COLOR[0];
    }

    // ==================== 贴图存在性校验 ====================
    //
    // 之前出现「黑紫棋盘」= MC 的 missing-texture，说明 blit 调用成功但
    // 资源包里找不到该 Identifier 对应的 png。这里在首次绘制前用
    // ResourceManager 实际查一次；不存在就不画（保持纯色）。

    /** 每个音色的贴图可用状态：null=未查过，TRUE=可用，FALSE=缺失 */
    private static final Boolean[] instTexOk = new Boolean[INST_TEX.length];

    private static volatile java.lang.reflect.Method rmGetter;
    private static volatile java.lang.reflect.Method resGetter;
    private static volatile boolean rmTried = false;

    /**
     * 探针是否可信。判据：MC 原版方块贴图（minecraft:textures/block/note_block、stone）必定存在，
     * 若连它们都报「缺失」，说明是资源管理器反射取错了，
     * 此时不再用探针拦截绘制，直接画 —— 避免把本来能显示的图标误判成缺失。
     */
    private static volatile boolean probeTrusted = true;

    /** 运行时确认 assets/disc_jockey/textures/item/<名>.png 是否真的在资源包里。 */
    private static boolean resourceExists(int instrument, Identifier id) {
        // 探针不可信时不再拦截，直接画，避免误判成缺失
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
                        ok = ((java.util.Optional<?>) r).isPresent();
                    } else {
                        ok = (r != null);
                    }
                }
            }
        } catch (Throwable ignored) {
            ok = true;
        }
        if (instrument >= 0 && instrument < instTexOk.length) {
            instTexOk[instrument] = ok;
            if (!ok) {
                LOGGER.warn("[Editor] 贴图缺失（退回纯色）：{} "
                                + "→ assets/disc_jockey/textures/item/{}.png 不在资源包里",
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
            LOGGER.info("[Editor] 资源探针：ResourceManager={} | getResource={}",
                    rm.getClass().getName(), (resGetter == null ? "未找到" : resGetter.toString()));
            boolean v1 = vanillaProbe("note_block");
            boolean v2 = vanillaProbe("stone");
            if (!v1 && !v2) {
                probeTrusted = false;
                LOGGER.warn("[Editor] 连 MC 原版贴图都查不到，探针不可信，改为直接绘制（不再拦截）");
            }
            dumpInstTexResolved();
        } catch (Throwable ignored) {}
    }

    /** 打出 16 个音色最终解析到的贴图，方便核对有没有串台 */
    private static void dumpInstTexResolved() {
        StringBuilder sb = new StringBuilder("[DJ-EDIT] 音色图标实际解析：");
        for (int i = 0; i < INST_TEX.length; i++) {
            Identifier id = buildId("disc_jockey", "textures/item/" + INST_TEX[i] + ".png");
            sb.append("\n  [").append(i).append("] ").append(INST_NAMES[i])
              .append(" -> ").append(id == null ? "(Identifier 失败)" : id.toString());
        }
        System.out.println(sb);
    }

    /** 反射造 disc_jockey:<path> 的 Identifier（兼容 26.3 / 26.4 工厂方法名差异） */
    private static Identifier djId(String path) {
        return buildId("disc_jockey", path);
    }

    /** 探 MC 原版贴图是否存在（原版资源必定存在，用来校验探针本身是否可信） */
    private static boolean vanillaProbe(String texName) {
        try {
            Identifier id = buildId("minecraft", "textures/block/" + texName + ".png");
            if (id == null) {
                LOGGER.info("[Editor] 原版探针 {} ：Identifier 创建失败", texName);
                return false;
            }
            Object mc = mcInstance();
            if (mc == null || rmGetter == null || resGetter == null) return false;
            Object r = resGetter.invoke(rmGetter.invoke(mc), id);
            boolean ok;
            String verdict;
            if (r instanceof java.util.Optional) {
                ok = ((java.util.Optional<?>) r).isPresent();
                verdict = ok ? "存在" : "缺失(Optional.empty)";
            } else if (r == null) {
                ok = false;
                verdict = "缺失(null)";
            } else {
                ok = true;
                verdict = "存在(" + r.getClass().getSimpleName() + ")";
            }
            LOGGER.info("[Editor] 原版探针 {} → {} ：{}", texName, id, verdict);
            return ok;
        } catch (Throwable t) {
            LOGGER.info("[Editor] 原版探针 {} 异常：{}", texName, t);
            return false;
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

    /** 按 namespace + path 反射造 Identifier：26.3 / 26.4 工厂方法名不同，三级兜底 */
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
     * 取音色图标，两级选择：
     *   ① mod 自带贴图 assets/disc_jockey/textures/item/<name>.png
     *   ② 兜底 MC 原版方块贴图 assets/minecraft/textures/block/<name>.png
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
                LOGGER.info("[Editor] mod 自带贴图不可用，已切换为 MC 原版方块贴图（minecraft:block/...）");
            }
            return vid;
        }

        instTexDead[instrument] = true;
        instTexTried = true;
        LOGGER.warn("[Editor] 图标贴图均不可用（instrument={}），退回纯色", instrument);
        return null;
    }

    // ==================== 贴图注册 ====================
    //
    // 26.x 的贴图 Identifier 是「全路径」形式：命名空间:textures/子目录/名.png
    //   证据：日志里 MC 自己打的图集名 minecraft:textures/atlas/blocks.png-atlas
    //
    // ★ 关键点：blit(Identifier) 要求贴图已完成 GPU 上传，而上传发生在
    //   TextureManager.tick()（每帧一次）。所以「注册完同一帧立刻 blit」必然抛
    //   IllegalStateException: Texture view does not exist ...
    //   对策：① 打开界面时就注册（init 里 preloadInstTextures）
    //        ② blit 失败不判死，下一帧继续重试（见 blitIcon）

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

    /** 打开界面时把 16 张贴图交给 TextureManager，尽早开始上传 */
    private static void preloadInstTextures() {
        for (int i = 0; i < INST_TEX.length; i++) {
            try {
                Identifier id = buildId("disc_jockey", "textures/item/" + INST_TEX[i] + ".png");
                if (id != null) ensureTexture(i, id);
            } catch (Throwable ignored) {}
        }
    }

    /** 让 TextureManager 真正加载这个贴图；失败也不影响后续 blit 尝试 */
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

            // ① 26.4 snapshot-6：registerAndLoad(id, TextureProvider2d) —— 真正把纹理传上 GPU。
            //    ★ 关键：registerForNextReload 语义是「下次资源重载时才注册」，本次会话【不加载】；
            //      SimpleTexture 类在 26.4 已被删除（javap 实测：找不到类）。
            //      两条老路都不加载 → getTexture 返回「缺失句柄」→ blit 画出黑紫棋盘，
            //      音符密集（上万）时整片糊成黑白黄斑 —— 这才是 26.4 GUI 烂掉的真因。
            try {
                Class<?> tp = Class.forName("net.minecraft.client.renderer.texture.TextureProvider2d");
                Object provider = tp.getConstructor().newInstance();
                for (java.lang.reflect.Method m : tm.getClass().getMethods()) {
                    if (m.getName().equals("registerAndLoad") && m.getParameterCount() == 2) {
                        m.invoke(tm, id, provider);
                        used = "registerAndLoad(TextureProvider2d)";
                        break;
                    }
                }
            } catch (Throwable ignored) {}

            // ② 26.3：register(id, new SimpleTexture(id))
            if (used == null) {
                for (java.lang.reflect.Method m : tm.getClass().getMethods()) {
                    if (m.getName().equals("register") && m.getParameterCount() == 2) {
                        Class<?> st = Class.forName("net.minecraft.client.renderer.texture.SimpleTexture");
                        Object tex = st.getConstructor(Identifier.class).newInstance(id);
                        m.invoke(tm, id, tex);
                        used = "register(SimpleTexture)";
                        break;
                    }
                }
            }

            // ③ 最后兜底（26.4 下不会立即生效，仅保证不抛异常）
            if (used == null) {
                for (java.lang.reflect.Method m : tm.getClass().getMethods()) {
                    if (m.getName().equals("registerForNextReload") && m.getParameterCount() == 1) {
                        m.invoke(tm, id);
                        used = "registerForNextReload(不立即加载)";
                        break;
                    }
                }
            }

            if (used != null && !texRegLogged) {
                texRegLogged = true;
                LOGGER.info("[Editor] \u8d34\u56fe\u5df2\u52a0\u8f7d\u5230 TextureManager\uff08{}\uff09\uff1a{}", used, id);
            }
        } catch (Throwable t) {
            if (!texRegLogged) {
                texRegLogged = true;
                LOGGER.info("[Editor] \u8d34\u56fe\u52a0\u8f7d\u5931\u8d25\uff08\u4ecd\u4f1a\u5c1d\u8bd5\u7ed8\u5236\uff09\uff1a{}", t);
            }
        }
    }

    /**
     * 贴图句柄是否已真正可用（26.4：TextureHandle.textureView() 非空）。
     *   true  = 可以画
     *   false = 还没传上 GPU，本次保持纯色，下一帧再试
     * ★ 这是防「黑紫棋盘 / 黑白黄斑」的最后一道闸：没就绪就绝不画。
     */
    private static boolean textureReady(Identifier id) {
        try {
            Object tm = textureManager();
            if (tm == null) return true;
            for (java.lang.reflect.Method m : tm.getClass().getMethods()) {
                if (!m.getName().equals("getTexture") || m.getParameterCount() != 1) continue;
                Object h = m.invoke(tm, id);
                if (h == null) return false;
                java.lang.reflect.Method tv = null;
                for (java.lang.reflect.Method mm : h.getClass().getMethods()) {
                    if (mm.getName().equals("textureView") && mm.getParameterCount() == 0) { tv = mm; break; }
                }
                if (tv == null) return true;          // 26.3 没有 textureView()，按就绪处理
                return tv.invoke(h) != null;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    // ==================== 图集 sprite 图标（26.4 / Vulkan 专用路径） ====================
    //
    // ★ 26.4 GUI 烂掉的真因（javap 实测确认）：
    //   GuiGraphicsExtractor 上【带管线】的 blit 全是「像素 UV」签名，
    //   没有 (管线, 贴图, x, y, w, h, u0, v0, uW, vH) 这种归一化重载，
    //   所以只能退化到【不带管线】的
    //     blit(Identifier, int, int, int, int, float, float, float, float)
    //   26.4 是 Vulkan 后端，不带管线 = 没绑定渲染状态 → 画出未初始化纹理，
    //   音符密集（上万）时整片糊成黑白黄斑。跟 UV 算错无关，跟后端也无关。
    //
    //   正解：MC 官方 sprite 路径（javap 逐条实测）
    //     · 26.4 管线类已从 RenderPipelines 改名 GuiPipeline，常量 GuiPipeline.TEXTURED
    //     · GuiGraphicsExtractor 自带 getSprite(SpriteId) —— 26.3 / 26.4 都有
    //     · SpriteId 是 record：(atlasLocation, texture)，双参构造
    //     · blitSprite(GuiPipeline, TextureAtlasSprite, x, y, w, h) 一定带管线
    //     · 方块图集启动即上传完毕（2048x2048x4 = 带 4 级 mipmap），不存在纹理未就绪
    //   sprite 拿不到就返回 false → 保持 16 色纯色，绝不画棋盘。

    /** 16 种音色对应的方块图集 sprite 名（与 INST_TEX 一一对应） */
    private static final String[] INST_SPRITE = {
            "note_block", "oak_planks", "stone", "sand",
            "glass", "white_wool", "clay", "gold_block",
            "packed_ice", "bone_block_top", "iron_block", "soul_sand",
            "pumpkin_side", "emerald_block", "hay_block_side", "glowstone"
    };

    /** 0=未探测 1=就绪 2=永久不可用 */
    private static volatile int spriteState = 0;
    /** 0=extractor.getSprite(SpriteId)  1=AtlasManager.getSprite(Identifier) */
    private static volatile int spriteMode = -1;

    private static volatile java.lang.reflect.Method exGetSprite;
    private static volatile java.lang.reflect.Constructor<?> spriteIdCtor;
    private static volatile java.lang.reflect.Method atlasGetSprite;
    private static volatile Object atlasBlocksId;
    private static volatile Object spriteAtlas;
    private static volatile Object spriteMissing;
    private static volatile java.lang.reflect.Method spriteBlit;
    private static volatile Object spritePipe;
    private static final Object[] spriteCache = new Object[INST_SPRITE.length];
    private static volatile boolean spriteDrawnLogged = false;
    private static volatile boolean spriteWarnLogged = false;

    private static boolean spriteFail(String why) {
        spriteState = 2;
        if (!spriteWarnLogged) {
            spriteWarnLogged = true;
            LOGGER.warn("[Editor] 图集 sprite 路线不可用：{}", why);
        }
        return false;
    }

    /** 26.4 = GuiPipeline.TEXTURED；26.3 = RenderPipelines.GUI_TEXTURED */
    private static Object resolveGuiPipeline(String[] out) {
        String[][] cands = {
                {"net.minecraft.client.gui.GuiPipeline", "TEXTURED"},
                {"net.minecraft.client.gui.GuiPipeline", "TEXTURED_PREMULTIPLIED_ALPHA"},
                {"net.minecraft.client.gui.GuiPipeline", "OPAQUE_TEXTURED_BACKGROUND"},
                {"net.minecraft.client.renderer.RenderPipelines", "GUI_TEXTURED"},
                {"net.minecraft.client.renderer.RenderPipelines", "GUI_TEXTURED_PREMULTIPLIED_ALPHA"}
        };
        for (String[] k : cands) {
            try {
                java.lang.reflect.Field f = Class.forName(k[0]).getField(k[1]);
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) { out[0] = k[0] + "." + k[1]; return v; }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object newSpriteId(Object texId) {
        if (spriteIdCtor == null || atlasBlocksId == null || texId == null) return null;
        try { return spriteIdCtor.newInstance(atlasBlocksId, texId); } catch (Throwable t) { return null; }
    }

    /** 按当前模式取一个 sprite；两种命名（block/xxx 与 xxx）都试，命中缺失 sprite 视为失败 */
    private static Object fetchSprite(Object c, int inst) {
        String name = INST_SPRITE[inst];
        String[] paths = {"block/" + name, name};
        try {
            if (spriteMode == 0 && exGetSprite != null && c != null) {
                for (String pth : paths) {
                    Object sid = newSpriteId(buildId("minecraft", pth));
                    if (sid == null) continue;
                    Object sp = exGetSprite.invoke(c, sid);
                    if (sp != null && !(spriteMissing != null && sp == spriteMissing)) return sp;
                }
            } else if (spriteMode == 1 && atlasGetSprite != null && spriteAtlas != null) {
                for (String pth : paths) {
                    Object tex = buildId("minecraft", pth);
                    if (tex == null) continue;
                    Object sp = atlasGetSprite.invoke(spriteAtlas, tex);
                    if (sp != null && !(spriteMissing != null && sp == spriteMissing)) return sp;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 初始化 sprite 路线（每步都记日志，便于定位）；失败则永久禁用 */
    private static boolean spriteEnsure(Object c) {
        if (spriteState != 0) return spriteState == 1;
        try {
            String[] pipeName = new String[1];
            Object pipe = resolveGuiPipeline(pipeName);
            if (pipe == null) return spriteFail("找不到 GUI 纹理管线常量（GuiPipeline / RenderPipelines）");
            spritePipe = pipe;

            Class<?> spCls = Class.forName("net.minecraft.client.renderer.texture.TextureAtlasSprite");
            for (java.lang.reflect.Method m : GuiGraphicsExtractor.class.getMethods()) {
                if (!m.getName().equals("blitSprite")) continue;
                Class<?>[] pp = m.getParameterTypes();
                if (pp.length != 6) continue;
                if (pp[1] != spCls) continue;
                if (pp[2] != int.class || pp[3] != int.class || pp[4] != int.class || pp[5] != int.class) continue;
                if (!pp[0].isInstance(pipe)) continue;
                spriteBlit = m;
                break;
            }
            if (spriteBlit == null) return spriteFail("找不到 blitSprite(管线, sprite, x, y, w, h)");

            Class<?> atlasCls = Class.forName("net.minecraft.client.renderer.texture.TextureAtlas");
            atlasBlocksId = atlasCls.getField("LOCATION_BLOCKS").get(null);
            if (atlasBlocksId == null) return spriteFail("TextureAtlas.LOCATION_BLOCKS 为空");

            try {
                Class<?> sc = Class.forName("net.minecraft.client.resources.model.sprite.SpriteId");
                spriteIdCtor = sc.getConstructor(Identifier.class, Identifier.class);
            } catch (Throwable t) {
                spriteIdCtor = null;
                LOGGER.info("[Editor] sprite 探测：SpriteId 双参构造不可用（{}）", t.toString());
            }

            // 模式 0：extractor.getSprite(SpriteId) —— 官方一步到位路径
            if (spriteIdCtor != null) {
                for (java.lang.reflect.Method m : GuiGraphicsExtractor.class.getMethods()) {
                    if (m.getName().equals("getSprite") && m.getParameterCount() == 1) { exGetSprite = m; break; }
                }
            }
            if (exGetSprite != null) {
                spriteMode = 0;
                Object probe = fetchSprite(c, 0);
                if (probe != null) {
                    spriteCache[0] = probe;
                    spriteState = 1;
                    LOGGER.info("[Editor] ✓ 图集 sprite 路线就绪：管线={} | 取法=extractor.getSprite(SpriteId)", pipeName[0]);
                    return true;
                }
                LOGGER.info("[Editor] sprite 探测：extractor 路径取不到 note_block，改用 AtlasManager");
            } else {
                LOGGER.info("[Editor] sprite 探测：extractor 无 getSprite(SpriteId)，改用 AtlasManager");
            }

            // 模式 1：Minecraft.getAtlasManager() → TextureAtlas.getSprite(Identifier)
            Object mc = mcInstance();
            Object am = null;
            if (mc != null) {
                for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
                    if (m.getName().equals("getAtlasManager") && m.getParameterCount() == 0) { am = m.invoke(mc); break; }
                }
            }
            if (am != null) {
                Object atlas = null;
                for (java.lang.reflect.Method m : am.getClass().getMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    if (m.getName().equals("getAtlasOrThrow") || m.getName().equals("getAtlas")) {
                        atlas = m.invoke(am, atlasBlocksId);
                        if (atlas != null) break;
                    }
                }
                if (atlas != null) {
                    java.lang.reflect.Method gs = null, ms = null;
                    for (java.lang.reflect.Method m : atlas.getClass().getMethods()) {
                        if (gs == null && m.getName().equals("getSprite") && m.getParameterCount() == 1) gs = m;
                        if (ms == null && m.getName().equals("missingSprite") && m.getParameterCount() == 0) ms = m;
                    }
                    if (gs != null) {
                        spriteAtlas = atlas;
                        atlasGetSprite = gs;
                        if (ms != null) { try { spriteMissing = ms.invoke(atlas); } catch (Throwable ignored) {} }
                        spriteMode = 1;
                        Object probe = fetchSprite(c, 0);
                        if (probe != null) {
                            spriteCache[0] = probe;
                            spriteState = 1;
                            LOGGER.info("[Editor] ✓ 图集 sprite 路线就绪：管线={} | 取法=AtlasManager.getSprite", pipeName[0]);
                            return true;
                        }
                        return spriteFail("图集内没有 note_block sprite（block/note_block 与 note_block 都不命中）");
                    }
                }
            }
            return spriteFail("拿不到方块图集（getAtlasManager / getAtlasOrThrow 均不可用）");
        } catch (Throwable t) {
            return spriteFail(String.valueOf(t));
        }
    }

    private static boolean drawSpriteIcon(GuiGraphicsExtractor c, int instrument, int x, int y, int w, int h) {
        if (spriteState != 1 || c == null) return false;
        try {
            Object sp = spriteCache[instrument];
            if (sp == null) {
                sp = fetchSprite(c, instrument);
                if (sp == null) return false;
                spriteCache[instrument] = sp;
            }
            spriteBlit.invoke(c, spritePipe, sp, x, y, w, h);
            if (!spriteDrawnLogged) {
                spriteDrawnLogged = true;
                LOGGER.info("[Editor] ✓ 图集 sprite 图标已绘制：{}", INST_SPRITE[instrument]);
            }
            return true;
        } catch (Throwable t) {
            if (!spriteWarnLogged) {
                spriteWarnLogged = true;
                LOGGER.warn("[Editor] sprite 图标绘制失败：{}", t.toString());
            }
            spriteState = 2;   // ★ 任何版本：sprite 画失败就永久禁用，退回纯色，绝不污染渲染
            return false;
        }
    }

    /**
     * true = 26.4 / Vulkan 环境（管线类已从 RenderPipelines 改名为 GuiPipeline）。
     * ★ 在这种情况下【绝不允许】退回无管线的 png blit —— 那正是整帧被污染、
     *   GUI 烂掉的直接来源（实验已证实：不调 blit 就一切正常）。
     */
    private static boolean isVulkan264() {
        try {
            Class.forName("net.minecraft.client.gui.GuiPipeline");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean drawNoteIcon(GuiGraphicsExtractor c, int instrument, int x, int y, int w, int h) {
        if (!ICONS_ENABLED || c == null) return false;
        boolean v264 = isVulkan264();
        if (spriteEnsure(c)) return drawSpriteIcon(c, instrument, x, y, w, h);

        Identifier tex = instTexture(instrument);
        if (tex == null || !resourceExists(instrument, tex) || !textureReady(tex)) return false;

        // ★ 26.4 / Vulkan：只能走【带管线】的 png blit。
        //   无管线 blit 不绑定渲染状态 → 未初始化纹理 → 整帧被污染（实验已证实）。
        if (v264) {
            Object pipe = (spritePipe != null) ? spritePipe : resolveGuiPipeline(new String[1]);
            if (pipe != null && blitIconPiped(c, pipe, tex, x, y, w, h)) return true;
            return false;   // 宁可纯色，绝不退回无管线路径
        }
        return blitIcon(c, tex, x, y, w, h);   // 26.3 原行为不变
    }

    // ==================== 图标绘制 ====================

    private static volatile java.lang.reflect.Method iconBlit;
    private static volatile int iconBlitPlan = -1;
    private static volatile Object iconBlitPipe;
    private static volatile boolean iconDrawnLogged = false;
    /**
     * ★ 选择尝试次数。26.x 贴图首帧尚未完成 GPU 上传，blit 会抛
     *   "Texture view does not exist"，等 TextureManager.tick() 跑过一两帧就好了。
     *   所以这里【不能一次判死】，必须允许多帧重试 —— 之前 iconBlitTried
     *   一次性置位导致永久退回纯色，就是图标出不来的直接原因。
     */
    private static volatile int iconBlitAttempts = 0;
    private static volatile boolean iconBlitWarned = false;

    private static Throwable realCause(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException) {
            Throwable target = ((java.lang.reflect.InvocationTargetException) t).getTargetException();
            return target != null ? target : t;
        }
        return t;
    }

    /**
     * 把 16x16 图标铺满 w x h；失败返回 false，调用方保持纯色。
     *
     * ★ OpenGL / Vulkan 兼容：不再自己猜 blit 签名，统一走 BlitCompat。
     *   后端（OpenGL=26.3 / Vulkan=26.4）对本方法透明 —— MC 的 GuiGraphics
     *   抽象层屏蔽了差异。真正要适配的是【API 签名】：
     *     · 有 RenderPipelines（26.3）→ 10 参带管线，u/v 是像素
     *     · 无 RenderPipelines（26.4）→ 9 参无管线，u/v 是 0..1 归一化
     *   BlitCompat 已按这两条路径分别排好实参，这里直接复用，
     *   不再维护第二套反射探测（两套逻辑各自踩坑正是之前画不出的根因）。
     *
     * BlitCompat 内部已做降级与日志；它失败时什么都不画，
     * 而调用前已经 fill 过底色块，所以自然退回纯色，不会出现黑紫棋盘。
     */
    private static boolean blitIcon(GuiGraphicsExtractor c, Identifier tex, int x, int y, int w, int h) {
        if (c == null || tex == null) return false;
        // ★ 用 blitFull 而不是 blit：
        //   blit 的 w/h 同时当「源尺寸」和「目标尺寸」，归一化路径下 UV 宽度会算成 w/tw
        //   （9x9 格子 / 16x16 贴图 = 0.56），只采样左上角再拉伸 → 密集时糊成斑块。
        //   blitFull 固定采样整张（UV 0..1），目标尺寸独立 = 真正的缩放。
        return BlitCompat.blitFull(c, tex, x, y, w, h);
    }

    private static volatile java.lang.reflect.Method pipedBlit = null;
    private static volatile boolean pipedBlitTried = false;
    private static volatile boolean pipedDrawnLogged = false;
    private static volatile boolean pipedWarnLogged = false;

    /**
     * 带管线的 png blit —— 26.4 下唯一安全的 png 路径。
     * 26.4 实测签名：blit(GuiPipeline, Identifier, int x, int y, float u, float v, int w, int h, int tw, int th)
     * 即「像素 UV」10 参；只要第一参是管线类型就一定会绑定渲染状态，不会污染整帧。
     */
    private static boolean blitIconPiped(GuiGraphicsExtractor c, Object pipe, Identifier tex, int x, int y, int w, int h) {
        if (c == null || pipe == null || tex == null) return false;
        if (!ensurePipedBlit(pipe)) return false;
        try {
            Object[] args = fillArgs(pipedBlit, 0, pipe, tex, x, y, w, h);
            for (Object a : args) { if (a == null) return false; }   // 形参没填满就别调，避免 NPE
            pipedBlit.invoke(c, args);
            if (!pipedDrawnLogged) {
                pipedDrawnLogged = true;
                LOGGER.info("[Editor] \u2713 \u5e26\u7ba1\u7ebf png \u56fe\u6807\u5df2\u7ed8\u5236\uff1a{}", tex);
            }
            return true;
        } catch (Throwable t) {
            if (!pipedWarnLogged) {
                pipedWarnLogged = true;
                LOGGER.warn("[Editor] \u5e26\u7ba1\u7ebf png blit \u5931\u8d25\uff1a{}", t.toString());
            }
            return false;
        }
    }

    /** 只认【第一参是管线 + 含 Identifier + 恰好 10 参】的像素 UV 重载，避免参数错位 */
    private static boolean ensurePipedBlit(Object pipe) {
        if (pipedBlitTried) return pipedBlit != null;
        pipedBlitTried = true;
        for (java.lang.reflect.Method m : GuiGraphicsExtractor.class.getMethods()) {
            if (!m.getName().equals("blit")) continue;
            Class<?>[] pp = m.getParameterTypes();
            if (pp.length != 10) continue;
            if (!pp[0].isInstance(pipe)) continue;
            boolean hasId = false;
            for (Class<?> pc : pp) {
                if (Identifier.class.isAssignableFrom(pc) || pc.isAssignableFrom(Identifier.class)) { hasId = true; break; }
            }
            if (!hasId) continue;
            pipedBlit = m;
            LOGGER.info("[Editor] \u2713 \u5e26\u7ba1\u7ebf png blit \u7b7e\u540d\uff1ablit{}", sig(m));
            return true;
        }
        if (!pipedWarnLogged) {
            pipedWarnLogged = true;
            LOGGER.warn("[Editor] \u627e\u4e0d\u5230\u5e26\u7ba1\u7ebf\u7684 blit \u7b7e\u540d\uff08png \u56fe\u6807\u5c06\u4fdd\u6301\u7eaf\u8272\uff09");
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

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { stopPlayback(); super.onClose(); }
}