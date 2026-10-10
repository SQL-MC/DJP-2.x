package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JukeboxProjectorItem extends Item {
    private static final Logger LOGGER = LoggerFactory.getLogger("Disc Jockey/JukeboxProjectorItem");

    public static JukeboxProjectorItem INSTANCE; // 由 Main 在注册后赋值
    private static final String REMOTE_URL =
            "https://raw.githubusercontent.com/SQL-MC/Nbs/main/NoteBlocks/%E9%9F%B3%E4%B9%90%E5%8E%85.litematic";
    private static final String FILE_NAME = "音乐厅.litematic";
    private static volatile boolean downloading = false;
    private static final Set<Object> IN_FLIGHT = Collections.newSetFromMap(new WeakHashMap<>());

    private static Path getTemplateDir() {
        return Paths.get(System.getProperty("user.dir"), "config", "disc_jockey", "NoteBlocks");
    }

    private static File getLocalFile() {
        return getTemplateDir().resolve(FILE_NAME).toFile();
    }

    public JukeboxProjectorItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();

        if (level.isClientSide()) {
            Object key = ctx.getPlayer();
            if (!IN_FLIGHT.add(key)) return InteractionResult.SUCCESS;
            Minecraft.getInstance().execute(() -> IN_FLIGHT.remove(key));
            return InteractionResult.SUCCESS;
        }
        if (ctx.getPlayer() != null && !ctx.getPlayer().getAbilities().instabuild) {
            ctx.getItemInHand().shrink(1);
        }

        BlockPos origin = ctx.getClickedPos().relative(ctx.getClickedFace());
        File local = getLocalFile();
        if (local.exists() && local.length() > 0) {
            LOGGER.info("[Disc Jockey] 使用本地模板: " + local.getAbsolutePath());
            // ★ 服务端直接同步放置，不再丢回客户端线程
            pasteLitematic(level, origin, local);
        } else {
            if (downloading) {
                LOGGER.info("[Disc Jockey] 模板下载中，请稍候…");
                return InteractionResult.SUCCESS;
            }
            downloading = true;
            LOGGER.info("[Disc Jockey] 本地模板不存在，开始下载...");
            CompletableFuture.supplyAsync(() -> downloadFile(REMOTE_URL, local))
                    .thenAccept(success -> {
                        downloading = false;
                        if (success) {
                            LOGGER.info("[Disc Jockey] 下载完成，开始放置");
                            // 下载在后台线程完成，放置仍交回服务端 tick：用 server execute
                            level.getServer().execute(() -> pasteLitematic(level, origin, local));
                        } else {
                            LOGGER.info("[Disc Jockey] 下载失败，回退到自动生成模板");
                            Song song = getCurrentSong();
                            if (song != null) {
                                level.getServer().execute(() -> generateSongTemplate(level, origin, song));
                            }
                        }
                    })
                    .exceptionally(ex -> {
                        downloading = false;
                        LOGGER.info("[Disc Jockey] 下载异常(已解锁): " + ex.getMessage());
                        return null;
                    });
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 解析并放置 litematic。
     * 存储顺序 YZX；localOff = minCorner - regionPos；位压缩 bitsPerIndex=ceilLog2(palette)。
     * 模板 Size=(13,17,-13) Pos=(0,0,12) -> localOff=(0,0,-13)
     * ★ 本方法现在运行在服务端线程，直接 setBlock，不碰 Minecraft.getInstance()
     */
    public static void pasteLitematic(Level level, BlockPos origin, File file) {
        try (DataInputStream dis = new DataInputStream(
                new java.util.zip.GZIPInputStream(Files.newInputStream(file.toPath())))) {
            CompoundTag root = net.minecraft.nbt.NbtIo.read(dis);
            CompoundTag schematic = root.getCompound("Schematic").orElse(root);
            CompoundTag regions = schematic.getCompound("Regions")
                    .orElseThrow(() -> new IOException("No Regions tag"));

            int regionCount = 0;
            long totalPlaced = 0;

            for (String regionName : regions.keySet()) {
                CompoundTag region = regions.getCompound(regionName)
                        .orElseThrow(() -> new IOException("Region missing: " + regionName));

                ListTag palette = region.getList("BlockStatePalette").orElse(null);
                if (palette == null) throw new IOException("No BlockStatePalette");
                long[] blockStates = region.getLongArray("BlockStates")
                        .orElseThrow(() -> new IOException("No BlockStates"));

                CompoundTag sizeTag = region.getCompound("Size")
                        .orElseThrow(() -> new IOException("No Size"));
                int sx = sizeTag.getInt("x").orElse(0);
                int sy = sizeTag.getInt("y").orElse(0);
                int sz = sizeTag.getInt("z").orElse(0);

                int width  = Math.abs(sx);
                int height = Math.abs(sy);
                int length = Math.abs(sz);

                int posX = 0, posY = 0, posZ = 0;
                CompoundTag posTag = region.getCompound("Position").orElse(null);
                if (posTag == null) posTag = region.getCompound("BlockPos").orElse(null);
                if (posTag != null) {
                    posX = posTag.getInt("x").orElse(0);
                    posY = posTag.getInt("y").orElse(0);
                    posZ = posTag.getInt("z").orElse(0);
                }

                int endSubX = (sx > 0) ? (sx - 1) : sx;
                int endSubY = (sy > 0) ? (sy - 1) : sy;
                int endSubZ = (sz > 0) ? (sz - 1) : sz;
                int endRelX = posX + endSubX;
                int endRelY = posY + endSubY;
                int endRelZ = posZ + endSubZ;
                int minX = Math.min(posX, endRelX);
                int minY = Math.min(posY, endRelY);
                int minZ = Math.min(posZ, endRelZ);
                int localOffX = minX - posX;
                int localOffY = minY - posY;
                int localOffZ = minZ - posZ;

                int bitsPerIndex = Math.max(2, ceilLog2(palette.size()));
                long blocksPerLong = 64L / bitsPerIndex;
                long totalBlocks = (long) width * height * length;

                long longsNeeded = (totalBlocks * bitsPerIndex + 63) / 64;
                if (blockStates.length < longsNeeded) {
                    LOGGER.info("[Disc Jockey][FATAL] BlockStates 长度不足: need " + longsNeeded
                            + " got " + blockStates.length);
                    return;
                }

                LOGGER.info("[Disc Jockey] Region='" + regionName + "'"
                        + " Size=(" + sx + "," + sy + "," + sz + ")"
                        + " localOff=(" + localOffX + "," + localOffY + "," + localOffZ + ")"
                        + " abs=" + width + "x" + height + "x" + length
                        + " palette=" + palette.size() + " bits=" + bitsPerIndex);

                long skippedUnknown = 0, skippedAir = 0, skippedPalette = 0;
                boolean verifyPassed = verifyLayerStructure(
                        blockStates, palette, width, height, length, bitsPerIndex, blocksPerLong);

                final List<BlockPos> posList = new ArrayList<>();
                final List<BlockState> stateList = new ArrayList<>();
                for (long i = 0; i < totalBlocks; i++) {
                    int paletteIdx = readPaletteIndex(blockStates, i, bitsPerIndex, blocksPerLong);
                    if (paletteIdx < 0 || paletteIdx >= palette.size()) { skippedPalette++; continue; }
                    CompoundTag stateTag = resolvePaletteEntry(palette, paletteIdx);
                    if (stateTag == null) { skippedUnknown++; continue; }
                    BlockState state = readBlockState(stateTag);
                    if (state == null) { skippedUnknown++; continue; }
                    if (state.isAir()) { skippedAir++; continue; }

                    long perLayer = (long) width * length;
                    long ly = i / perLayer;
                    long rem = i % perLayer;
                    long lz = rem / width;
                    long lx = rem % width;

                    posList.add(origin.offset(localOffX + (int) lx, localOffY + (int) ly, localOffZ + (int) lz));
                    stateList.add(state);
                }

                // ★ 服务端直接落块，一次性同步写完
                for (int k = 0; k < posList.size(); k++) {
                    level.setBlock(posList.get(k), stateList.get(k), 3);
                }
                LOGGER.info("[Disc Jockey] 服务端放置完成 " + posList.size() + " 块");

                totalPlaced += posList.size();
                regionCount++;
                LOGGER.info("[Disc Jockey] Region '" + regionName + "': 已放置 " + posList.size()
                        + " [跳过: palette=" + skippedPalette + " unknown=" + skippedUnknown
                        + " air=" + skippedAir + "] verify=" + (verifyPassed ? "PASS" : "FAIL"));
            }

            LOGGER.info("[Disc Jockey] 总计放置 " + totalPlaced + " 个方块，处理了 " + regionCount + " 个 region");

        } catch (IOException e) {
            LOGGER.info("[Disc Jockey] 解析 litematic 失败: " + e.getMessage());
            LOGGER.error("异常详情", e);
        }
    }

    private static CompoundTag resolvePaletteEntry(ListTag palette, int idx) {
        if (idx < 0 || idx >= palette.size()) return null;
        if (palette.get(idx) instanceof CompoundTag) return palette.getCompound(idx).orElse(null);
        var elem = palette.get(idx);
        int ref = -1;
        if (elem instanceof net.minecraft.nbt.IntTag it) ref = it.intValue();
        else if (elem instanceof net.minecraft.nbt.LongTag lt) ref = (int) lt.longValue();
        else ref = palette.getInt(idx).orElse(-1);
        if (ref >= 0 && ref < palette.size()) return palette.getCompound(ref).orElse(null);
        return null;
    }

    private static boolean verifyLayerStructure(long[] blockStates, ListTag palette,
                                               int width, int height, int length,
                                               int bits, long blocksPerLong) {
        int noteLayerCount = 0;
        int leavesLayerCount = 0;
        for (int y = 0; y < height; y++) {
            int noteInLayer = 0, leavesInLayer = 0, nonAirInLayer = 0;
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < length; z++) {
                    long i = (long) y * width * length + (long) z * width + x;
                    int idx = readPaletteIndex(blockStates, i, bits, blocksPerLong);
                    if (idx <= 0 || idx >= palette.size()) continue;
                    CompoundTag t = resolvePaletteEntry(palette, idx);
                    if (t == null) continue;
                    String name = t.getString("Name").orElse("");
                    if (name.isEmpty() || name.equals("minecraft:air")) continue;
                    nonAirInLayer++;
                    if (name.equals("minecraft:note_block")) noteInLayer++;
                    if (name.contains("leaves")) leavesInLayer++;
                }
            }
            if (nonAirInLayer == 0) continue;
            if ((double) noteInLayer / nonAirInLayer > 0.4) noteLayerCount++;
            if ((double) leavesInLayer / nonAirInLayer > 0.5) leavesLayerCount++;
        }
        boolean okNotes = noteLayerCount == 4;
        boolean okLeaves = leavesLayerCount >= 2;
        return okNotes && okLeaves;
    }

    // ===== readBlockState 容错（不再因属性解析失败吞非音符方块）=====
    private static BlockState readBlockState(CompoundTag tag) {
        String name = tag.getString("Name").orElse("");
        if (name.isEmpty()) return null;
        if (name.equals("minecraft:air")) return null;
        Identifier id = Identifier.tryParse(name);
        if (id == null) { LOGGER.info("[Disc Jockey][readBlockState] 非法 ID: " + name); return null; }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block == null) { LOGGER.info("[Disc Jockey][readBlockState] 未注册: " + name); return null; }
        BlockState state = block.defaultBlockState();
        CompoundTag props = tag.getCompound("Properties").orElse(null);
        if (props == null) return state;
        StateDefinition<Block, BlockState> def = block.getStateDefinition();
        for (String key : props.keySet()) {
            String value = props.getString(key).orElse("");
            for (Property<?> prop : def.getProperties()) {
                if (!prop.getName().equals(key)) continue;
                Object val = parseProperty(prop, value);
                if (val != null) state = setStateSafely(state, prop, val);
            }
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setStateSafely(BlockState state, Property<?> prop, Object val) {
        return state.setValue((Property<T>) prop, (T) val);
    }

    private static Object parseProperty(Property<?> prop, String value) {
        if (prop instanceof net.minecraft.world.level.block.state.properties.IntegerProperty ip)
            return ip.getValue(value).orElse(null);
        if (prop instanceof net.minecraft.world.level.block.state.properties.BooleanProperty bp)
            return bp.getValue(value).orElse(null);
        if (prop instanceof net.minecraft.world.level.block.state.properties.EnumProperty<?> ep) {
            for (Object v : ep.getPossibleValues())
                if (v.toString().equalsIgnoreCase(value)) return v;
            for (Object v : ep.getPossibleValues())
                if (((Enum<?>) v).name().equalsIgnoreCase(value)) return v;
            String norm = value.toLowerCase().replace("-", "_");
            for (Object v : ep.getPossibleValues())
                if (v.toString().toLowerCase().replace("_", "").equals(norm.replace("_", ""))) return v;
        }
        return null;
    }

    private static int readPaletteIndex(long[] arr, long index, int bits, long blocksPerLong) {
        long startBit = index * (long) bits;
        int bi = (int) (startBit >>> 6);
        int bitInLong = (int) (startBit & 63);
        if (bi >= arr.length) return -1;
        long v = arr[bi] >>> bitInLong;
        int bitsHere = Math.min(bits, 64 - bitInLong);
        if (bits > bitsHere) {
            int remain = bits - bitsHere;
            if (bi + 1 < arr.length) v |= (arr[bi + 1] & ((1L << remain) - 1)) << bitsHere;
            else return -1;
        }
        return (int) (v & ((1L << bits) - 1));
    }

    private static int ceilLog2(int n) {
        if (n <= 1) return 1;
        return 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    private Song getCurrentSong() {
        if (Main.SONG_PLAYER != null && Main.SONG_PLAYER.running) return Main.SONG_PLAYER.getSong();
        if (Main.PREVIEWER != null && Main.PREVIEWER.isRunning()) return Main.PREVIEWER.getInstance().getSong();
        return null;
    }

    public static void generateSongTemplate(Level level, BlockPos origin, Song song) {
        if (song == null || song.notes == null) return;
        int version = (song.formatVersion & 0xFF);
        Map<NoteBlockInstrument, Map<Integer, Boolean>> used = new HashMap<>();
        for (long note : song.notes) {
            int rawInst = (int) ((note >> Note.INSTRUMENT_SHIFT) & 0xFF);
            int rawNote = Note.extractNoteId(note);
            int noteId = ((rawNote % 25) + 25) % 25;
            NoteBlockInstrument inst = Note.fromNbs(version, rawInst);
            if (inst == null) continue;
            used.computeIfAbsent(inst, k -> new HashMap<>()).put(noteId, true);
        }
        int y = origin.getY(), x0 = origin.getX(), z0 = origin.getZ();
        int col = 0, unknownInstruments = 0;
        List<NoteBlockInstrument> instOrder = new ArrayList<>(used.keySet());
        instOrder.sort(java.util.Comparator.comparing(Object::toString));
        for (NoteBlockInstrument inst : instOrder) {
            Block base = Note.INSTRUMENT_BLOCKS.get(inst);
            if (base == null || base == Blocks.AIR) { unknownInstruments++; continue; }
            List<Integer> noteList = new ArrayList<>(used.get(inst).keySet());
            Collections.sort(noteList);
            for (int row = 0; row < noteList.size(); row++) {
                int noteId = noteList.get(row);
                BlockPos basePos = new BlockPos(x0 + col * 2, y, z0 + row * 2);
                level.setBlock(basePos, base.defaultBlockState(), 2);
                level.setBlock(basePos.above(), Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, noteId), 2);
            }
            col++;
        }
        LOGGER.info("[Disc Jockey] 自动生成模板: " + instOrder.size() + " 种乐器"
                + (unknownInstruments > 0 ? "（" + unknownInstruments + " 种无底座跳过）" : "") + ", 原点=" + origin);
    }

    private static boolean downloadFile(String url, File dest) {
        try {
            dest.getParentFile().mkdirs();
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "DiscJockey/1.0").build();
            HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) { LOGGER.info("[Disc Jockey] 下载失败 HTTP " + resp.statusCode()); return false; }
            try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(resp.body()); }
            LOGGER.info("[Disc Jockey] 已保存: " + dest.getAbsolutePath() + " (" + dest.length() + " bytes)");
            return true;
        } catch (Exception e) { LOGGER.info("[Disc Jockey] 下载异常: " + e.getMessage()); return false; }
    }
}