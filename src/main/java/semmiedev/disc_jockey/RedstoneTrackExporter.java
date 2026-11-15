package semmiedev.disc_jockey;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * DJ_LAYERED —— 严格按用户给的 ASCII 结构生成，默认就是立体多层（不再铺平）。
 *
 *  R 红石粉   P 中继器(正, 默认 west)   Q 中继器(反, 默认 east)
 *  B 石头按钮 N 音符盒                 G 玻璃
 *  S 会下落的音色块(sand/glass)        K 辅助方块(石头)
 *
 * 图1/3 一层俯视:  RRNPNPNPNPNP —— X=时间(帧位)，Z=音轨；起始端母线一列 R，
 *      轨间行只在该列有单点 R，X>=1 区域各轨平行、绝不互连
 * 图2  同帧:        粉后必中继器，恒 N/P 交替，禁止 RNRNRN；空帧位用 K 占位保持链连续
 * 图4  上下层:      偶数层左进右出(P)、奇数层右进左出(Q)；出端玻璃阶梯
 *      上升 4 格接下一层同端母线中点；阶梯全在音轨区外的专用 X 列，全程 G+R 不插中继器。
 *      所有 setblock 的红石粉一律 power=0；"D/充能红石"只是运行期状态，绝不写进方块状态。
 *      接口俯视 AGA（两侧空气 A，中间玻璃 G），侧视 RA / GR / RG / GR / RG；绝不留 K 硌 R 的脑袋。
 * 图5  支撑:        每格下方 K；sand/glass 类音色块下方再补 G 防止掉落
 * B:                只在 lv=0 起始排母线的轨间行正中间；母线从中心往外，
 *      第一个续电点在 dj.feedFirst(默认 6)、第二个及以后每 dj.feedSpan(默认 10) 一个，
 *      放 L(朝左) / I(朝右) 中继器续电：R15...R5 -> L/I -> R15，绝不衰减到 R0；
 *      中继器【之前】的轨在首个 P 上多加 (kMax-kRep) tick 抵消 L/I 的 delay，
 *      保证同帧不同音轨仍然齐响
 * 入端桥(lv>=1):    本层整层下垫与母线循环会把上一层阶梯的 G/R 刷回 K/R，
 *      跨层接口在全部层生成结束后统一覆盖，避免后写层把先写层刷坏
 *
 * 时间轴按片切给各层 -> dX≈dY；同帧所有轨共用同一帧位与同一 delay
 * -> 同一时刻不同音轨齐响，不同 tick 沿 X 依次响。
 * DJ_V6: ① 删掉所有 redstone_block（常亮电源=天然激活）；tp 只由石头按钮的
 *      脉冲在【播放开始那一刻】触发一次，命令方块放在按钮正下方，零常亮、零新增红石粉。
 *      每层中心 A 行的命令方块保留作占位器(auto:0b，无触发源，不会自己激活)。
 *      ② 转层 delay 改为 d-kRep：母线传播已过 kRep 档 L/I，层首 P 补回 d-kRep，
 *      使「母线传播 + 层首 P」= 层内帧间 d，不顿也不抢拍。不再叠 (kMax-kRep) 或常数扣减。
 */
public final class RedstoneTrackExporter {
    private RedstoneTrackExporter() {}

    public static boolean SPLIT_BY_LAYER = true;   // 兼容旧调用

    private static final String[] INST_BLOCK = {
        "minecraft:dirt", "minecraft:oak_planks", "minecraft:stone", "minecraft:sand",
        "minecraft:glass", "minecraft:white_wool", "minecraft:clay", "minecraft:gold_block",
        "minecraft:packed_ice", "minecraft:bone_block", "minecraft:iron_block",
        "minecraft:soul_sand", "minecraft:pumpkin", "minecraft:emerald_block",
        "minecraft:hay_block", "minecraft:glowstone", "minecraft:copper_block",
        "minecraft:exposed_copper", "minecraft:weathered_copper", "minecraft:oxidized_copper"
    };

    private static final String K = "minecraft:stone";
    private static final String G = "minecraft:glass";
    private static final String A = "minecraft:air";

    /* 粉只连需要的轴：绝不再出现四面全连把音轨之间织成网 */
    private static final String WIRE_Z =   // 母线：只连 Z（轨间单点）
        "minecraft:redstone_wire[power=0,east=none,west=none,north=side,south=side]";
    private static final String WIRE_X =   // 轨内首格：只连 X
        "minecraft:redstone_wire[power=0,east=side,west=side,north=none,south=none]";
    private static final String WIRE_J =   // 母线与轨的交点：连 Z + 连轨(east)
        "minecraft:redstone_wire[power=0,east=side,west=none,north=side,south=side]";
    private static final String WIRE_S =   // 阶梯竖井：让游戏按高度差自行判定 up/side
        "minecraft:redstone_wire[power=0,east=side,west=side,north=none,south=none]";

    /* 重要：D = 运行期"充能的红石"，不是可写进 setblock 的方块状态。
       所有红石粉一律 power=0 放置；亮不亮交给游戏按邻接信号源重算。 */
    private static final String WIRE_R_E =   // 塔在 +X 侧：连塔 + 连上下两格 R
        "minecraft:redstone_wire[power=0,east=side,west=none,north=side,south=side]";
    private static final String WIRE_R_W =   // 塔在 -X 侧
        "minecraft:redstone_wire[power=0,east=none,west=side,north=side,south=side]";
    private static final String WIRE_UP_R =  // 塔内逐级：power=0，只连 X 两向
        "minecraft:redstone_wire[power=0,east=side,west=side,north=none,south=none]";
    /* 入端桥专用：只在母线列(xIn)的 zMid 一格使用，绝不进音轨区。
       power=0；既要连塔/上下母线(north/south)，又要保留连轨首(east 或 west)。 */
    private static final String WIRE_R_BUS =
        "minecraft:redstone_wire[power=0,east=side,west=side,north=side,south=side]";

    /* 图4：正 / 反 中继器朝向，可用 JVM 参数覆盖，避免来回翻烧饼 */
    private static final String FACE_POS = System.getProperty("dj.posFacing", "west");
    private static final String FACE_NEG = System.getProperty("dj.negFacing", "east");
    /* 母线续电：L = z<zMid 一侧(朝左)，I = z>zMid 一侧(朝右)。
       按已实测的「facing 记输入端」语义：往 +Z 走要 facing=north，往 -Z 走要 facing=south。
       可用 -Ddj.leftFacing / -Ddj.rightFacing 翻转，不用重新 build。 */
    private static final String FACE_LEFT  = System.getProperty("dj.leftFacing",  "south");
    private static final String FACE_RIGHT = System.getProperty("dj.rightFacing", "north");
    private static final String WIRE_J_W =   // 入端在左：连 Z，并往 +X 接轨首
        "minecraft:redstone_wire[power=0,east=side,west=none,north=side,south=side]";
    private static final String WIRE_J_E =   // 入端在右：连 Z，并往 -X 接轨首
        "minecraft:redstone_wire[power=0,east=none,west=side,north=side,south=side]";

    public static File exportSong(Song song) throws IOException {
        Result r = build(song);
        File dir = new File("config/disc_jockey/redstone");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        String nm = str(song, "fileName");
        if (nm == null || nm.isEmpty()) nm = str(song, "name");
        File out = new File(dir, safeName(nm) + ".mcfunction");
        Files.writeString(out.toPath(), r.commands, StandardCharsets.UTF_8);
        placeInWorld(r.commands);       // 一键铺进当前世界
        return out;
    }

    /**
     * 默认就是立体：把图1/3 的平面块按图4 沿 Y 叠 layers 层。
     * X = 时间(帧位)，Z = 音轨，Y = 时间片。
     * 偶数层：左端母线进 -> +X (P, FACE_POS) -> 右端出；
     * 奇数层：右端母线进 -> -X (Q, FACE_NEG) -> 左端出；
     * 出端即下一层入端，所以玻璃阶梯只在音轨区外的专用 X 列上升，绝不穿音轨。
     * 同帧的所有轨共用同一帧位与同一 delay -> 同一时刻不同音轨齐响。
     */
    public static Result build(Song song) {
        long[] a = (long[]) val(song, "notes");
        if (a == null || a.length == 0) a = (long[]) val(song, "foldedNotes");
        if (a == null) a = new long[0];
        List<N> all = new ArrayList<>();
        for (long p : a) all.add(new N(
            (int)(p & 0xFFFFL), (int)((p >>> 16) & 0xFFFFL),
            (int)((p >>> 32) & 0xFFL), Note.extractNoteId(p)));
        if (all.isEmpty()) throw new IllegalStateException("no notes to export");

        int tempo = intOf(song, "tempo", 1000);
        if (tempo <= 0) tempo = 1000;
        final double msPerTickD = 100000.0 / tempo;
        Integer tickOverride = Integer.getInteger("dj.tickUnits");
        int units = (tickOverride != null) ? Math.max(1, tickOverride)
                : Math.max(1, (int) Math.round(msPerTickD / 100.0));   // 仅显示 / 手动覆盖
        // 历史: V4 把 lv>=1 的 (kMax-kRep) 母线续电补偿整个删掉再扣 1 档，
        // 轨数多时层首一下快 3~5 档 -> 切层抢拍。这里恢复补偿，只扣 bridgeComp。
        // 0=回到 V3(略顿)  1=默认(比 V3 快 1 档)  2/3=更快  -1/-2=补回更慢。
        // DJ_V6: 仅作微调旋钮，默认 0（物理正确值由 d-kRep 得出）
        int bridgeComp = Math.max(-3, Math.min(3, Integer.getInteger("dj.bridgeComp", 0)));
        // DJ_V6: 0=关 1=按钮脉冲触发一次(默认) 2=同 1（保留兼容）
        int centerTp = Math.max(0, Math.min(2, Integer.getInteger("dj.centerTp", 1)));
        List<Object> customs = customs(song);
        int vc = vanillaCount(song);

        // 全局帧序列 + 音轨序列；同帧同轨理论上只有一个音符（图2）
        TreeMap<Integer, TreeMap<Integer, N>> grid = new TreeMap<>();
        TreeSet<Integer> laneSet = new TreeSet<>();
        for (N n : all) {
            laneSet.add(n.layer);
            grid.computeIfAbsent(n.tick, k -> new TreeMap<>()).put(n.layer, n);
        }
        int F = grid.size();
        int[] frames = new int[F];
        { int i = 0; for (int k : grid.keySet()) frames[i++] = k; }
        int L = laneSet.size();
        int[] laneIds = new int[L];
        { int i = 0; for (int k : laneSet) laneIds[i++] = k; }

        // 每片帧数：让 dX≈dY（4*levels ≈ 2T+4），也支持 -Ddj.slice / -Ddj.side 强制
        int T = Integer.getInteger("dj.slice", 0);
        if (T <= 0) {
            int side = Integer.getInteger("dj.side", 0);
            if (side > 0) T = Math.max(2, (side - 4) / 2);
            else T = Math.max(2, (int) Math.round(Math.sqrt(2.0 * F)));
        }
        T = Math.max(1, Math.min(T, F));
        int levels = (F + T - 1) / T;
        int W = 2 * T + 2;                 // 右端母线列
        int ZC = 2 * L;                    // 阶梯/回送专用 Z 行（在所有音轨之外）
        int M = all.size();

        List<String> cmds = new ArrayList<>();
        cmds.add("# DJ_V6_PULSE_TP  notes=" + M + "  frames=" + F + "  lanes=" + L
            + "  slice=" + T + "  levels=" + levels
            + "  dX=" + (W + 2) + " dY=" + (4 * levels) + " dZ=" + (ZC + 1)
            + "  tempo=" + tempo + " units=" + units
            + "  pitchBase=12(note24->2.0,noOctaveFold)  bridgeComp=" + bridgeComp
            + " centerTp=" + centerTp + " pulse=button");

        // 续电点分布：第一个离中点近(first)，第二个更远(first+span)，之后每 span 一个。
        // 任意相邻续电点间距 <= span(默认 10)，母线最低能量 15-10=5，绝不再出现 R0。
        int first = Math.max(2, Integer.getInteger("dj.feedFirst", 6));
        if ((first & 1) == 1) first++;            // 必须偶数：轨间行是奇数 z
        int span = Math.max(4, Integer.getInteger("dj.feedSpan", 10));
        if ((span & 1) == 1) span++;
        int zMid = 2 * (L / 2) + 1;              // 母线中点(轨间行)：按钮与阶梯共用
        for (int lv = 0; lv < levels; lv++) {
            int Y = 4 * lv;                       // 支撑层 Y / 轨面 Y+1 / 发声留空 Y+2
            boolean rev = (lv % 2 == 1);          // 图4：上 P 下 Q
            String face = rev ? FACE_NEG : FACE_POS;
            int xIn  = rev ? W : 0;               // 入端母线列
            int xOut = rev ? 0 : W;               // 出端（= 下一层入端）
            int sOut = xOut == 0 ? -1 : 1;        // 阶梯错开方向，恒在音轨区外
            int xFirst = rev ? W - 1 : 1;         // 轨首 R
            int from = lv * T, to = Math.min(F, from + T);

            // ---- 图4/图5：整层下垫 K ----
            for (int x = 0; x <= W; x++)
                for (int z = 0; z <= ZC; z++) cmds.add(sb(x, Y, z, K));

            // ---- 起始端母线：轨行 WIRE_J，轨间行 WIRE_Z；每 span 格用中继器续电 ----
            // 纯粉母线从中心往外会衰减到 0（R15..R0），所以只在【母线列的轨间行】
            // 放朝北/朝南的中继器补能；X>=1 的音轨区一列粉都不加，绝不穿轨。
            String j = rev ? WIRE_J_E : WIRE_J_W;
            for (int z = 0; z <= ZC; z++) {
                boolean lane = (z < ZC && (z & 1) == 0);
                if (isFeed(z, zMid, first, span)) {
                    // 用户规格：R15 ... R1 -> L / I 中继器 -> 重置 R15
                    String fz = (z < zMid) ? FACE_LEFT : FACE_RIGHT;
                    cmds.add(sb(xIn, Y + 1, z,
                        "minecraft:repeater[facing=" + fz + ",delay=1]"));
                } else {
                    cmds.add(sb(xIn, Y + 1, z, lane ? j : WIRE_Z));
                }
            }
            if (lv == 0) {
                // 起始排正中间（轨间行，不覆盖任何轨首交点）
                cmds.add(sb(xIn, Y + 1, zMid,
                    "minecraft:stone_button[face=floor,facing=east]"));
            }

            // ---- 入端桥（lv>=1）：接口只保留 AGA，不预置充能 D ----
            // 上一层玻璃阶梯末级 R 落在本层 (xIn, Y+1, zMid)，但本层下垫/母线循环会刷回 K/R。
            // 这里只修接口：塔侧支撑列俯视 AGA，顶格一律 power=0 的 R；侧列上方空气 A。
            if (lv > 0) {
                int sIn = (xIn == 0) ? -1 : 1;
                for (int zd = zMid - 1; zd <= zMid + 1; zd++) {
                    if (zd < 0 || zd > ZC) continue;
                    boolean mid = (zd == zMid);
                    cmds.add(sb(xIn,       Y,     zd, mid ? G : K));
                    cmds.add(sb(xIn + sIn, Y,     zd, mid ? G : A));
                    cmds.add(sb(xIn,       Y + 1, zd, mid ? WIRE_R_BUS : WIRE_Z));
                    cmds.add(sb(xIn + sIn, Y + 1, zd, A));
                }
            }

            // ---- 各轨：RRNPNPNPNPNP（图1/3 + 图2） ----
            // 全层经过续电中继器最多的轨 -> 作为补偿基准
            int kMax = 0;
            for (int r = 0; r < L; r++) {
                int z = 2 * r, k = 0;
                for (int zz = Math.min(z, zMid) + 1; zz < Math.max(z, zMid); zz++)
                    if (isFeed(zz, zMid, first, span)) k++;
                kMax = Math.max(kMax, k);
            }
            for (int r = 0; r < L; r++) {
                int z = 2 * r;
                // 该轨从按钮出发经过几个续电中继器；经过越多越晚到。
                // 用户规格：主线中继器【之前】的轨要多达一个 tick 抵消 L/I 的 delay。
                int kRep = 0;
                for (int zz = Math.min(z, zMid) + 1; zz < Math.max(z, zMid); zz++)
                    if (isFeed(zz, zMid, first, span)) kRep++;
                cmds.add(sb(xFirst, Y + 1, z, WIRE_X));       // 第二个 R
                for (int k = 0; k < T; k++) {
                    int g = from + k;
                    if (g >= F) break;
                    int nx = rev ? (W - 2 - 2 * k) : (2 + 2 * k);
                    int px = rev ? (W - 3 - 2 * k) : (3 + 2 * k);
                    TreeMap<Integer, N> m = grid.get(frames[g]);
                    N n = (m == null) ? null : m.get(laneIds[r]);
                    if (n != null) emitNote(cmds, n, nx, Y, z, customs, vc);
                    else {                                   // 空帧位：K 占位，链不断
                        cmds.add(sb(nx, Y, z, K));
                        cmds.add(sb(nx, Y + 1, z, K));
                    }
                    int gap = (g + 1 < F) ? Math.max(1, frames[g + 1] - frames[g]) : 1;
                    int d = (tickOverride != null)
                            ? Math.max(1, Math.min(4, gap * units))
                            : Math.max(1, Math.min(4, (int) Math.round(gap * msPerTickD / 100.0)));
                    if (k == 0) {
                        if (lv == 0) {
                            d = Math.min(4, d + (kMax - kRep));   // 开头维持原样（用户未抱怨开头）
                        } else {
                            // DJ_V6: 转层时间必须【等于层内帧间时间】。
                            // 母线从 zMid 往外已过 kRep 档 L/I 中继器(kRep 档 = 2*kRep gt)，
                            // 层首 P 补 d-kRep，使总延迟 = d，与层内任意帧边界完全一致。
                            // V3/V5 叠 (kMax-kRep) 让整层慢 kMax 档 -> 顿；V4 删补偿 -> 抢拍。
                            d = Math.max(1, Math.min(4, d - kRep - bridgeComp));
                        }
                    }
                    cmds.add(sb(px, Y + 1, z,
                        "minecraft:repeater[facing=" + face + ",delay=" + d + "]"));
                }
            }

            // ---- 接近层中心的 A 行放命令方块占位器（auto:0b，无触发源，不自己激活）----
            // zMid 是轨间 A 行，Y+1 该行全层为空；xC 取层中部，远离母线列与接口列，
            // 不写任何红石粉，PR/ARU/PR 接口与玻璃阶梯一律不动。
            if (centerTp != 0) {
                int xC = centerX(W);
                cmds.add(sb(xC, Y, zMid, K));                 // 支撑（整层下垫已有 K，重申保证）
                cmds.add(sb(xC, Y + 1, zMid, "minecraft:command_block[facing=up]"));
                cmds.add("data merge block ~" + xC + " ~" + (Y + 1) + " ~" + zMid
                    + " {Command:\"tp @a[distance=..100] ~ ~ ~\",auto:0b}");
            }

            // 跨层玻璃阱统一在全部层生成结束后写入 emitRisers()，
            // 否则下一层的整层下垫/母线循环会把上一层阶梯刷回 K/R。
        }

        // 最后统一覆盖所有跨层接口：避免后写层覆盖先写层，且所有红石粉保持 power=0。
        emitRisers(cmds, levels, W, ZC, zMid);
        // 接口全部层最终绝对覆盖：第二列三行恒为 power=0 的 R -> PR / ARU / PR
        emitJoints(cmds, levels, W, ZC, zMid);
        // DJ_V6: 按钮脉冲触发一次 tp；无红石块、无常亮、无 repeating。
        emitCenterTp(cmds, levels, W, zMid, centerTp);

        String info = "DJ_V6_PULSE_TP  notes=" + M + "  frames=" + F + "  lanes=" + L
            + "  slice=" + T + "  levels=" + levels
            + "  dX=" + (W + 2) + " dY=" + (4 * levels) + " dZ=" + (ZC + 1)
            + "  tempo=" + tempo + "  units=" + units
            + "  trigger=stone_button(middle of lv0 bus)";
        return new Result(String.join("\n", cmds) + "\n", info);
    }

    /**
     * 全部层生成结束后统一覆盖跨层接口。
     * 俯视：塔侧列 AGA（两侧 A、中间 G），外侧列 A，不再 GGG。
     * 侧视：RA / GR / RG / GR / RG；所有红石粉 power=0，D 只在运行期出现。
     */
    private static void emitRisers(List<String> out, int levels, int W, int ZC, int zMid) {
        for (int lv = 0; lv + 1 < levels; lv++) {
            int Y = 4 * lv;
            boolean rev = (lv % 2 == 1);
            int xOut = rev ? 0 : W;
            int sOut = (xOut == 0) ? -1 : 1;
            int x0 = xOut;
            int x1 = xOut + sOut;
            int x2 = xOut + 2 * sOut;

            // 俯视 AGA：塔侧支撑列两侧清空，中央保留玻璃；外侧列全部清空。
            for (int zd = zMid - 1; zd <= zMid + 1; zd++) {
                if (zd < 0 || zd > ZC) continue;
                boolean mid = (zd == zMid);
                out.add(sb(x1, Y,     zd, mid ? G : A));   // 支撑层仍 AGA，玻璃一个不动
                out.add(sb(x2, Y,     zd, A));
                out.add(sb(x1, Y + 5, zd, A));
                out.add(sb(x2, Y + 5, zd, A));
                if (!mid) {
                    out.add(sb(x0, Y,     zd, K));          // 母线侧行支撑，不再玻璃 GGG
                    out.add(sb(x0, Y + 5, zd, WIRE_Z));     // 下一层母线侧行，power=0
                    // 中继器的头：本层轨面 (x0, Y+1, zMid±1) 原本是空气 A，
                    // 导致音轨末端 P 的输出端接不上 -> PA。补一格 power=0 的 R，
                    // 俯视变成 PR / ARU / PR（emitJoints 最后统一覆盖为接口专用 R）。
                    out.add(sb(x0, Y + 1, zd, WIRE_X));
                }
            }

            // 侧视 RA / GR / RG / GR / RG：全部是 R（power=0），不写 D，不留 K 硌脑袋。
            out.add(sb(x0, Y,     zMid, G));
            out.add(sb(x0, Y + 1, zMid, WIRE_R_BUS));
            out.add(sb(x1, Y + 1, zMid, G));
            out.add(sb(x0, Y + 2, zMid, G));
            out.add(sb(x1, Y + 2, zMid, WIRE_UP_R));
            out.add(sb(x0, Y + 3, zMid, WIRE_UP_R));
            out.add(sb(x1, Y + 3, zMid, G));
            out.add(sb(x0, Y + 4, zMid, G));
            out.add(sb(x1, Y + 4, zMid, WIRE_UP_R));
            out.add(sb(x0, Y + 5, zMid, WIRE_R_BUS));
            out.add(sb(x1, Y + 5, zMid, A));

            // 清掉塔侧列在 zMid±1 的所有残留方块，保证俯视确实是 AGA。
            // 所有红石粉一律 power=0：充能状态交给游戏按邻接信号源重算，不天然亮。
            for (int y = Y; y <= Y + 5; y++) {
                if (zMid - 1 >= 0)     out.add(sb(x1, y, zMid - 1, A));
                if (zMid + 1 <= ZC)    out.add(sb(x1, y, zMid + 1, A));
                out.add(sb(x2, y, zMid, A));
            }
        }
    }

    /** N：音色方块在 Y，音符盒在 Y+1，上方 Y+2 留空发声；S 类下方补 G。 */
    private static void emitNote(List<String> out, N n, int X, int Y, int Z,
                                 List<Object> customs, int vc) {
        int inst = n.instr & 0xFF;
        Object ci = null; boolean custom = false;
        if (inst >= vc) {
            int idx = inst - vc;
            if (idx >= 0 && idx < customs.size()) { ci = customs.get(idx); custom = true; }
        }
        String file = custom ? str(ci, "file") : "";
        if (file != null) file = file.trim();

        if (custom && file != null && !file.isEmpty()) {
            int bp = intOf(ci, "pitch", 45);
            int press = intOf(ci, "press", 0);
            double p = djPitch(n.noteId, bp, press);
            out.add(sb(X, Y - 1, Z, G));                    // 图5 支撑
            out.add(sb(X, Y, Z, K));
            out.add(sb(X, Y + 1, Z, "minecraft:command_block[facing=north]"));
            String cmd = "playsound " + file + " master @a ~ ~ ~ 1 " + p + " 1";
            out.add("data merge block ~" + X + " ~" + (Y + 1) + " ~" + Z
                + " {Command:\"" + esc(cmd) + "\",auto:0b}");
            return;
        }
        int note = n.noteId;
        while (note < 0) note += 12;
        while (note > 24) note -= 12;
        String block = inst < INST_BLOCK.length ? INST_BLOCK[inst] : INST_BLOCK[0];
        boolean falling = block.endsWith("sand") || block.endsWith("glass")
                       || block.endsWith("gravel") || block.endsWith("concrete_powder");
        if (falling) out.add(sb(X, Y - 1, Z, G));           // 图5：防止 S 掉落
        out.add(sb(X, Y, Z, block));
        out.add(sb(X, Y + 1, Z, "minecraft:note_block[note=" + note + "]"));
    }

    /**
     * 编辑器口径的 pitch：note 0..24，note 12(F#4)=1.0，note 24(#F24)=2.0
     * —— 即用户实测「NoteBlockEditScreen 显示的 #F24 对应 pitch=2」。
     * 基准固定为 note 空间 12，完全不读 custom instrument 的 pitch 字段(NBS 45)，
     * 避免不同谱子的自定义乐器基准漂移导致音高整体偏移。
     * /playsound 的 pitch 合法域是 0~2：只 clamp，绝不做「>2 减半 / <0.5 翻倍」的八度折叠，
     * 那正是把 0.297 折成 0.595、以及不同音高压成同一个值的失真来源。
     * 若某谱子的 noteId 本身就是 NBS key(45=F#4)，HMCL 加 -Ddj.pitchBase=45 即可切回。
     */
    private static double djPitch(int noteId, int bp, int press) {
        if (press == 1) return 1.0;
        int base = Integer.getInteger("dj.pitchBase", 12);
        double p = Math.pow(2.0, (noteId - base) / 12.0);
        p = Math.max(0.0, Math.min(2.0, p));
        return Math.round(p * 1000.0) / 1000.0;
    }

    /**
     * 中继器头与玻璃阱的衔接头：在【全部层、全部 riser 之后】最后一次覆盖。
     * 俯视恒为 PR / ARU / PR —— 第二列三行都是 power=0 的 R：
     *   上下两行 R 接中继器 P 的输出头；中间行 R 既连上下 R、又连第三列的塔 U。
     * 上一版循环写成 lv+1<levels，漏掉最后一层（JOINT_BAD=3），这里改成全部层，
     * 并且 lv>=1 的入端母线列也一并覆盖（本层下垫/母线循环会把它刷回 K/R）。
     * lv=0 的入端 zMid 是石头按钮，绝不覆盖。只写轨面 Y+1，玻璃一个字不动。
     */
    /** 占位器对齐【音符盒/音色方块/石头列】(偶数 X)，不落在 P 中继器列(奇数 X)。 */
    private static int centerX(int W) {
        int x = (W / 2) & ~1;                       // 强制偶数 = N 列
        return Math.max(2, Math.min(W - 2, x));
    }

    /**
     * DJ_V6: 绝不放 redstone_block —— 常亮电源会让命令方块天然激活，与「只有播放时
     * 才该激活」直接冲突。tp 改由【石头按钮的脉冲】触发：
     *   按钮在 (0,1,zMid) face=floor，附着方块 (0,0,zMid) 保持 K；
     *   按下 -> 该 K 被强充能 -> 激活水平相邻的 (1,0,zMid) impulse 命令方块一次，松开即止。
     * 不新增任何红石粉、不改音轨/A 行结构、不留任何常亮方块。
     * 各层中心 (centerX(W), Y+1, zMid) 的命令方块仍作占位器保留，auto:0b 且无触发源，
     * 不会自己激活；-Ddj.centerTp=0 可整个关闭。
     */
    private static void emitCenterTp(List<String> out, int levels, int W, int zMid, int mode) {
        if (mode == 0) return;
        int xC = centerX(W);
        // 命令方块自身在 (1,0,zMid)；目标层中心是 (xC,1,zMid) -> 相对偏移 (xC-1, 1, 0)
        int dx = xC - 1;
        out.add(sb(1, 0, zMid, "minecraft:command_block"));
        out.add("data merge block ~1 ~0 ~" + zMid
            + " {Command:\"tp @a[distance=..100] ~" + dx + " ~1 ~\",auto:0b}");
    }

    private static void emitJoints(List<String> out, int levels, int W, int ZC, int zMid) {
        for (int lv = 0; lv < levels; lv++) {
            int Y = 4 * lv;
            boolean rev = (lv % 2 == 1);
            int xIn  = rev ? W : 0;
            int xOut = rev ? 0 : W;
            for (int zd = zMid - 1; zd <= zMid + 1; zd++) {
                if (zd < 0 || zd > ZC) continue;
                out.add(sb(xOut, Y + 1, zd, WIRE_R_BUS));
            }
            if (lv > 0) {
                for (int zd = zMid - 1; zd <= zMid + 1; zd++) {
                    if (zd < 0 || zd > ZC) continue;
                    out.add(sb(xIn, Y + 1, zd, WIRE_R_BUS));
                }
            }
        }
    }

    /** 母线续电中继器位置：只在轨间行(奇数 z)；第一个距中点 first，之后每 span 一个。
        例 first=6,span=10 -> d=6,16,26,36...，任意相邻间距 <= 10，最低能量 R5。 */
    private static boolean isFeed(int z, int zMid, int first, int span) {
        if ((z & 1) == 0 || z == zMid) return false;
        int d = Math.abs(z - zMid);
        return d >= first && ((d - first) % span == 0);
    }

    private static String sb(int x, int y, int z, String block) {
        return "setblock ~" + x + " ~" + y + " ~" + z + " " + block + " replace";
    }
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
    private static String safeName(String s) {
        if (s == null || s.trim().isEmpty()) return "exported_song";
        StringBuilder b = new StringBuilder();
        for (char ch : s.replaceAll("(?i)\\.nbs$", "").toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.') b.append(ch);
            else b.append('_');
        }
        return b.length() == 0 ? "exported_song" : b.toString();
    }
    private static Object val(Object o, String name) {
        if (o == null) return null;
        try { java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
              f.setAccessible(true); return f.get(o); } catch (Throwable ignored) { return null; }
    }
    private static String str(Object o, String name) { Object v = val(o, name); return v == null ? null : v.toString(); }
    private static int intOf(Object o, String name, int def) {
        Object v = val(o, name); return v instanceof Number ? ((Number) v).intValue() : def;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> customs(Song song) {
        Object v = val(song, "customInstruments");
        return v instanceof List ? (List<Object>) v : Collections.emptyList();
    }
    private static int vanillaCount(Song song) {
        int v = intOf(song, "vanillaInstrumentCount", 0) & 0xFF;
        return v > 0 ? v : 20;
    }
    private static final class N {
        final int tick, layer, instr, noteId;
        N(int t, int l, int i, int n) { tick = t; layer = l; instr = i; noteId = n; }
    }
    public static final class Result {
        public final String commands, info;
        Result(String c, String i) { commands = c; info = i; }
    }

    /** 一键铺进当前世界：绝对坐标 + 整片地基 + 分批发送（原样保留）。 */
    public static void placeInWorld(String commands) {
        final java.util.List<String> lines = new java.util.ArrayList<>();
        for (String s : commands.split("\\R")) {
            String t = s.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            lines.add(t);
        }
        if (lines.isEmpty()) return;
        try {
            Object mc = Class.forName("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
            if (mc == null) { System.out.println("[DJ_REDSTONE] no Minecraft instance"); return; }
            Object player = djField(mc, "player");
            Object conn = (player == null) ? null : djField(player, "connection");
            if (conn == null) conn = djMethod0(mc, "getConnection");
            if (conn == null) { System.out.println("[DJ_REDSTONE] no connection"); return; }
            java.lang.reflect.Method send = null; boolean slash = false;
            for (java.lang.reflect.Method m : conn.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class
                        && m.getName().toLowerCase().contains("sendcommand")) { send = m; break; }
            }
            if (send == null) for (java.lang.reflect.Method m : conn.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class
                        && m.getName().toLowerCase().contains("chat")) { send = m; slash = true; break; }
            }
            if (send == null && player != null) for (java.lang.reflect.Method m : player.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class
                        && m.getName().toLowerCase().contains("chat")) { send = m; slash = true; break; }
            }
            if (send == null) { System.out.println("[DJ_REDSTONE] no command sender"); return; }
            java.lang.reflect.Method exec = null;
            for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
                if (m.getName().equals("execute") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Runnable.class) { exec = m; break; }
            }
            int[] pos = djPos(player);
            if (pos != null) {
                final int PX = pos[0] + 3, PY = pos[1], PZ = pos[2];
                java.util.List<String> abs = new java.util.ArrayList<>(lines.size());
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                java.util.regex.Pattern pt = java.util.regex.Pattern.compile("~(-?\\d+)?");
                java.util.regex.Pattern cc = java.util.regex.Pattern.compile(
                    "^(?:setblock|data merge block)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)");
                for (String raw : lines) {
                    java.util.regex.Matcher mt = pt.matcher(raw);
                    StringBuffer s2 = new StringBuffer(); int k = 0;
                    while (mt.find() && k < 3) {
                        String num = mt.group(1);
                        int v = (num == null || num.isEmpty()) ? 0 : Integer.parseInt(num);
                        int a = (k == 0) ? PX + v : (k == 1) ? PY + v : PZ + v;
                        mt.appendReplacement(s2, java.util.regex.Matcher.quoteReplacement(String.valueOf(a)));
                        k++;
                    }
                    mt.appendTail(s2);
                    String out = s2.toString(); abs.add(out);
                    java.util.regex.Matcher cm = cc.matcher(out);
                    if (cm.find()) {
                        int X = Integer.parseInt(cm.group(1)), Y = Integer.parseInt(cm.group(2)), Z = Integer.parseInt(cm.group(3));
                        minX = Math.min(minX, X); maxX = Math.max(maxX, X);
                        minY = Math.min(minY, Y);
                        minZ = Math.min(minZ, Z); maxZ = Math.max(maxZ, Z);
                    }
                }
                lines.clear(); lines.addAll(abs);
                if (minX != Integer.MAX_VALUE) {
                    lines.add(0, "fill " + (minX - 1) + " " + (minY - 1) + " " + (minZ - 1)
                        + " " + (maxX + 1) + " " + (minY - 1) + " " + (maxZ + 1)
                        + " minecraft:stone replace");
                }
            } else System.out.println("[DJ_REDSTONE] no player pos; 保持相对坐标");

            final java.lang.reflect.Method fSend = send, fExec = exec;
            final Object fMc = mc, fConn = conn, fPlayer = player;
            final boolean fSlash = slash;
            Thread th = new Thread(() -> {
                int from = 0;
                while (from < lines.size()) {
                    final int a = from, b = Math.min(lines.size(), from + 200);
                    from = b;
                    Runnable batch = () -> {
                        for (int i = a; i < b; i++) {
                            String cmd = fSlash ? "/" + lines.get(i) : lines.get(i);
                            try { fSend.invoke(fSlash ? fPlayer : fConn, cmd); }
                            catch (Throwable t) { try { fSend.invoke(fConn, cmd); } catch (Throwable ignored) {} }
                        }
                    };
                    if (fExec != null) { try { fExec.invoke(fMc, batch); } catch (Throwable e) { batch.run(); } }
                    else batch.run();
                    try { Thread.sleep(40); } catch (InterruptedException e) { break; }
                }
                System.out.println("[DJ_REDSTONE] placed " + lines.size() + " commands (DJ_V6_PULSE_TP)");
            }, "DJ-Redstone-Place");
            th.setDaemon(true); th.start();
        } catch (Throwable t) {
            System.out.println("[DJ_REDSTONE] place skipped: " + t);
        }
    }
    private static int[] djPos(Object player) {
        if (player == null) return null;
        try {
            return new int[]{
                (Integer) player.getClass().getMethod("getBlockX").invoke(player),
                (Integer) player.getClass().getMethod("getBlockY").invoke(player),
                (Integer) player.getClass().getMethod("getBlockZ").invoke(player)};
        } catch (Throwable ignored) {}
        for (String mn : new String[]{"blockPosition", "getOnPos"}) {
            try {
                Object p = player.getClass().getMethod(mn).invoke(player);
                return new int[]{
                    (Integer) p.getClass().getMethod("getX").invoke(p),
                    (Integer) p.getClass().getMethod("getY").invoke(p),
                    (Integer) p.getClass().getMethod("getZ").invoke(p)};
            } catch (Throwable ignored) {}
        }
        return null;
    }
    private static Object djField(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (Throwable ignored) {}
        }
        return null;
    }
    private static Object djMethod0(Object o, String name) {
        try { return o.getClass().getMethod(name).invoke(o); } catch (Throwable ignored) { return null; }
    }
}
