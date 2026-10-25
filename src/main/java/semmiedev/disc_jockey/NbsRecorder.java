package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 游戏内音符盒录制器 —— 把指定半径内音符盒的发声按时间轴记成 .nbs。
 *
 * ★ 与 Main 同级（semmiedev.disc_jockey 包），平台无关：
 *   只用 net.minecraft.* + 项目自身的 Main / Song / SongLoader / Note，
 *   不引用任何 Fabric / NeoForge 专属 API，三个版本共用同一份。
 *
 * ★ 保存为什么直接复用 Song.save(File)：
 *   Song.java 里已有完整的 NBS 写出端（writeNotesLE / writeLayersLE /
 *   writeNbsStringLE），且明确是【旧格式 v0-v3】—— 与 SongLoader.loadSong()
 *   的读取分支完全对齐（length != 0 → 走旧格式，不读 formatVersion）。
 *   所以录出来的文件一定能被自己加载，不会变成"损坏文件"。
 *
 * ★ 时间轴换算（照 Song.millisecondsToTicks 反推）：
 *     songSpeed = tempo / 100 / 20 = 1000/100/20 = 0.5
 *     ticks     = ms × 0.02 × songSpeed = ms / 100
 *   即 tempo=1000 时【100ms = 1 tick】，与 SongPlayer 播放端同一套换算，
 *   录完回放节奏自洽。
 *
 * ★ 音高换算（实测为准，勿再用旧推导）：
 *     音符盒 note 属性 0..24 → 0 = F#3 = MIDI 54（这是 MC 侧唯一的真实音高源）
 *     本项目的 NBS noteId 就是音符盒 note 本身（0..24），1:1 存储。
 *
 *   ⚠ 早期版本按「MIDI = noteId + 33」推导出 noteId = blockNote + 21，
 *     实测回放偏高 21 个半音（弹 A15 → 出 #F36；弹 E10 → 出 #C31，两处偏差
 *     都恰好 21），证明加载端并不用那套基准。现由 NOTE_ID_OFFSET 控制，
 *     默认 0（即 1:1）。若你的加载端另有基准，改这一个常量即可。
 *
 * ★ 乐器保真范围（旧格式限制，需知悉）：
 *   Song.save 写的是旧格式，加载端 Note.fromNbs(version=0, id) 走 NBS_V0，
 *   只有 10 个乐器（HARP..XYLOPHONE）。录制时若碰到后 6 种
 *   （IRON_XYLOPHONE..PLING），写出的 id≥10 在加载时会被映射成 HARP。
 *   这与项目里其它导出路径行为一致，不是本录制器的 bug。
 */
public final class NbsRecorder {

    private NbsRecorder() {}

    // ---------------- 状态 ----------------
    private static volatile boolean recording = false;
    private static volatile long startNanos = 0L;
    private static volatile BlockPos center = null;
    private static volatile int radius = 8;

    /** 每条 = { tick, instrumentId, noteId } */
    private static final List<long[]> captured =
            Collections.synchronizedList(new ArrayList<long[]>());

    private static volatile boolean hookTried = false;
    private static volatile boolean hookOk = false;

    /** 去重：同一 tick 内同一(乐器+音高)的重复触发只记一次 */
    private static volatile long dedupTick = -1L;
    private static volatile long dedupKey = 0L;

    // ---------------- 诊断统计（排查"录不到"用，停止录制时打印） ----------------
    private static volatile long statPolls = 0L;         // 队列轮询次数
    private static volatile long statQueueNonEmpty = 0L; // 队列非空次数
    private static volatile long statEvents = 0L;        // 从队列取出的事件总数
    private static volatile long statCaptured = 0L;      // 成功记入的音符数
    private static volatile long statOutRadius = 0L;     // 半径外被丢弃
    private static volatile long statNotNoteBlock = 0L;  // 不是音符盒被丢弃
    private static volatile long statBadNote = 0L;       // 音高读失败被丢弃
    private static volatile String statQueueField = "(未找到)";

    /**
     * ★ 音高偏移：NBS noteId = 音符盒 note(0..24) + NOTE_ID_OFFSET
     *
     * 默认 0 —— 本项目的 noteId 就是音符盒 note，直接存读到的值即可。
     * （曾用 +21：实测回放偏高 21 个半音，弹 A15 出 #F36、弹 E10 出 #C31，
     *   两处偏差都恰好 21，故改为 0。类注释里那套「MIDI = noteId + 33」的
     *   推导与实际加载端不符，已作废。）
     *
     * 若你的加载端另有基准，改这一个常量就行，不用动别处。
     */
    private static final int NOTE_ID_OFFSET = 0;

    /**
     * ★ 已废弃：不再需要 Mixin。
     *
     * 原来 Fabric 侧要靠 Mixin 钩 ClientPacketListener.handleBlockEvent 才能拿到包，
     * 但那要改 disc_jockey.mixins.json（且必须放 client 数组，放错会让服务端崩）。
     *
     * 现在改用【服务端方块事件队列轮询】（tryServerBlockEventQueue）：
     *   单人世界下服务端与客户端在同一个 JVM，Minecraft.getSingleplayerServer()
     *   能直接拿到 ServerLevel，而它内部有个存放 BlockEventData 的队列 ——
     *   音符盒被敲时服务端会往里塞一条，tick 时消费并广播。
     *   高频轮询这个队列就能捕获全部发声，纯反射只读、零依赖、零风险。
     *
     * 保留本常量仅为兼容旧说明；installHook 不再读取它。
     * 如果你已经在 mixins.json 里加了 NoteBlockEventMixin，留着也无妨 ——
     * 去重逻辑会保证同一音符不会被记两次。
     */
    @Deprecated
    private static final boolean USE_MIXIN = false;

    // ---------------- 服务端方块事件队列轮询（无需 Mixin） ----------------

    /** 轮询间隔（毫秒）。服务端每 tick(50ms) 消费一次队列，4ms 足够密 */
    private static final long QUEUE_POLL_MS = 4L;
    /** 已处理过的 BlockEventData（按对象身份去重，队列 tick 内会被反复看到） */
    private static final java.util.Set<Object> seenEvents =
            Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());

    private static volatile Field queueFieldCache = null;
    private static volatile Thread queuePollThread = null;
    private static volatile boolean queueMode = false;

    public static final int MIN_RADIUS = 1;
    /** 上限放宽到 64：大场景（音乐厅 / 整片音符盒阵列）也能一次录全 */
    public static final int MAX_RADIUS = 64;
    public static final int DEFAULT_RADIUS = 8;

    /** 录制速度（NBS tempo，单位 0.01 TPS）。1000 = 10 TPS = 100ms/tick，与 Song.ensureDefaults 一致 */
    public static final int MIN_TEMPO = 100;    // 1 TPS，慢放
    public static final int MAX_TEMPO = 10000;  // 100 TPS，快放
    public static final int DEFAULT_TEMPO = 1000;

    /** 界面上可改的 tempo；录制中修改会在【下次开始录制】生效（避免时间轴错乱） */
    private static int tempo = DEFAULT_TEMPO;
    /** 录制开始时锁定的 tempo —— 时间轴换算只认它 */
    private static volatile int recTempo = DEFAULT_TEMPO;
    /** 自定义文件名（null/空 = 用时间戳 Recording_yyyyMMdd_HHmmss） */
    private static String customName = null;

    /**
     * 当前 tempo 下一个 tick 是多少纳秒。
     * 推导（与 Song.millisecondsToTicks 同源）：
     *   songSpeed = tempo / 100 / 20  →  tick 时长(ms) = 1000 / (tempo/100/20)
     *   tempo=1000 → songSpeed=0.5 → 2000ms? 不对，按项目公式：ticks = ms × 0.02 × songSpeed
     *   实测口径：tempo=1000 → 100ms = 1 tick，故 tickNanos = 1e9 × 100 / (tempo/1000)
     *   即【tickNanos = 1e11 / tempo】。tempo=1000 → 1e8 ns = 100ms ✅
     */
    private static long tickNanos() {
        int t = (recTempo > 0) ? recTempo : DEFAULT_TEMPO;
        long ns = 100_000_000_000L / t;
        return (ns <= 0L) ? 1_000_000L : ns;   // 兜底 1ms，绝不除零
    }

    // ---------------- 对外接口 ----------------

    public static boolean isRecording() { return recording; }

    public static int getRadius() { return radius; }

    public static void setRadius(int r) {
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r));
    }

    public static int getTempo() { return tempo; }

    /** 设置录制速度；录制中调用只影响下一次录制（当前录制仍用锁定值） */
    public static void setTempo(int t) {
        tempo = Math.max(MIN_TEMPO, Math.min(MAX_TEMPO, t));
    }

    /** 每秒多少个 tick（界面显示用，如 "10.0 TPS"） */
    public static double ticksPerSecond() {
        return tempo / 100.0D;
    }

    /** 自定义文件名（空串/null 表示用时间戳） */
    public static String getCustomName() { return customName; }

    /** 设置自定义文件名；传 null 或空白表示恢复时间戳命名 */
    public static void setCustomName(String n) {
        if (n == null) { customName = null; return; }
        String s = n.trim();
        customName = s.isEmpty() ? null : s;
    }

    /**
     * 生成合法文件名：自定义名优先，否则时间戳。
     * 会剔除 Windows 非法字符，并统一补 .nbs 后缀。
     */
    private static String buildSongName() {
        String base;
        if (customName != null && !customName.trim().isEmpty()) {
            base = customName.trim();
        } else {
            base = "Recording_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        }
        return base.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 已捕获的音符数 */
    public static int noteCount() { return captured.size(); }

    /** 包钩子是否注册成功（false 时只能录到本 mod 自己发出的音） */
    public static boolean isHookOk() { return hookOk; }

    /**
     * 开始录制。
     * @param c 中心（通常用玩家位置）；null 表示不过滤中心，只按半径过滤时以玩家为准
     * @param r 半径（立方，与 NoteBlockScanner.countAround 口径一致）
     */
    public static boolean start(BlockPos c, int r) {
        if (recording) return false;
        center = (c != null) ? c.immutable() : null;
        setRadius(r);
        synchronized (captured) { captured.clear(); }
        dedupTick = -1L;
        dedupKey = 0L;
        startNanos = System.nanoTime();
        recTempo = (tempo > 0) ? tempo : DEFAULT_TEMPO;   // ★ 锁定：整段录制用同一个 tempo
        recording = true;
        installHook();
        seenEvents.clear();
        statPolls = statQueueNonEmpty = statEvents = statCaptured = 0L;
        statOutRadius = statNotNoteBlock = statBadNote = 0L;
        ensurePollThread();   // ★ 每次开录都要确保轮询线程在跑（上次录完已自然退出）
        Main.LOGGER.info("[DJ] 音符盒录制开始：半径 {}，速度 {}（{} TPS，1 tick={}ms），中心 {}",
                radius, recTempo, String.format(Locale.ROOT, "%.2f", recTempo / 100.0D),
                (tickNanos() / 1_000_000L),
                (center == null ? "(跟随玩家)" : center.toShortString()));
        return true;
    }

    /** 录制已进行的秒数 */
    public static double elapsedSeconds() {
        if (!recording) return 0.0D;
        return (System.nanoTime() - startNanos) / 1_000_000_000.0D;
    }

    /** 放弃录制（不保存） */
    public static void cancel() {
        recording = false;
        synchronized (captured) { captured.clear(); }
        seenEvents.clear();
    }

    /**
     * 停止并保存为 .nbs。
     * @return 保存出的文件名（如 Recording_20261025_153000.nbs）；失败返回 null
     */
    public static String stopAndSave() {
        if (!recording) return null;
        recording = false;
        seenEvents.clear();

        // ★ 诊断：无论成功与否都打印一次，用于定位"录不到"到底卡在哪一环
        Main.LOGGER.info("[DJ] 录制诊断：队列字段={} | 轮询={} 非空={} | 事件={} 记入={} | 丢弃[半径外={} 非音符盒={} 音高读失败={}]",
                statQueueField, statPolls, statQueueNonEmpty, statEvents, statCaptured,
                statOutRadius, statNotNoteBlock, statBadNote);

        List<long[]> snap;
        synchronized (captured) { snap = new ArrayList<long[]>(captured); }
        captured.clear();

        if (snap.isEmpty()) {
            Main.LOGGER.warn("[DJ] 录制结束但没有捕获到任何音符盒发声，未生成文件");
            return null;
        }

        try {
            Song song = new Song();
            song.name = buildSongName();
            song.fileName = song.name + ".nbs";
            song.displayName = song.name + " (" + song.fileName + ")";
            song.author = playerName();
            song.originalAuthor = "";
            song.description = "Recorded in-game by Disc Jockey";
            song.tempo = (short) recTempo;
            song.height = 1;                 // 全部记在 layer 0
            song.timeSignature = 4;
            song.vanillaInstrumentCount = 10; // 旧格式：加载端按 NBS_V0（10 乐器）解析
            song.formatVersion = 0;

            long[] arr = new long[snap.size()];
            int maxTick = 0;
            for (int i = 0; i < arr.length; i++) {
                long[] e = snap.get(i);
                int tick = (int) e[0];
                int layer = 0;
                int inst = (int) e[1] & 0xFF;
                int noteId = (int) e[2];
                if (tick > maxTick) maxTick = tick;

                long packed = ((long) tick)
                            | ((long) layer << 16)
                            | ((long) inst << 32);
                arr[i] = Note.packNoteId(packed, noteId);
            }
            song.notes = arr;
            song.length = (short) Math.max(1, maxTick + 1);
            song.ensureDefaults();
            song.detectKeySignature();

            File dir = Main.songsFolder;
            if (dir == null) {
                Main.LOGGER.warn("[DJ] Main.songsFolder 未初始化，录音未保存");
                return null;
            }
            if (!dir.isDirectory()) dir.mkdirs();

            File out = new File(dir, song.fileName);
            song.save(out);
            Main.LOGGER.info("[DJ] 录制保存完成：{} 个音符 → {}", arr.length, out.getAbsolutePath());

            // 刷新歌曲列表，让新录的歌立刻出现在 DJ 界面
            try {
                SongLoader.loadSongs();
            } catch (Throwable t) {
                Main.LOGGER.warn("[DJ] 录制后刷新歌曲列表失败：{}", String.valueOf(t));
            }
            return song.fileName;
        } catch (Throwable t) {
            Main.LOGGER.error("[DJ] 录制保存失败", t);
            return null;
        }
    }

    /**
     * 音符盒发声回调。
     *
     * 两个来源：
     *   ① 包钩子（网络线程调进来，内部用 mc.execute 切主线程后读 level）
     *   ② 本 mod 的 sendNB() 主动调用 —— 包钩子注册失败时的降级路径，
     *      保证"自己弹琴 / MIDI 广播"至少一定录得上。
     *
     * @param pos      发声的音符盒坐标
     * @param nanoTime 事件时间戳；<=0 表示用当前时间
     */
    public static void onNoteBlockEvent(BlockPos pos, long nanoTime) {
        if (!recording || pos == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        final long ts = (nanoTime > 0L) ? nanoTime : System.nanoTime();
        final BlockPos p = pos.immutable();
        try {
            mc.execute(() -> capture(mc, p, ts));
        } catch (Throwable t) {
            // execute 不可用时退回直接读（网络线程读 level 不理想，但好过丢音符）
            capture(mc, p, ts);
        }
    }

    // ---------------- 内部：采集 ----------------

    private static void capture(Minecraft mc, BlockPos pos, long ts) {
        if (!recording) return;
        if (mc == null || mc.level == null) return;

        if (!inRange(mc, pos)) { statOutRadius++; return; }

        BlockState st = mc.level.getBlockState(pos);
        if (!(st.getBlock() instanceof NoteBlock)) { statNotNoteBlock++; return; }

        int note = readNote(st);            // BlockStateProperties.NOTE，0..24
        if (note < 0 || note > 24) { statBadNote++; return; }

        int inst = readInstrument(st);      // → NBS instrument id
        long tick = (ts - startNanos) / tickNanos();
        if (tick < 0L) tick = 0L;

        // 去重：同一 tick 内同(乐器+音高)只记一次。
        // 包钩子与 sendNB 可能同时命中同一次发声，不去重会记两份。
        long key = (((long) inst) << 32) | (((long) note) << 8) | (tick & 0xFFL);
        if (tick == dedupTick && key == dedupKey) return;
        dedupTick = tick;
        dedupKey = key;

        // blockNote → NBS noteId（默认 1:1，见 NOTE_ID_OFFSET 说明）
        captured.add(new long[]{ tick, inst, note + NOTE_ID_OFFSET });
        statCaptured++;
    }

    /** 立方半径过滤；center==null 时以当前玩家为中心 */
    private static boolean inRange(Minecraft mc, BlockPos pos) {
        BlockPos c = center;
        if (c == null) {
            try {
                if (mc.player != null) c = mc.player.blockPosition();
            } catch (Throwable t) {}
        }
        if (c == null) return true;
        return Math.abs(pos.getX() - c.getX()) <= radius
            && Math.abs(pos.getY() - c.getY()) <= radius
            && Math.abs(pos.getZ() - c.getZ()) <= radius;
    }

    /**
     * 读音符盒音高（0..24）。
     *
     * ★ 为什么不 import BlockStateProperties：此文件三版本共用（26.3 Fabric /
     *   26.3 NeoForge / 26.4 Fabric），直接 import 会在某个版本上「找不到符号」
     *   直接编译失败（实测三个版本都报了同一个错）。改成按类名字符串反射，
     *   类在不在都不影响编译，取不到再走遍历兜底。
     */
    private static int readNote(BlockState st) {
        if (st == null) return -1;
        // ① 反射读 BlockStateProperties.NOTE（NoteBlock.NOTE 作二次候选）
        Object prop = staticProp("net.minecraft.world.level.block.state.properties.BlockStateProperties", "NOTE");
        if (prop == null) prop = staticProp("net.minecraft.world.level.block.NoteBlock", "NOTE");
        if (prop != null) {
            Object v = invokeGetValue(st, prop);
            if (v instanceof Integer i) return i.intValue();
        }
        // ② 兜底：遍历该方块状态的所有属性，取第一个落在 0..24 的 Integer
        //    （乐器属性是枚举，不会与之混淆）
        try {
            for (Object p : st.getProperties()) {
                Object v = invokeGetValue(st, p);
                if (v instanceof Integer i && i >= 0 && i <= 24) return i;
            }
        } catch (Throwable t) {}
        return -1;
    }

    /** 反射取静态属性常量（Property<?>），取不到返回 null（不抛、不影响编译） */
    private static Object staticProp(String className, String fieldName) {
        try {
            Class<?> c = Class.forName(className);
            return c.getField(fieldName).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读乐器（NoteBlockInstrument）并映射成 NBS instrument id。
     * 属性名各版本不统一（NOTE_BLOCK_INSTRUMENT / INSTRUMENT），走反射兜底。
     */
    private static int readInstrument(BlockState st) {
        try {
            for (String cn : new String[]{
                    "net.minecraft.world.level.block.state.properties.BlockStateProperties",
                    "net.minecraft.world.level.block.NoteBlock"}) {
                Class<?> c;
                try { c = Class.forName(cn); } catch (Throwable t) { continue; }
                for (String fn : new String[]{"NOTE_BLOCK_INSTRUMENT", "INSTRUMENT"}) {
                    try {
                        Field f = c.getField(fn);
                        Object prop = f.get(null);
                        Object v = invokeGetValue(st, prop);
                        if (v != null) return toNbsInstrument(v);
                    } catch (Throwable t) {}
                }
            }
        } catch (Throwable t) {}
        return 0;   // 读不到按 HARP
    }

    /**
     * 反射调 BlockState.getValue(Property)。
     * ★ 不声明 throws：调用点（如 readNote）就直接用了返回值，
     *   声明受检异常会导致调用点必须 try/catch 或继续抛（实测三个版本都
     *   报「未报告的异常错误 Exception；必须对其进行捕获或声明以便抛出」）。
     *   这里内部吞掉并返回 null，与其他辅助方法风格一致。
     */
    private static Object invokeGetValue(BlockState st, Object prop) {
        try {
            Method gm = BlockState.class.getMethod("getValue", Property.class);
            return gm.invoke(st, prop);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeGetValue(BlockState st, String propFieldName) {
        Object prop = staticProp("net.minecraft.world.level.block.state.properties.BlockStateProperties", propFieldName);
        if (prop == null) prop = staticProp("net.minecraft.world.level.block.NoteBlock", propFieldName);
        if (prop == null) return null;
        try {
            return invokeGetValue(st, prop);
        } catch (Throwable t) {
            return null;
        }
    }

    /** NoteBlockInstrument → NBS instrument id（查 Note.NBS_V4，失败回退 0=HARP） */
    private static int toNbsInstrument(Object inst) {
        try {
            Object[] table = Note.NBS_V4;
            for (int i = 0; i < table.length; i++) {
                if (table[i] != null && table[i].equals(inst)) return i;
            }
            String nm = String.valueOf(inst);
            for (int i = 0; i < table.length; i++) {
                if (table[i] != null && String.valueOf(table[i]).equalsIgnoreCase(nm)) return i;
            }
        } catch (Throwable t) {}
        return 0;
    }

    private static String playerName() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                return mc.player.getName().getString();
            }
        } catch (Throwable t) {}
        return "";
    }

    // ---------------- 内部：包钩子（反射，平台无关） ----------------

    /**
     * 注册音符盒发声包监听。
     *
     * 为什么全反射：Fabric 用 ClientPlayNetworking、NeoForge 用事件总线，
     * 两边 API 完全不同且各版本签名还会变。这里用 Proxy 动态代理实现
     * handler 接口，编译期不绑定任何平台 API —— 注册失败也不影响编译，
     * 只是降级为"只录本 mod 自己发出的音"。
     */
    /**
     * 注册音符盒发声包监听（按优先级尝试，命中一个就停）。
     *
     * 为什么不再用 Fabric 的 ClientPlayNetworking（javap 实测结论）：
     *   registerGlobalReceiver(CustomPacketPayload$Type<T>, PlayPayloadHandler<T>)
     *   只接受【mod 自定义频道】的 payload，原版包根本不经过它 ——
     *   之前日志里「取不到 ClientboundBlockEventPacket.TYPE」就是这个原因
     *   （26.4 该类压根没有 TYPE 字段，只有 STREAM_CODEC + type() 方法）。
     *
     * 当前策略（★ 已不依赖 Mixin）：
     *   ① 服务端方块事件队列轮询（单人主力，无需 Mixin / 无需 Netty）。
     *   ② Netty 管道注入（联机客户端；单人无 socket 会自动失败）。
     *      在 MC 自己的 handler 之前插一个 inbound handler，拿到已解码的 Packet。
     *   ③ NeoForge 事件总线（尽力而为，各版本事件类名不稳定）。
     *   ④ 全失败 → 降级：只录本 mod 自身发出的音（自己弹琴 / MIDI 广播）。
     */
    private static void installHook() {
        if (hookTried) return;
        hookTried = true;

        // ① 服务端方块事件队列轮询（★ 无需 Mixin，单人世界主力路径）
        boolean queue = tryServerBlockEventQueue();

        // ② Netty 管道注入（联机客户端；单人无 socket 会自动失败）
        boolean netty = false;
        if (!queue) netty = tryNettyPipeline();

        // ③ NeoForge 事件总线（尽力而为）
        if (!queue && !netty) tryNeoForgeEventBus();

        if (!hookOk) {
            Main.LOGGER.warn("[DJ] 音符盒包监听未注册成功；"
                    + "录制仍可用，但只会记录本 mod 自身发出的音符（自己弹琴 / MIDI 广播）");
        }
    }

    // ==================== 服务端方块事件队列轮询（★ 无需 Mixin） ====================

    /**
     * ★ 单人世界里，服务端与客户端在同一个 JVM，所以不用钩网络也能拿到全部发声。
     *
     * 原理：音符盒被敲 → 服务端 Level.blockEvent() 往内部的 BlockEventData 队列
     *       塞一条 → 下一个 tick 消费并【广播给客户端】。
     *       我们从 Minecraft.getSingleplayerServer() 拿到 ServerLevel，
     *       高频（4ms）轮询这个队列，就能在原版广播之前捕获到每一次发声。
     *
     * 优点：
     *   - 不需要 Mixin（不用碰 disc_jockey.mixins.json，不会因注入失配而崩）
     *   - 不需要 Netty（单人没有 socket，Netty 方案在单人下必然失败）
     *   - 纯反射【只读】，不改任何 MC 对象，风险为零
     *   - 三平台通用（都是原版 Level 类）
     *
     * 局限：只在单人（含 LAN 主机）有效；
     *       联机客户端没有 ServerLevel，退回 Netty 路径。
     */
    private static boolean tryServerBlockEventQueue() {
        Object level = resolveServerLevel();
        if (level == null) {
            Main.LOGGER.info("[DJ] 单人服务端 Level 不可用（应为联机客户端），跳过队列轮询");
            return false;
        }
        Field qf = resolveQueueField(level);
        if (qf == null) {
            Main.LOGGER.warn("[DJ] 找不到方块事件队列字段，跳过队列轮询");
            return false;
        }
        queueMode = true;
        hookOk = true;
        Main.LOGGER.info("[DJ] 音符盒包监听已注册（服务端方块事件队列轮询，无需 Mixin）");
        return true;
    }

    /** 取单人世界的 ServerLevel（Minecraft.getSingleplayerServer() → overworld） */
    private static Object resolveServerLevel() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return null;

            Object server = null;
            for (String mn : new String[]{"getSingleplayerServer"}) {
                try {
                    server = mc.getClass().getMethod(mn).invoke(mc);
                    if (server != null) break;
                } catch (Throwable ignored) { }
            }
            if (server == null) {
                for (String fn : new String[]{"singleplayerServer", "integratedServer", "server"}) {
                    try {
                        Field f = mc.getClass().getDeclaredField(fn);
                        f.setAccessible(true);
                        server = f.get(mc);
                        if (server != null) break;
                    } catch (Throwable ignored) { }
                }
            }
            if (server == null) return null;

            for (String mn : new String[]{"overworld", "getOverworld"}) {
                try {
                    Object l = server.getClass().getMethod(mn).invoke(server);
                    if (l != null) return l;
                } catch (Throwable ignored) { }
            }
            for (String mn : new String[]{"getAllLevels", "getLevels"}) {
                try {
                    Object r = server.getClass().getMethod(mn).invoke(server);
                    if (r instanceof Iterable<?>) {
                        for (Object o : (Iterable<?>) r) if (o != null) return o;
                    }
                } catch (Throwable ignored) { }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在 ServerLevel 里找"存放 BlockEventData 的 List/Collection 字段"。
     * 不写死字段名（各版本命名不同），靠【泛型签名】匹配，空列表也能命中。
     */
    private static Field resolveQueueField(Object level) {
        if (queueFieldCache != null) return queueFieldCache;
        List<String> candidates = new ArrayList<>();
        Class<?> c = level.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    String g = f.getGenericType().getTypeName();
                    String fn = f.getName().toLowerCase();
                    String tn = f.getType().getTypeName();
                    boolean hit = (g != null && g.contains("BlockEvent"))
                               || fn.contains("blockevent")
                               || (tn != null && tn.contains("BlockEvent"));
                    if (!hit) continue;
                    // 只接受能装元素的容器（List/Set/Queue/Collection）
                    if (!java.util.Collection.class.isAssignableFrom(f.getType())) continue;
                    candidates.add(c.getSimpleName() + "." + f.getName() + " : " + g);
                    f.setAccessible(true);
                    queueFieldCache = f;
                    statQueueField = c.getSimpleName() + "." + f.getName();
                    Main.LOGGER.info("[DJ] 方块事件队列字段命中：{}（候选 {} 个：{}）",
                            statQueueField, candidates.size(), candidates);
                    return f;
                } catch (Throwable ignored) { }
            }
            c = c.getSuperclass();
        }
        Main.LOGGER.warn("[DJ] 未找到方块事件队列字段（level 类={}）",
                level == null ? "null" : level.getClass().getName());
        return null;
    }

    /** 启动轮询线程（录制期间持续跑，recording=false 后自然退出） */
    private static void ensurePollThread() {
        if (!queueMode) return;
        if (queuePollThread != null && queuePollThread.isAlive()) return;
        queuePollThread = new Thread(() -> {
            while (recording) {
                try {
                    drainQueue();
                    Thread.sleep(QUEUE_POLL_MS);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) { }
            }
        }, "DJ-BlockEventPoll");
        queuePollThread.setDaemon(true);
        queuePollThread.start();
    }

    /**
     * 取一次队列快照并逐条交给 onNoteBlockEvent（内部判半径 + 读方块状态）。
     * ★ 遍历【所有】服务端维度，不只 overworld —— 玩家在下界/末地时也要能录到。
     */
    private static void drainQueue() {
        try {
            statPolls++;
            for (Object level : resolveAllServerLevels()) {
                drainOneLevel(level);
            }
        } catch (Throwable ignored) { }
    }

    private static void drainOneLevel(Object level) {
        try {
            if (level == null) return;
            Field qf = resolveQueueField(level);
            if (qf == null) return;

            Object v = qf.get(level);
            if (!(v instanceof java.util.Collection<?> col)) return;

            Object[] snap;
            synchronized (col) {
                if (col.isEmpty()) {
                    seenEvents.clear();      // 队列已消费完 → 清空去重集，避免无限增长
                    return;
                }
                statQueueNonEmpty++;
                snap = col.toArray();
            }
            if (seenEvents.size() > 4096) seenEvents.clear();

            for (Object o : snap) {
                if (o == null) continue;
                // ★ 按对象身份去重：同一条 BlockEventData 在 tick 内会被反复轮询到
                if (!seenEvents.add(o)) continue;

                Object pos = null;
                for (String mn : new String[]{"pos", "getPos"}) {
                    try {
                        pos = o.getClass().getMethod(mn).invoke(o);
                        if (pos != null) break;
                    } catch (Throwable ignored) { }
                }
                if (pos == null) {
                    // 26.3 部分版本是 public final 字段而非 getter，再兜一次
                    for (String fn : new String[]{"pos", "position"}) {
                        try {
                            java.lang.reflect.Field ff = o.getClass().getDeclaredField(fn);
                            ff.setAccessible(true);
                            pos = ff.get(o);
                            if (pos != null) break;
                        } catch (Throwable ignored) { }
                    }
                }
                if (pos instanceof BlockPos bp) {
                    statEvents++;
                    // capture() 内部会判半径 + 读音符盒状态，非音符盒事件自动忽略
                    onNoteBlockEvent(bp, System.nanoTime());
                }
            }
        } catch (Throwable ignored) { }
    }

    /** 所有服务端维度（overworld 优先，其次 getAllLevels 的全部） */
    private static List<Object> resolveAllServerLevels() {
        List<Object> out = new ArrayList<>();
        Object ow = resolveServerLevel();
        if (ow != null) out.add(ow);
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return out;
            Object server = null;
            try { server = mc.getClass().getMethod("getSingleplayerServer").invoke(mc); }
            catch (Throwable ignored) { }
            if (server == null) return out;
            for (String mn : new String[]{"getAllLevels", "getLevels"}) {
                try {
                    Object r = server.getClass().getMethod(mn).invoke(server);
                    if (r instanceof Iterable<?>) {
                        for (Object o : (Iterable<?>) r) {
                            if (o != null && !out.contains(o)) out.add(o);
                        }
                    }
                    break;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * ★ Netty 管道注入（三平台通用，NeoForge 没有 Mixin 时的唯一可行路径）
     *
     * MC 客户端 pipeline（inbound 方向）：
     *   ... -> decoder（字节→Packet 对象） -> handler（MC 处理） -> ...
     * 我们用 addBefore("handler", ...) 插在 MC 的 handler 之前，
     * 拿到的已经是【解码后的 Packet 对象】，直接判类名即可。
     * 若找不到名为 "handler" 的 handler（各版本命名不同），退回 addLast ——
     * inbound 事件仍会流过，只是顺序在 MC 之后，功能一样。
     *
     * 安全性：只转发消息、不做任何拦截改写，不会影响正常收包。
     */
    private static boolean tryNettyPipeline() {
        try {
            Class.forName("io.netty.channel.ChannelInboundHandlerAdapter");
        } catch (Throwable t) {
            Main.LOGGER.warn("[DJ] Netty 不可用，跳过管道注入");
            return false;
        }
        try {
            Object conn = getConnectionObj();
            if (conn == null) {
                Main.LOGGER.warn("[DJ] 取不到客户端 connection，跳过管道注入");
                return false;
            }
            Object channel = findFieldByTypeName(conn, "io.netty.channel.Channel");
            if (channel == null) {
                Main.LOGGER.warn("[DJ] 取不到 netty Channel，跳过管道注入");
                return false;
            }
            Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
            if (pipeline == null) return false;

            // 用反射实例化，避免 netty 缺失时主类被验证器连带报错
            Object hook = Class.forName("semmiedev.disc_jockey.NbsRecorder$NettyHook")
                    .getDeclaredConstructor().newInstance();
            Class<?> handlerIface = Class.forName("io.netty.channel.ChannelHandler");

            try {
                pipeline.getClass()
                        .getMethod("addBefore", String.class, String.class, handlerIface)
                        .invoke(pipeline, "handler", "dj_nbs_recorder", hook);
            } catch (Throwable t) {
                // 没有名为 handler 的 handler → 挂到最后
                pipeline.getClass()
                        .getMethod("addLast", String.class, handlerIface)
                        .invoke(pipeline, "dj_nbs_recorder", hook);
            }
            hookOk = true;
            Main.LOGGER.info("[DJ] 音符盒包监听已注册（Netty pipeline）");
            return true;
        } catch (Throwable t) {
            Main.LOGGER.warn("[DJ] Netty 管道注入失败：{}", String.valueOf(t));
            return false;
        }
    }

    /**
     * Netty 入站钩子。
     * ★ 独立嵌套类：只有当 tryNettyPipeline 探测到 netty 存在、并显式
     *   Class.forName 加载它时才会被加载，netty 缺失时不会 NoClassDefFoundError。
     */
    static final class NettyHook extends io.netty.channel.ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                NbsRecorder.onPacketObject(msg);
            } catch (Throwable ignored) { }
            super.channelRead(ctx, msg);   // ★ 原样转发，绝不拦截
        }
    }

    /** 客户端 connection 对象（反射兼容 26.3 / 26.4 方法名差异） */
    private static Object getConnectionObj() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return null;
            try {
                Method m = mc.getClass().getMethod("getConnection");
                Object v = m.invoke(mc);
                if (v != null) return v;
            } catch (Throwable t) { }
            Class<?> c = mc.getClass();
            while (c != null) {
                for (String fn : new String[]{"connection", "packetListener",
                                              "clientPacketListener"}) {
                    try {
                        Field f = c.getDeclaredField(fn);
                        f.setAccessible(true);
                        Object v = f.get(mc);
                        if (v != null) return v;
                    } catch (Throwable t) { }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) { }
        return null;
    }

    /** 在对象（含父类）里找类型名等于/派生于 typeName 的字段值 */
    private static Object findFieldByTypeName(Object o, String typeName) {
        Class<?> want;
        try { want = Class.forName(typeName); } catch (Throwable t) { return null; }
        Class<?> c = o.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (!want.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v != null) return v;
                } catch (Throwable t) { }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * ★ 包入口：由 NettyHook（三平台）或 NoteBlockEventMixin（Fabric）调用。
     * 只认 ClientboundBlockEventPacket，其余包直接忽略。
     */
    public static void onPacketObject(Object packet) {
        if (!recording || packet == null) return;
        try {
            if (!packet.getClass().getName().contains("ClientboundBlockEventPacket")) return;
            BlockPos bp = extractBlockPos(packet);
            if (bp != null) onNoteBlockEvent(bp, System.nanoTime());
        } catch (Throwable ignored) { }
    }

    private static boolean tryNeoForgeEventBus() {
        Object bus = null;
        try {
            Class<?> nf = Class.forName("net.neoforged.neoforge.common.NeoForge");
            java.lang.reflect.Field f = nf.getField("EVENT_BUS");
            bus = f.get(null);
        } catch (Throwable t) {
            return false;   // 不是 NeoForge
        }
        String[] eventCandidates = new String[]{
                "net.neoforged.neoforge.client.event.ClientPacketEvent",
                "net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent",
                "net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent",
        };
        for (String ecn : eventCandidates) {
            Class<?> evCls;
            try { evCls = Class.forName(ecn); } catch (Throwable t) { continue; }
            try {
                Object proxy = Proxy.newProxyInstance(
                        NbsRecorder.class.getClassLoader(),
                        new Class<?>[]{ java.util.function.Consumer.class },
                        (p, method, args) -> {
                            try {
                                if (args != null && args.length > 0 && args[0] != null) {
                                    BlockPos bp = extractBlockPos(args[0]);
                                    if (bp == null) bp = extractBlockPosFromPacketField(args[0]);
                                    if (bp != null) onNoteBlockEvent(bp, System.nanoTime());
                                }
                            } catch (Throwable t) {}
                            return null;
                        });
                Method add = null;
                for (Method m : bus.getClass().getMethods()) {
                    if (m.getName().equals("addListener") && m.getParameterCount() == 1) { add = m; break; }
                }
                if (add == null) continue;
                add.invoke(bus, proxy);
                hookOk = true;
                Main.LOGGER.info("[DJ] 音符盒包监听已注册（NeoForge {}）", ecn);
                return true;
            } catch (Throwable t) {
                Main.LOGGER.warn("[DJ] NeoForge {} 注册失败：{}", ecn, String.valueOf(t));
            }
        }
        return false;
    }

    /** 事件对象里可能把包藏在 getPacket() 之类的方法里，再挖一层 */
    private static BlockPos extractBlockPosFromPacketField(Object ev) {
        try {
            for (Method m : ev.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName().toLowerCase();
                if (!(n.contains("packet") || n.contains("payload"))) continue;
                Object v = m.invoke(ev);
                if (v == null) continue;
                BlockPos bp = extractBlockPos(v);
                if (bp != null) return bp;
            }
        } catch (Throwable t) {}
        return null;
    }

    /** 用动态代理实现 handler 接口：从任意实参里捞出 BlockPos 就触发采集 */
    private static Object makeHandlerProxy(Class<?> iface) {
        try {
            return Proxy.newProxyInstance(
                    NbsRecorder.class.getClassLoader(),
                    new Class<?>[]{ iface },
                    (proxy, method, args) -> {
                        try {
                            if (args != null) {
                                for (Object a : args) {
                                    if (a == null) continue;
                                    BlockPos bp = extractBlockPos(a);
                                    if (bp != null) {
                                        onNoteBlockEvent(bp, System.nanoTime());
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable t) { /* 采集合法失败不打断原包处理 */ }
                        return defaultValue(method.getReturnType());
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从包对象里找 BlockPos（无参 + 返回类型名以 BlockPos 结尾） */
    private static BlockPos extractBlockPos(Object o) {
        try {
            for (Method m : o.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getReturnType().getName().endsWith("BlockPos")) continue;
                Object v = m.invoke(o);
                if (v instanceof BlockPos bp) return bp;
            }
        } catch (Throwable t) {}
        return null;
    }

    private static Object defaultValue(Class<?> rt) {
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == void.class) return null;
        if (rt == int.class) return Integer.valueOf(0);
        if (rt == long.class) return Long.valueOf(0L);
        if (rt == float.class) return Float.valueOf(0F);
        if (rt == double.class) return Double.valueOf(0D);
        if (rt == short.class) return Short.valueOf((short) 0);
        if (rt == byte.class) return Byte.valueOf((byte) 0);
        if (rt == char.class) return Character.valueOf((char) 0);
        return null;
    }
}
