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
            if (playing) stopPlayback(); else startPlayback();
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
            Minecraft.getInstance().execute(() ->
                    playButton.setMessage(Component.translatable("disc_jockey.editor.play")));
        }, "DJ-NBSEditor-Play");
        playThread.start();
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

    private static String instName(int i) {
        initInstKeys();
        String key = (i >= 0 && i < instKeys.length) ? instKeys[i] : null;
        if (key == null) return INST_NAMES[i];
        String t = Component.translatable(key).getString();
        return (t == null || t.isEmpty() || t.equals(key)) ? INST_NAMES[i] : t;
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

    public static void showMsg(String text) { showMsg(Component.literal(text)); }

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
            int col;
            if (sel)                 col = 0xFFFFEB3B;
            else if (isBlackKey(n.noteId)) col = 0xFF4FC3F7;
            else                     col = 0xFF8BC34A;
            c.fill(x, y, x + Math.max(2, cw - 1), y + Math.max(2, ch - 1), col);
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