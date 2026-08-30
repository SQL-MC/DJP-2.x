package semmiedev.disc_jockey.gui.screen;

import semmiedev.disc_jockey.Main;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.util.RandomSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import javax.sound.midi.*;

/* ✅ DJP024xxx：补入 FileOutputStream，供 MidiRecorder.stopAndSave 显式写出 .mid（混淆安全） */
import java.io.FileOutputStream;
import java.io.IOException;

public class PianoKeyboardScreen extends Screen {

    private static final int NOTE_ID_A0 = -39;
    private static final int NOTE_ID_C8 = 48;

    /* ✅ DJP025xxx：Roll 全局坐标从 A-2 起算（A-2 = -39 - 2*12 = -63，MIDI 9） */
    private static final int NOTE_ID_A_MIN = -63;
    private static final int RW_SPAN = NOTE_ID_C8 - NOTE_ID_A_MIN + 1; // 48 - (-63) + 1 = 112

    // ★ PianoLib 的 key_0 = A0(noteId=-39)，转换偏移是 39
    private static final int PIANO_KEY_CENTER = 39;

    private static final int DISPLAY_BASE = 4;
    private static final int DISPLAY_MIN = 1;
    private static final int DISPLAY_MAX = 9;
    private static final int INTERNAL_MIN = -3;
    private static final int INTERNAL_MAX = 4;

    /* ===== 白键：A0(-39) ~ C8(48)，共52个，零点=A0 ===== */
    private static final int[] WHITE_NOTE_IDS = {
        -39,-37,
        -36,-34,-32,-31,-29,-27,-25,
        -24,-22,-20,-19,-17,-15,-13,
        -12,-10, -8, -7, -5, -3, -1,
          0,  2,  4,  5,  7,  9, 11,
         12, 14, 16, 17, 19, 21, 23,
         24, 26, 28, 29, 31, 33, 35,
         36, 38, 40, 41, 43, 45, 47,
         48
    };
    private static final int TOTAL_WHITE = WHITE_NOTE_IDS.length;
    private static final int C4_INDEX = 23; // WHITE_NOTE_IDS[23] = 0 = C4

    /* ===== 黑键：36个 ===== */
    private static final int[] BLACK_NOTE_IDS = {
        -38,
        -35,-33,-30,-28,-26,
        -23,-21,-18,-16,-14,
        -11, -9, -6, -4, -2,
          1,  3,  6,  8, 10,
         13, 15, 18, 20, 22,
         25, 27, 30, 32, 34,
         37, 39, 42, 44, 46
    };
    private static final int TOTAL_BLACK = BLACK_NOTE_IDS.length;

    /* ===== 黑键排在哪个白键之后（已验证100%正确）===== */
    private static final int[] BLACK_AFTER_WHITE = {
        0,
        2,3,5,6,7,
        9,10,12,13,14,
        16,17,19,20,21,
        23,24,26,27,28,
        30,31,33,34,35,
        37,38,40,41,42,
        44,45,47,48,49
    };

    /* ===== 物理键映射：offset = 相对C4的半音数（C大调把位）===== */
    private static final Map<Integer, Integer> KEY_MAP = new LinkedHashMap<>();
    static {
        KEY_MAP.put(GLFW.GLFW_KEY_A, 0);   // C4 (MIDI 60)
        KEY_MAP.put(GLFW.GLFW_KEY_W, 1);   // C#4
        KEY_MAP.put(GLFW.GLFW_KEY_S, 2);   // D4
        KEY_MAP.put(GLFW.GLFW_KEY_E, 3);   // D#4
        KEY_MAP.put(GLFW.GLFW_KEY_D, 4);   // E4
        KEY_MAP.put(GLFW.GLFW_KEY_F, 5);   // F4
        KEY_MAP.put(GLFW.GLFW_KEY_T, 6);   // F#4
        KEY_MAP.put(GLFW.GLFW_KEY_G, 7);   // G4
        KEY_MAP.put(GLFW.GLFW_KEY_Y, 8);
        KEY_MAP.put(GLFW.GLFW_KEY_H, 9);  // A4 (MIDI 69) 标准音A
        KEY_MAP.put(GLFW.GLFW_KEY_U, 10);  // A#4
        KEY_MAP.put(GLFW.GLFW_KEY_J, 11);  // B4
        KEY_MAP.put(GLFW.GLFW_KEY_K, 12);  // C5
        KEY_MAP.put(GLFW.GLFW_KEY_O, 13);  // C#5
        KEY_MAP.put(GLFW.GLFW_KEY_L, 14);  // D5
        KEY_MAP.put(GLFW.GLFW_KEY_P, 15);  // D#5
        KEY_MAP.put(GLFW.GLFW_KEY_SEMICOLON, 16); // E5
        KEY_MAP.put(GLFW.GLFW_KEY_APOSTROPHE, 17); // F5
    };

    /* ===== 布局常量 ===== */
    private static final int WKW = 48, WKH = 150;
    private static final int BKW = 30, BKH = 95;
    private static final int KX = 40;
    private static final int RY = 70, RH = 70;
    private static final int BY = RY + RH + 4;
    private static final int KY = BY + 26;
    private static final int SY = KY + WKH + 4;
    private static final int ICON = 22;
    private static final int TY = 8, TS = 22;

    /* ===== MIDI 录制布局常量（左上角）===== */
    private static final int REC_BTN_W = 60;
    private static final int REC_BTN_H = TS;
    private static final int REC_BTN_X = 10;
    private static final int REC_BTN_Y = TY;

    private final Screen parent;
    private final Map<Integer, Boolean> pressed = new HashMap<>();
    private int shift = 0;
    private final Set<Integer> active = new HashSet<>();
    private final Map<String, Boolean> keyState = new HashMap<>();
    private boolean wasMouseDown = false;

    private boolean broadcast = false;
    private Button sndBtn;
    private Button recBtn;
    private final Map<Integer, BlockPos> nbCache = new HashMap<>();
    private boolean nbReady = false;
    private int scanTick = 0;
    private long lastPkt = -1;
    private static final long PKT_INT = 50;

    private final List<Note> history = new ArrayList<>();
    private static final int HIST_MAX = 48;

    private static class Note {
        final int id, lx, iv;
        int age;
        Note(int n, int l, int i) { id = n; lx = l; iv = i; age = 0; }
    }

    /* ===== PianoLib 反射 ===== */
    private static volatile Boolean apiOK = null;
    private static volatile Class<?> apiCls = null;
    private static volatile Method apiPlay = null;
    private static volatile boolean libReady = false;
    private static volatile Method mcPlay = null;
    private static volatile Object mcSrc = null;
    private static final Map<Integer, Object> libCache = new HashMap<>();

    private static volatile Method gwM = null, ghM = null;
    private static volatile boolean refDone = false;

    /* ===== MIDI 录制器（单例）===== */
    private static final MidiRecorder MIDI_REC = new MidiRecorder();

    public PianoKeyboardScreen(Screen parent) {
        super(Component.literal("[Disc Jockey] Piano"));
        this.parent = parent;
    }

    /* ===== 显示/内部转换 ===== */
    private void setShift(int s) {
        int old = shift;
        shift = Mth.clamp(s, INTERNAL_MIN, INTERNAL_MAX);
        if (old != shift) {
            System.out.println("[DJ] shift " + old + " -> " + shift + " (display +" + getDisp() + ")");
        }
    }

    private int getDisp() { return shift + DISPLAY_BASE; }

    private void addDisp(int d) {
        setShift(shift + d);
    }

    private void setDisp(int d) {
        setShift(d - DISPLAY_BASE);
    }

    /* ===== 音名：允许负数八度（如 C-1, A-2）===== */
    private static String name(int midi) {
        String[] n = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
        int cls = ((midi % 12) + 12) % 12;
        int oct = (midi - cls) / 12 - 1;
        return n[cls] + oct;
    }

    private static String nameOfId(int id) {
        return name(id + 60);
    }

    private String labelForWhite(int i) {
        int nid = WHITE_NOTE_IDS[i] + shift * 12;
        return nameOfId(nid);
    }

    private String labelForBlack(int i) {
        int nid = BLACK_NOTE_IDS[i] + shift * 12;
        return nameOfId(nid);
    }

    private static boolean isPianoAPIAvail() {
        if (apiOK != null) return apiOK;
        try {
            apiCls = Class.forName("sqlmc.api.PianoAPI");
            apiOK = (boolean) apiCls.getMethod("isAvailable").invoke(null);
            apiPlay = apiCls.getMethod("playPianoKey", int.class, float.class);
            System.out.println("[DJ] PianoAPI available=" + apiOK);
        } catch (Throwable t) {
            apiOK = false;
            System.out.println("[DJ] PianoAPI not found");
        }
        return apiOK;
    }

    /**
     * ★ 26.2 兼容版 initLib
     */
    private static void initLib() {
        if (libReady) return;
        int n = 0;
        try {
            for (int k = 0; k <= 87; k++) {
                try {
                    Identifier loc = Identifier.fromNamespaceAndPath("pianolib", "key_" + k);
                    SoundEvent evt = SoundEvent.createVariableRangeEvent(loc);
                    int noteId = k - 39;
                    libCache.put(noteId, evt);
                    n++;
                } catch (Throwable ignored) {}
            }

            if (n == 0) {
                String[] altNames = {
                    "a0","a1","a2","a3","a4","a5","a6","a7",
                    "as0","as1","as2","as3","as4","as5","as6","as7",
                    "b0","b1","b2","b3","b4","b5","b6","b7",
                    "c1","c2","c3","c4","c5","c6","c7","c8",
                    "cs1","cs2","cs3","cs4","cs5","cs6","cs7",
                    "d1","d2","d3","d4","d5","d6","d7",
                    "ds1","ds2","ds3","ds4","ds5","ds6","ds7",
                    "e1","e2","e3","e4","e5","e6","e7",
                    "f1","f2","f3","f4","f5","f6","f7",
                    "fs1","fs2","fs3","fs4","fs5","fs6","fs7",
                    "g1","g2","g3","g4","g5","g6","g7",
                    "gs1","gs2","gs3","gs4","gs5","gs6","gs7"
                };
                for (int i = 0; i < altNames.length && i < 88; i++) {
                    try {
                        Identifier loc = Identifier.fromNamespaceAndPath("pianolib", altNames[i]);
                        SoundEvent evt = SoundEvent.createVariableRangeEvent(loc);
                        int noteId = i - 39;
                        libCache.put(noteId, evt);
                        n++;
                    } catch (Throwable ignored) {}
                }
            }

            if (n == 0) {
                try {
                    Object reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                        .getField("SOUND_EVENT").get(null);

                    Method getHolder = null;
                    for (Method m : reg.getClass().getMethods()) {
                        if (m.getName().equals("getHolder") && m.getParameterCount() == 1) {
                            getHolder = m; break;
                        }
                    }
                    Method getOptional = null;
                    for (Method m : reg.getClass().getMethods()) {
                        if (m.getName().equals("get") && m.getParameterCount() == 1
                            && m.getReturnType().getSimpleName().contains("Optional")) {
                            getOptional = m; break;
                        }
                    }

                    for (int k = 0; k <= 87; k++) {
                        try {
                            Identifier loc = Identifier.fromNamespaceAndPath("pianolib", "key_" + k);
                            Object holder = null;
                            if (getHolder != null) {
                                holder = getHolder.invoke(reg, loc);
                            } else if (getOptional != null) {
                                Object opt = getOptional.invoke(reg, loc);
                                if (opt instanceof Optional && ((Optional<?>) opt).isPresent()) {
                                    holder = ((Optional<?>) opt).get();
                                }
                            }
                            if (holder != null) {
                                try {
                                    Method valM = holder.getClass().getMethod("value");
                                    Object evt = valM.invoke(holder);
                                    if (evt instanceof SoundEvent) {
                                        int noteId = k - 39;
                                        libCache.put(noteId, evt);
                                        n++;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    System.out.println("[DJ] Registry fallback failed: " + t.getMessage());
                }
            }

            libReady = n > 0;
            System.out.println("[DJ] PianoLib cache: " + n + "/88 (Identifier)");
        } catch (Throwable t) {
            libReady = false;
            t.printStackTrace();
            System.out.println("[DJ] PianoLib initLib FAILED, cached " + n + " sounds");
        }

        if (mcPlay == null) {
            try {
                mcPlay = Class.forName("net.minecraft.world.level.Level").getMethod(
                    "playLocalSound", BlockPos.class, SoundEvent.class,
                    SoundSource.class, float.class, float.class, boolean.class);
                mcSrc = Class.forName("net.minecraft.sounds.SoundSource").getField("RECORDS").get(null);
            } catch (Throwable ignored) {}
        }
    }

/* ========== playLib() ========== */
    private static boolean playLib(int id) {
        if (id < NOTE_ID_A0 || id > NOTE_ID_C8) {
            System.out.println("[DJ] REJECT noteId=" + id + " out of range A0~C8");
            return false;
        }

        Minecraft m = Minecraft.getInstance();
        boolean inWorld = m.level != null && m.player != null;

        // 路径1：世界里走 PianoAPI
        if (inWorld && isPianoAPIAvail() && apiPlay != null) {
            try {
                int apiKey = id + PIANO_KEY_CENTER;
                apiPlay.invoke(null, apiKey, 1f);
                System.out.println("[DJ] PianoAPI key=" + apiKey + " noteId=" + id + "(" + nameOfId(id) + ") [world]");
                return true;
            } catch (Throwable t) {
                System.out.println("[DJ] PianoAPI failed: " + t.getMessage());
            }
        }

        // 路径2：PianoLib 缓存
        if (!libReady) initLib();
        Object evt = libCache.get(id);
        if (evt != null) {
            try {
                SoundEvent soundEvent = (SoundEvent) evt;
                if (inWorld) {
                    mcPlay.invoke(m.level, m.player.blockPosition(), evt, mcSrc, 2f, 1f, true);
                    System.out.println("[DJ] PianoLib noteId=" + id + "(" + nameOfId(id) + ") [world]");
                } else {
                    // ★ 主菜单：用 forUI 走 UI 音频通道（不依赖 Level）
                    SimpleSoundInstance inst = SimpleSoundInstance.forUI(soundEvent, 1.0f, 1.0f);
                    m.getSoundManager().play(inst);
                    System.out.println("[DJ] PianoLib noteId=" + id + "(" + nameOfId(id) + ") [mainmenu] inst=" + inst);
                }
                return true;
            } catch (Throwable t) {
                System.out.println("[DJ] PianoLib play failed: " + t.getMessage());
                t.printStackTrace();
            }
        } else {
            System.out.println("[DJ] no cache for noteId=" + id + "(" + nameOfId(id) + ")");
        }

        // 路径3：fallback HARP
        try {
            int midiNote = id + 60;
            int useCount = midiNote - 54;
            if (useCount >= 0 && useCount <= 24) {
                float pitch = Mth.clamp((float) Math.pow(2.0, (useCount - 12) / 12.0), 0.5f, 2.0f);
                if (inWorld) {
                    m.level.playSound(m.player, m.player.blockPosition(),
                        SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 2.0f, pitch);
                    System.out.println("[DJ] Fallback HARP noteId=" + id + " pitch=" + pitch + " [world]");
                } else {
                    // ★ 主菜单：HARP 也走 forUI
                    SimpleSoundInstance inst = SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_HARP.value(), 1.0f, pitch);
                    m.getSoundManager().play(inst);
                    System.out.println("[DJ] Fallback HARP noteId=" + id + " pitch=" + pitch + " [mainmenu] inst=" + inst);
                }
                return true;
            }
        } catch (Throwable t) {
            System.out.println("[DJ] Fallback HARP failed: " + t.getMessage());
        }
        return false;
    }
    private static synchronized void initRef() {
        if (refDone) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            Class<?> mcC = mc.getClass();
            try { gwM = mcC.getMethod("getWindow"); } catch (NoSuchMethodException e1) {
                try { gwM = mcC.getMethod("getMainWindow"); } catch (NoSuchMethodException e2) {
                    for (Method m : mcC.getMethods())
                        if (m.getParameterCount()==0 && m.getReturnType().getSimpleName().equals("Window"))
                            { gwM = m; break; }
                }
            }
            if (gwM != null) {
                Object wo = gwM.invoke(mc);
                if (wo != null) {
                    Class<?> wc = wo.getClass();
                    for (String s : new String[]{"getHandle","getWindow","longValue"})
                        try { ghM = wc.getMethod(s); break; } catch (NoSuchMethodException ignored) {}
                    if (ghM == null)
                        for (Method m : wc.getMethods())
                            if (m.getParameterCount()==0 && m.getReturnType()==long.class)
                                { ghM = m; break; }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        refDone = true;
    }

    private long getWin() {
        try {
            initRef();
            if (gwM == null || ghM == null) return 0L;
            Object wo = gwM.invoke(minecraft);
            if (wo == null) return 0L;
            Object r = ghM.invoke(wo);
            return r instanceof Long ? (long)r : 0L;
        } catch (Exception e) {
            try {
                Object w = minecraft.getClass().getMethod("getWindow").invoke(minecraft);
                if (w != null) {
                    Object h = w.getClass().getMethod("getHandle").invoke(w);
                    return h instanceof Long ? (long)h : 0L;
                }
            } catch (Exception x) { x.printStackTrace(); }
            return 0L;
        }
    }

    private boolean isSurvival() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameMode == null) return false;
            GameType m = mc.gameMode.getPlayerMode();
            return m != null && m.isSurvival();
        } catch (Exception e) { return false; }
    }

    private void scanNB() {
        try {
            Minecraft c = Minecraft.getInstance();
            if (c.level == null || c.player == null) return;
            if (++scanTick % 40 != 0 && nbReady) return;
            nbCache.clear();
            BlockPos p0 = c.player.blockPosition();
            for (int dx = -8; dx <= 8; dx++)
                for (int dy = -2; dy <= 2; dy++)
                    for (int dz = -8; dz <= 8; dz++) {
                        BlockPos p = p0.offset(dx, dy, dz);
                        BlockState s = c.level.getBlockState(p);
                        if (s.getBlock() instanceof NoteBlock) {
                            int v = s.getValue(BlockStateProperties.NOTE);
                            if (!nbCache.containsKey(v)) nbCache.put(v, p);
                        }
                    }
            nbReady = true;
        } catch (Exception e) { e.printStackTrace(); }
    }

    private BlockPos findNB(int id) {
        try {
            if (!nbReady) scanNB();
            BlockPos p = nbCache.get(id);
            if (p != null) return p;
            if (id >= 12) { p = nbCache.get(id - 12); if (p != null) return p; }
            if (id <= 12) { p = nbCache.get(id + 12); if (p != null) return p; }
        } catch (Exception e) {}
        return null;
    }

    private void broadcastNB(int id) {
        try {
            if (minecraft.player == null || minecraft.level == null) return;
            long now = System.currentTimeMillis();
            if (now - lastPkt < PKT_INT) return;
            lastPkt = now;
            BlockPos t = findNB(id);
            if (t != null) {
                minecraft.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, t, Direction.UP, 0));
                minecraft.player.swing(InteractionHand.MAIN_HAND);
            }
        } catch (Exception e) {}
    }

    @Override
    protected void init() {
        try {
            keyState.clear();
            shift = 0;
            isPianoAPIAvail();
            initLib();
            scanNB();

            int rx = width - 10;

            sndBtn = Button.builder(
                    Component.literal(broadcast ? "\uD83D\uDD0A" : "\uD83D\uDD08"),
                    b -> {
                        if (broadcast) { broadcast = false; b.setMessage(Component.literal("\uD83D\uDD08")); }
                        else if (isSurvival()) { broadcast = true; b.setMessage(Component.literal("\uD83D\uDD0A")); }
                    })
                .pos(rx - TS*2 - 4, TY).size(TS, TS).build();
            addRenderableWidget(sndBtn);

            addRenderableWidget(Button.builder(Component.literal("X"), b -> {
                if (MIDI_REC.isRecording()) {
                    String path = getRecordingsDir() + "/piano_" + System.currentTimeMillis() + ".mid";
                    boolean ok = MIDI_REC.stopAndSave(path);
                    System.out.println("[DJ] MIDI auto-save on close=" + ok + " path=" + path);
                }
                nbReady = false; nbCache.clear();
                Main.setScreenCompatStatic(minecraft, parent);
            }).pos(rx - TS, TY).size(TS, TS).build());

            recBtn = Button.builder(
                    Component.literal(MIDI_REC.isRecording() ? "\u25CF STOP" : "\u25CF REC"),
                    b -> {
                        if (MIDI_REC.isRecording()) {
                            String path = getRecordingsDir() + "/piano_" + System.currentTimeMillis() + ".mid";
                            boolean ok = MIDI_REC.stopAndSave(path);
                            System.out.println("[DJ] MIDI save=" + ok + " path=" + path);
                            b.setMessage(Component.literal("\u25CF REC"));
                        } else {
                            MIDI_REC.start();
                            System.out.println("[DJ] MIDI recording START");
                            b.setMessage(Component.literal("\u25CF STOP"));
                        }
                        updateRecBtnColor(b);
                    })
                .pos(REC_BTN_X, REC_BTN_Y).size(REC_BTN_W, REC_BTN_H).build();
            updateRecBtnColor(recBtn);
            addRenderableWidget(recBtn);

            addRenderableWidget(Button.builder(Component.literal("[-]"), b -> addDisp(-1)).pos(KX, BY).size(ICON, ICON).build());
            addRenderableWidget(Button.builder(Component.literal("[+]"), b -> addDisp(+1)).pos(KX+ICON+4, BY).size(ICON, ICON).build());
            addRenderableWidget(Button.builder(Component.literal("C4"), b -> setDisp(4)).pos(KX+(ICON+4)*2, BY).size(ICON*2, ICON).build());
            addRenderableWidget(Button.builder(Component.literal("CLR"), b -> history.clear()).pos(KX+(ICON+4)*4, BY).size(ICON, ICON).build());
            addRenderableWidget(Button.builder(Component.literal("SCN"), b -> { nbCache.clear(); nbReady=false; scanNB(); }).pos(KX+(ICON+4)*5+ICON, BY).size(ICON, ICON).build());
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void updateRecBtnColor(Button b) {
        if (b == null) return;
        if (MIDI_REC.isRecording()) {
            b.setMessage(Component.literal("\u25CF STOP").copy().withStyle(s -> s.withColor(0xFFFF2222)));
        } else {
            b.setMessage(Component.literal("\u25CF REC").copy().withStyle(s -> s.withColor(0xFFAAAAAA)));
        }
    }

    private String getRecordingsDir() {
        String dir = Minecraft.getInstance().gameDirectory.getAbsolutePath() + "/recordings";
        java.io.File d = new java.io.File(dir);
        if (!d.exists()) d.mkdirs();
        return dir;
    }

    @Override
    public void tick() {
        super.tick();
        try {
            active.clear();
            for (int n : pressed.keySet())
                if (n >= NOTE_ID_A0 && n <= NOTE_ID_C8) active.add(n);

            if (Main.SPECTRUM != null && Main.SPECTRUM.currentLevels != null) {
                float[] lv = Main.SPECTRUM.currentLevels;
                for (int i = 0; i < lv.length; i++) lv[i] *= 0.85f;
                for (int n : active) { int idx = n + 39; if (idx >= 0 && idx < lv.length) lv[idx] = 1f; }
            }

            List<Note> nh = new ArrayList<>();
            for (Note r : history) { r.age++; if (r.age < HIST_MAX) nh.add(r); }
            history.clear(); history.addAll(nh);

            scanNB();

            long win = getWin();
            if (win != 0L) {
                boolean k0 = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_0) == GLFW.GLFW_PRESS;
                if (k0 && !keyState.getOrDefault("K0", false)) {
                    System.out.println("[DJ] 0 -> C4 reset");
                    setDisp(4);
                }
                keyState.put("K0", k0);

                boolean k1 = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_1) == GLFW.GLFW_PRESS;
                if (k1 && !keyState.getOrDefault("K1", false)) {
                    System.out.println("[DJ] 1 -> A0(-39) \u2605");
                    if (playAndRecord(-39)) { addRoll(-39); if (broadcast) broadcastNB(-39); }
                    pressed.put(-39, true);
                }
                if (!k1) { pressed.remove(-39); MIDI_REC.noteOff(-39); }
                keyState.put("K1", k1);

                boolean k2 = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_2) == GLFW.GLFW_PRESS;
                if (k2 && !keyState.getOrDefault("K2", false)) {
                    System.out.println("[DJ] 2 -> A#0(-38) \u2605");
                    if (playAndRecord(-38)) { addRoll(-38); if (broadcast) broadcastNB(-38); }
                    pressed.put(-38, true);
                }
                if (!k2) { pressed.remove(-38); MIDI_REC.noteOff(-38); }
                keyState.put("K2", k2);

                boolean k3 = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_3) == GLFW.GLFW_PRESS;
                if (k3 && !keyState.getOrDefault("K3", false)) {
                    System.out.println("[DJ] 3 -> B0(-37) \u2605");
                    if (playAndRecord(-37)) { addRoll(-37); if (broadcast) broadcastNB(-37); }
                    pressed.put(-37, true);
                }
                if (!k3) { pressed.remove(-37); MIDI_REC.noteOff(-37); }
                keyState.put("K3", k3);

                boolean esc = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
                if (esc && !keyState.getOrDefault("ESC", false)) {
                    if (MIDI_REC.isRecording()) {
                        String path = getRecordingsDir() + "/piano_" + System.currentTimeMillis() + ".mid";
                        boolean ok = MIDI_REC.stopAndSave(path);
                        System.out.println("[DJ] MIDI auto-save on ESC=" + ok + " path=" + path);
                        if (recBtn != null) updateRecBtnColor(recBtn);
                    }
                    nbReady = false; nbCache.clear();
                    Main.setScreenCompatStatic(minecraft, parent);
                }
                keyState.put("ESC", esc);

                for (Map.Entry<Integer, Integer> ent : KEY_MAP.entrySet()) {
                    int key = ent.getKey();
                    String keyName = GLFW.glfwGetKeyName(key, 0);
                    boolean now = GLFW.glfwGetKey(win, key) == GLFW.GLFW_PRESS;
                    boolean was = keyState.getOrDefault("K"+key, false);
                    int noteId = ent.getValue() + shift * 12;
                    boolean ok = noteId >= NOTE_ID_A0 && noteId <= NOTE_ID_C8;

                    if (now && !was) {
                        if (ok) {
                            System.out.println("[DJ] key=" + keyName +
                                " offset=" + ent.getValue() + " shift=" + shift +
                                "(+" + getDisp() + ") -> " + noteId + "(" + nameOfId(noteId) + ")");
                            if (playAndRecord(noteId)) {
                                addRoll(noteId);
                                if (broadcast) broadcastNB(noteId);
                            }
                            pressed.put(noteId, true);
                        } else {
                            System.out.println("[DJ] REJECT noteId=" + noteId + " key=" + keyName);
                        }
                    }
                    if (!now && was) { pressed.remove(noteId); MIDI_REC.noteOff(noteId); }
                    keyState.put("K"+key, now);
                }
            }

            boolean ml = minecraft.mouseHandler.isLeftPressed();
            if (ml && !wasMouseDown) click(minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos());
            if (!ml) {
                Map<Integer, Boolean> keep = new HashMap<>();
                keep.put(-39, pressed.getOrDefault(-39, false));
                keep.put(-38, pressed.getOrDefault(-38, false));
                keep.put(-37, pressed.getOrDefault(-37, false));
                for (int k : pressed.keySet()) {
                    if (k >= NOTE_ID_A0 && k <= NOTE_ID_C8) keep.put(k, pressed.get(k));
                }
                pressed.clear();
                pressed.putAll(keep);
                for (int k : keep.keySet()) {
                    if (keep.get(k)) MIDI_REC.noteOff(k);
                }
            }
            wasMouseDown = ml;
        } catch (Exception e) { e.printStackTrace(); }
    }

    private boolean playAndRecord(int noteId) {
        boolean ok = playLib(noteId);
        if (ok) {
            int velocity = calcVelocity(noteId);
            MIDI_REC.noteOn(noteId, velocity);
        }
        return ok;
    }

    private int calcVelocity(int noteId) {
        int midiNote = noteId + 60;
        int dist = Math.abs(midiNote - 60);
        int vel = 100 - (dist / 12) * 8;
        return Math.max(64, Math.min(110, vel));
    }

    /* ✅ DJP025xxx：addRoll 按 A-2 起算的全局坐标存 lx（history 跨八度连续） */
    private void addRoll(int id) {
        int rw = WKW * TOTAL_WHITE;
        history.add(new Note(id, ((id - NOTE_ID_A_MIN) * rw) / RW_SPAN, 180));
    }

    public void extractBackground(GuiGraphicsExtractor c, int mx, int my, float d) {
        try { super.extractBackground(c, mx, my, d); } catch (Exception e) {}
    }

    public void extractRenderState(GuiGraphicsExtractor c, int mx, int my, float d) {
        try {
            super.extractRenderState(c, mx, my, d);

            c.text(font, Component.literal("[Disc Jockey] Piano \u2014 PianoLib 88-key (A0~C8)"),
                width/2 - font.width(Component.literal("[Disc Jockey] Piano \u2014 PianoLib 88-key (A0~C8)"))/2, 20, 0xFFFFFFFF, false);

            int aNoteId = 0 + shift * 12;
            String ot = "OCTAVE: +" + getDisp() + "(int " + shift + ") | A-KEY: " + nameOfId(aNoteId) + " | Range: A0 ~ C8";
            int oc = (shift == 0) ? 0xFF00FF00 : (shift > 0 ? 0xFFFFFF00 : 0xFF8888FF);
            c.text(font, Component.literal(ot), width/2 - font.width(Component.literal(ot))/2, 38, oc, false);

            String ht = "1=A0 2=A#0 3=B0 (固定) A=C4 W=C#4 S=D4 E=D#4 D=E4 F=F4 T=F#4 G=G4 Y=G#4 H=A4 | [-][+] = octave | 0 = C4 | ESC = close";
            c.text(font, Component.literal(ht), width/2 - font.width(Component.literal(ht))/2, 56, 0x666666, false);

            drawRoll(c);
            drawKeys(c);
            drawStatus(c);
        } catch (Exception e) { e.printStackTrace(); }
    }

    /* ✅ DJP025xxx：返回当前 shift 对应八度窗口在全局坐标（A-2 起算）里的起始 lx。
       使 Roll 画布随 [-]/[+] 八度窗口滑动，高音/低音区音符均落入当前视图。 */
    private int rollOrigin() {
        // 当前 shift 下，琴键区第一个可见白键的 noteId（A0 + shift*12），再按 A-2 起算
        int firstVisibleWhite = WHITE_NOTE_IDS[0] + shift * 12+24;
        int rw = WKW * TOTAL_WHITE;
        return ((firstVisibleWhite - NOTE_ID_A_MIN) * rw) / RW_SPAN;
    }

    /* ✅ DJP025xxx：drawRoll 通过 rollOrigin() 让画布随 [-]/[+] 八度窗口滑动。
       全局坐标从 A-2 起算（NOTE_ID_A_MIN=-63），A-2 在画布最左，C8 在最右。 */
    private void drawRoll(GuiGraphicsExtractor c) {
        int rw = WKW * TOTAL_WHITE, rh = RH;
        // 当前八度窗口起点（A-2 起算的全局 lx）
        int origin = rollOrigin();

        c.fill(KX-1, RY-1, KX+rw+1, RY+rh+1, 0x55444444);
        c.fill(KX, RY, KX+rw, RY+rh, 0x990A0A0A);

        // 背景刻度竖线：A-2 ~ C8，减去 origin
        for (int n = NOTE_ID_A_MIN; n <= NOTE_ID_C8; n++) {
            int lx = KX + (((n - NOTE_ID_A_MIN) * rw) / RW_SPAN) - origin;
            if (lx < KX || lx > KX + rw) continue;
            int a = (n % 12 == 0) ? 0x44 : 0x18;
            c.fill(lx, RY, lx+1, RY+rh, (a<<24)|0x888888);
        }

        // 历史音符：r.lx 已是 A-2 起算，减去 origin
        for (Note r : history) {
            int bx = KX + r.lx + 1 - origin;
            if (bx < KX || bx > KX + rw) continue;
            int by = RY + 2 + (r.age * 3) % (rh - 4);
            float fade = Mth.clamp(1f - (float)r.age / HIST_MAX, 0.1f, 1f);
            c.fill(bx, by, bx + Math.max(2, rw/40), by+2, color(r.id, (int)(fade * r.iv)));
        }

        // 实时按下高亮
        for (int n : active) {
            int lx = KX + ((n - NOTE_ID_A_MIN) * rw) / RW_SPAN - origin;
            if (lx < KX || lx > KX + rw) continue;
            c.fill(lx, RY, lx+2, RY+rh, 0x66FF4444);
        }

        c.text(font, Component.literal("Piano Roll (A-2~C8)"), KX, RY-14, 0x88FFFFFF, false);
    }

    private void drawKeys(GuiGraphicsExtractor c) {
        for (int i = 0; i < TOTAL_WHITE; i++) {
            int nid = WHITE_NOTE_IDS[i] + shift * 12;
            int x = KX + i * WKW;
            int y = KY;
            boolean pr = pressed.getOrDefault(nid, false);
            boolean ac = active.contains(nid);
            boolean inRange = (nid >= NOTE_ID_A0 && nid <= NOTE_ID_C8);

            int col = !inRange ? 0xFFDDDDDD : (pr ? 0xFFE8E8E8 : (ac ? 0xFFF0F8E8 : 0xFFFFFFFF));
            c.fill(x, y, x+WKW, y+WKH, col);
            c.fill(x+2, y+WKH-2, x+WKW, y+WKH, 0x22000000);
            c.fill(x, y, x+WKW, y+1, 0xFFAAAAAA);
            c.fill(x, y, x+1, y+WKH, 0xFFAAAAAA);
            c.fill(x, y+WKH-1, x+WKW, y+WKH, 0xFF888888);
            c.fill(x+WKW-1, y, x+WKW, y+WKH, 0xFF888888);

            String label = labelForWhite(i);
            int lblColor;
            if (nid == -39)         { label = "\u2605A0\u2605"; lblColor = 0xFF00FF00; }
            else if (nid == 0)      { label = "\u2605C4\u2605"; lblColor = 0xFFFFFF00; }
            else if (nid == 9)     { label = "A4";          lblColor = 0xFFFF8800; }
            else if (!inRange)      { lblColor = 0xFF666666; }
            else                     { lblColor = 0xFF333333; }

            c.text(font, Component.literal(label),
                x + WKW/2 - font.width(Component.literal(label))/2,
                y + WKH - 16, lblColor, false);

            if (ac && inRange) c.fill(x+2, y+2, x+WKW-2, y+5, 0x6600FF66);
        }

        for (int i = 0; i < TOTAL_BLACK; i++) {
            int nid = BLACK_NOTE_IDS[i] + shift * 12;
            int aw = BLACK_AFTER_WHITE[i];
            int x = KX + aw * WKW + WKW - BKW/2;
            int y = KY;
            boolean pr = pressed.getOrDefault(nid, false);
            boolean ac = active.contains(nid);
            boolean inRange = (nid >= NOTE_ID_A0 && nid <= NOTE_ID_C8);

            int col = !inRange ? 0xFF2A2A2A : (pr ? 0xFF555555 : (ac ? 0xFF334433 : 0xFF1A1A1A));
            c.fill(x, y, x+BKW, y+BKH, col);
            if (!pr) c.fill(x+2, y+2, x+BKW-2, y+4, 0xFF3A3A3A);
            c.fill(x+2, y+BKH-3, x+BKW-2, y+BKH-1, 0xFF0A0A0A);

            String label = labelForBlack(i);
            int lblColor = inRange ? 0xFF888888 : 0xFF555555;
            c.text(font, Component.literal(label),
                x+BKW/2 - font.width(Component.literal(label))/2,
                y+BKH-14, lblColor, false);
            if (ac && inRange) c.fill(x+2, y+2, x+BKW-2, y+5, 0x8800FF66);
        }

        int firstNid = 0 + shift * 12;
        int fi = -1, li = -1;
        for (int i = 0; i < TOTAL_WHITE; i++) {
            int cur = WHITE_NOTE_IDS[i] + shift * 12;
            if (cur == firstNid && fi < 0) fi = i;
            if (li < 0 && cur >= firstNid + 12) li = i;
        }
        if (fi < 0) {
            int best = 0, bestDiff = 999;
            for (int i = 0; i < TOTAL_WHITE; i++) {
                int d = Math.abs((WHITE_NOTE_IDS[i] + shift * 12) - firstNid);
                if (d < bestDiff) { bestDiff = d; best = i; }
            }
            fi = best;
        }
        if (li < 0 || li <= fi) {
            li = TOTAL_WHITE - 1;
        }
        if (fi >= 0) {
            int sx1 = KX + fi * WKW;
            int sx2 = KX + (li + 1) * WKW;
            c.fill(sx1, KY-4, sx2, KY-2, 0x6600AAFF);
            c.fill(sx1, KY+WKH+2, sx2, KY+WKH+4, 0x6600AAFF);
        }

        c.fill(KX + C4_INDEX*WKW, KY-6, KX + C4_INDEX*WKW+2, KY, 0xFFFFFF00);
        for (int i = 0; i < TOTAL_WHITE; i++) if (WHITE_NOTE_IDS[i] == 9) {
            c.fill(KX + i*WKW, KY-6, KX + i*WKW+2, KY, 0xFFFF8800);
            break;
        }
        c.fill(KX, KY-6, KX+2, KY, 0xFFFF0000);
    }

    private void drawStatus(GuiGraphicsExtractor c) {
        StringBuilder sb = new StringBuilder();
        int aNoteId = 0 + shift * 12;
        sb.append("Octave: +").append(getDisp()).append("(int ").append(shift).append(") | ");
        sb.append("A-Key: ").append(nameOfId(aNoteId)).append(" | ");
        sb.append("Keys: ").append(TOTAL_WHITE).append("/52 | ");
        sb.append("Lib: ").append(String.valueOf(libCache.size())).append("/88").append(libReady ? "" : " (LOADING...)").append(" | ");
        sb.append("API: ").append(apiOK==null?"?":(apiOK?"ON":"OFF")).append(" | ");
        sb.append("BCast: ").append(broadcast?"ON":"OFF").append(" | ");
        if (MIDI_REC.isRecording()) {
            sb.append("REC: ON(").append(MIDI_REC.getNoteCount()).append(") | ");
            sb.append("Sustain: ON | Reverb: ON | ADSR: ON");
        } else {
            sb.append("REC: OFF");
        }
        sb.append(" | NB: ").append(nbCache.size());
        if (!active.isEmpty()) {
            sb.append(" | ");
            for (int n : active) {
                sb.append(nameOfId(n)).append(" ");
            }
        }
        c.text(font, Component.literal(sb.toString()), KX, SY, 0x88FFFFFF, false);

        if (MIDI_REC.isRecording()) {
            long t = System.currentTimeMillis();
            int alpha = 120 + (int)(60 * Math.sin(t / 200.0));
            int barW = 200;
            int barX = width/2 - barW/2;
            c.fill(barX, 4, barX + barW, 6, (alpha<<24)|0xFFFF2222);
            c.text(font, Component.literal("\u25CF RECORDING MIDI \u2014 Sustain+Reverb+ADSR"),
                barX + barW/2 - font.width(Component.literal("\u25CF RECORDING MIDI \u2014 Sustain+Reverb+ADSR"))/2,
                8, 0xFFFF2222, false);
        }
    }

    private void click(double mx, double my) {
        int x0 = KX;
        if (mx >= x0 && mx < x0+WKW && my >= KY && my < KY+WKH) {
            if (-39 >= NOTE_ID_A0 && -39 <= NOTE_ID_C8) {
                System.out.println("[DJ] click A0(-39) \u2605");
                if (playAndRecord(-39)) { addRoll(-39); if (broadcast) broadcastNB(-39); }
                pressed.put(-39, true);
            }
            return;
        }
        int xA0s = KX + 0*WKW + WKW - BKW/2;
        if (mx >= xA0s && mx < xA0s+BKW && my >= KY && my < KY+BKH) {
            if (-38 >= NOTE_ID_A0 && -38 <= NOTE_ID_C8) {
                System.out.println("[DJ] click A#0(-38) \u2605");
                if (playAndRecord(-38)) { addRoll(-38); if (broadcast) broadcastNB(-38); }
                pressed.put(-38, true);
            }
            return;
        }
        int x1 = KX + 1*WKW;
        if (mx >= x1 && mx < x1+WKW && my >= KY && my < KY+WKH) {
            if (-37 >= NOTE_ID_A0 && -37 <= NOTE_ID_C8) {
                System.out.println("[DJ] click B0(-37) \u2605");
                if (playAndRecord(-37)) { addRoll(-37); if (broadcast) broadcastNB(-37); }
                pressed.put(-37, true);
            }
            return;
        }

        for (int i = 0; i < TOTAL_BLACK; i++) {
            int nid = BLACK_NOTE_IDS[i] + shift * 12;
            int x = KX + BLACK_AFTER_WHITE[i] * WKW + WKW - BKW/2;
            if (mx >= x && mx < x+BKW && my >= KY && my < KY+BKH) {
                if (nid >= NOTE_ID_A0 && nid <= NOTE_ID_C8) {
                    System.out.println("[DJ] click BLACK " + nid + "(" + nameOfId(nid) + ")");
                    if (playAndRecord(nid)) { addRoll(nid); if (broadcast) broadcastNB(nid); }
                    pressed.put(nid, true);
                }
                return;
            }
        }
        for (int i = 0; i < TOTAL_WHITE; i++) {
            int nid = WHITE_NOTE_IDS[i] + shift * 12;
            int x = KX + i * WKW;
            if (mx >= x && mx < x+WKW && my >= KY && my < KY+WKH) {
                if (nid >= NOTE_ID_A0 && nid <= NOTE_ID_C8) {
                    System.out.println("[DJ] click WHITE " + nid + "(" + nameOfId(nid) + ")");
                    if (playAndRecord(nid)) { addRoll(nid); if (broadcast) broadcastNB(nid); }
                    pressed.put(nid, true);
                }
                return;
            }
        }
    }

    /* ✅ DJP025xxx：color() 改用 A-2 起算的全局坐标，保持颜色随全局坐标渐变一致 */
    private int color(int id, int a) {
        a = Mth.clamp(a, 30, 255);
        float t = (float)(id - NOTE_ID_A_MIN) / RW_SPAN;
        int r, g, b;
        if (t < 0.33f) { r=255; g=(int)(100+155*t/0.33f); b=50; }
        else if (t < 0.66f) { r=(int)(255-155*(t-0.33f)/0.33f); g=255; b=50; }
        else { r=(int)(100-50*(t-0.66f)/0.34f); g=(int)(255-100*(t-0.66f)/0.34f); b=(int)(100+155*(t-0.66f)/0.34f); }
        return (a<<24)|((r&0xFF)<<16)|((g&0xFF)<<8)|(b&0xFF);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /* ========================================================================
     * ★ MidiRecorder —— 带钢琴延音/回音效果
     * ------------------------------------------------------------------------
     * ✅ DJP024xxx 混淆安全修复：
     *   - start() 中 catch(InvalidMidiDataException e) 变量 e → t
     *   - stopAndSave() 中 catch(InvalidMidiDataException | IOException e)
     *     拆分为两个独立 catch，各自变量 t，并改用 FileOutputStream 显式写出
     * ====================================================================== */
    private static class MidiRecorder {
        private static final int PPQ = 480;
        private static final int CHANNEL = 0;
        private static final int BPM = 120;

        private static final int PROGRAM_GRAND_PIANO = 0;

        private static final int SUSTAIN_PEDAL_CC = 64;
        private static final int REVERB_DEPTH_CC  = 91;
        private static final int REVERB_LEVEL_CC  = 93;
        private static final int EXPRESSION_CC    = 11;
        private static final int VOLUME_CC        = 7;

        private static final long MIN_DUR_MS = 150;
        private static final long RELEASE_MS = 800;
        private static final long LOW_NOTE_RELEASE_MS = 2000;
        private static final long HIGH_NOTE_RELEASE_MS = 500;

        private Sequence sequence;
        private Track track;
        private long startTimeMs;
        private boolean recording;
        private int noteCount;

        private final Map<Integer, Integer> pressCount = new HashMap<>();
        private final Map<Integer, Long> pressMs = new HashMap<>();
        private long lastTick = 0;

        void start() {
            try {
                sequence = new Sequence(Sequence.PPQ, PPQ);
                track = sequence.createTrack();

                addProgramChange(PROGRAM_GRAND_PIANO);
                addControlChange(SUSTAIN_PEDAL_CC, 127);
                addControlChange(REVERB_DEPTH_CC, 50);
                addControlChange(REVERB_LEVEL_CC, 40);
                addControlChange(VOLUME_CC, 110);
                addControlChange(EXPRESSION_CC, 120);

                addTempo(BPM);

                startTimeMs = System.currentTimeMillis();
                recording = true;
                noteCount = 0;
                pressCount.clear();
                pressMs.clear();
                lastTick = 0;
                System.out.println("[MIDI] Recording START (PPQ=" + PPQ + ", BPM=" + BPM
                    + ", Program=GrandPiano(0), Sustain=ON, Reverb=ON)");
            } catch (InvalidMidiDataException t) {
                recording = false;
                t.printStackTrace();
            }
        }

        private int toMidiNote(int noteId) {
            return noteId + 60;
        }

        private long calcReleaseMs(int noteId) {
            int mnote = noteId + 60;
            if (mnote < 36)  return LOW_NOTE_RELEASE_MS;
            if (mnote < 60)  return 1200;
            if (mnote < 84)  return RELEASE_MS;
            return HIGH_NOTE_RELEASE_MS;
        }

        void noteOn(int pianoLibId, int velocity) {
            if (!recording) return;
            int mnote = toMidiNote(pianoLibId);
            if (mnote < 0 || mnote > 127) {
                System.out.println("[MIDI] REJECT noteId=" + pianoLibId + " midi=" + mnote + " out of range");
                return;
            }

            long nowMs = System.currentTimeMillis();
            long tick = msToTick(nowMs - startTimeMs);

            int count = pressCount.getOrDefault(mnote,0);
            pressCount.put(mnote, count + 1);
            pressMs.put(mnote, nowMs);

            try {
                ShortMessage on = new ShortMessage();
                on.setMessage(ShortMessage.NOTE_ON, CHANNEL, mnote, clampVel(velocity));
                track.add(new MidiEvent(on, tick));
                noteCount++;
                System.out.println("[MIDI] NOTE_ON midi=" + mnote + "(" + name(mnote) + ") vel=" + velocity + " tick=" + tick);
            } catch (InvalidMidiDataException ignored) {}
        }

        void noteOff(int pianoLibId) {
            if (!recording) return;
            int mnote = toMidiNote(pianoLibId);
            if (mnote < 0 || mnote > 127) return;

            int count = pressCount.getOrDefault(mnote,0);
            if (count <= 0) return;
            if (count > 1) {
                pressCount.put(mnote, count - 1);
                return;
            }
            pressCount.remove(mnote);

            long nowMs = System.currentTimeMillis();
            Long onMsObj = pressMs.remove(mnote);
            if (onMsObj == null) return;
            long onMs = onMsObj;

            long releaseMs = calcReleaseMs(pianoLibId);

            long currentTick = msToTick(nowMs - startTimeMs);
            long releaseTicks = msToTick(releaseMs);
            long onTick = msToTick(onMs - startTimeMs);
            long offTick = currentTick + releaseTicks;

            if (offTick <= onTick + msToTick(MIN_DUR_MS)) offTick = onTick + msToTick(MIN_DUR_MS);

            try {
                long fadeTick = currentTick + msToTick(releaseMs / 3);
                if (fadeTick > onTick + msToTick(MIN_DUR_MS) && fadeTick < offTick) {
                    ShortMessage fade = new ShortMessage();
                    fade.setMessage(ShortMessage.NOTE_ON, CHANNEL, mnote, clampVel(20));
                    track.add(new MidiEvent(fade, fadeTick));
                }

                ShortMessage off = new ShortMessage();
                off.setMessage(ShortMessage.NOTE_OFF, CHANNEL, mnote, 0);
                track.add(new MidiEvent(off, offTick));
                System.out.println("[MIDI] NOTE_OFF midi=" + mnote + " dur=" + (offTick - onTick) + "t release=" + releaseMs + "ms");
            } catch (InvalidMidiDataException ignored) {}
        }

        boolean stopAndSave(String filePath) {
            if (!recording) return false;
            recording = false;

            long endMs = System.currentTimeMillis();
            long endTick = msToTick(endMs - startTimeMs);

            for (Map.Entry<Integer, Long> e : pressMs.entrySet()) {
                int mnote = e.getKey();
                long onMs = e.getValue();
                long onTick = msToTick(onMs - startTimeMs);
                long releaseTicks = msToTick(calcReleaseMsFromMidi(mnote));
                long offTick = Math.max(endTick + 1, onTick + msToTick(MIN_DUR_MS)) + releaseTicks;

                long fadeTick = endTick + msToTick(calcReleaseMsFromMidi(mnote) / 3);
                if (fadeTick > onTick) {
                    try {
                        ShortMessage fade = new ShortMessage();
                        fade.setMessage(ShortMessage.NOTE_ON, CHANNEL, mnote, 20);
                        track.add(new MidiEvent(fade, fadeTick));
                    } catch (InvalidMidiDataException ignored) {}
                }

                try {
                    ShortMessage off = new ShortMessage();
                    off.setMessage(ShortMessage.NOTE_OFF, CHANNEL, mnote, 0);
                    track.add(new MidiEvent(off, offTick));
                } catch (InvalidMidiDataException ignored) {}
                System.out.println("[MIDI] AUTO-OFF midi=" + mnote + " tick=" + offTick);
            }

            try {
                ShortMessage pedalUp = new ShortMessage();
                pedalUp.setMessage(ShortMessage.CONTROL_CHANGE, CHANNEL, SUSTAIN_PEDAL_CC, 0);
                track.add(new MidiEvent(pedalUp, endTick + 1));
                System.out.println("[MIDI] Sustain pedal RELEASED");
            } catch (InvalidMidiDataException ignored) {}

            pressCount.clear();
            pressMs.clear();

            try {
                MetaMessage end = new MetaMessage();
                end.setMessage(0x2F, new byte[0], 0);
                long lastEvtTick = track.size() > 0
                    ? track.get(track.size() - 1).getTick() : endTick;
                long eotTick = Math.max(endTick + 2, lastEvtTick + 1);
                track.add(new MidiEvent(end, eotTick));

                java.io.File out = new java.io.File(filePath);
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    MidiSystem.write(sequence, 1, fos);
                }
                System.out.println("[MIDI] Saved: " + out.getAbsolutePath()
                    + " (" + noteCount + " notes, " + eotTick + " ticks, Sustain+Reverb ON)");
                return true;
            } catch (InvalidMidiDataException t) {
                t.printStackTrace();
                return false;
            } catch (IOException t) {
                t.printStackTrace();
                return false;
            }
        }

        private long calcReleaseMsFromMidi(int mnote) {
            if (mnote < 36)  return LOW_NOTE_RELEASE_MS;
            if (mnote < 60)  return 1200;
            if (mnote < 84)  return RELEASE_MS;
            return HIGH_NOTE_RELEASE_MS;
        }

        boolean isRecording() { return recording; }
        int getNoteCount() { return noteCount; }

        private long msToTick(long ms) {
            double seconds = ms / 1000.0;
            double beats = seconds * (BPM / 60.0);
            long tick = (long) (beats * PPQ);
            if (tick <= lastTick) tick = lastTick + 1;
            lastTick = tick;
            return tick;
        }

        private void addTempo(int bpm) throws InvalidMidiDataException {
            int mpqn = 60_000_000 / bpm;
            MetaMessage tempo = new MetaMessage();
            tempo.setMessage(0x51, new byte[]{
                (byte) (mpqn >> 16), (byte) (mpqn >> 8), (byte) mpqn
            }, 3);
            track.add(new MidiEvent(tempo, 0));
        }

        private void addProgramChange(int program) throws InvalidMidiDataException {
            ShortMessage pc = new ShortMessage();
            pc.setMessage(ShortMessage.PROGRAM_CHANGE, CHANNEL, program, 0);
            track.add(new MidiEvent(pc, 0));
            System.out.println("[MIDI] ProgramChange -> " + program + " (Grand Piano)");
        }

        private void addControlChange(int controller, int value) {
            try {
                ShortMessage cc = new ShortMessage();
                cc.setMessage(ShortMessage.CONTROL_CHANGE, CHANNEL, controller, value);
                track.add(new MidiEvent(cc, 0));
                System.out.println("[MIDI] CC" + controller + " = " + value);
            } catch (InvalidMidiDataException ignored) {}
        }

        private int clampVel(int v) { return Math.max(1, Math.min(127, v)); }
    }
}
