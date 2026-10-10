package semmiedev.disc_jockey;

import java.util.HashMap;
import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

public record Note(NoteBlockInstrument instrument, byte note) {
    public static final HashMap<NoteBlockInstrument, Block> INSTRUMENT_BLOCKS = new HashMap<>();

    public static final byte LAYER_SHIFT = Short.SIZE;
    public static final byte INSTRUMENT_SHIFT = Short.SIZE * 2;
    public static final byte NOTE_SHIFT = Short.SIZE * 2 + Byte.SIZE;

    public static int extractNoteId(long note) {
        long raw = (note >>> NOTE_SHIFT) & 0xFF;
        return raw < 128 ? (int) raw : (int) (raw - 256);
    }

    public static long packNoteId(long baseNote, int noteId) {
        byte b = (byte) noteId;
        return (baseNote & ~(0xFFL << NOTE_SHIFT))
             | ((long) (b & 0xFF) << NOTE_SHIFT);
    }

    // ---- NBS 版本映射表 ----
    public static final NoteBlockInstrument[] NBS_V0 = new NoteBlockInstrument[]{
        NoteBlockInstrument.HARP,
        NoteBlockInstrument.BASS,
        NoteBlockInstrument.BASEDRUM,
        NoteBlockInstrument.SNARE,
        NoteBlockInstrument.HAT,
        NoteBlockInstrument.GUITAR,
        NoteBlockInstrument.FLUTE,
        NoteBlockInstrument.BELL,
        NoteBlockInstrument.CHIME,
        NoteBlockInstrument.XYLOPHONE,
    };

    public static final NoteBlockInstrument[] NBS_V4 = new NoteBlockInstrument[]{
        NoteBlockInstrument.HARP,
        NoteBlockInstrument.BASS,
        NoteBlockInstrument.BASEDRUM,
        NoteBlockInstrument.SNARE,
        NoteBlockInstrument.HAT,
        NoteBlockInstrument.GUITAR,
        NoteBlockInstrument.FLUTE,
        NoteBlockInstrument.BELL,
        NoteBlockInstrument.CHIME,
        NoteBlockInstrument.XYLOPHONE,
        NoteBlockInstrument.IRON_XYLOPHONE,
        NoteBlockInstrument.COW_BELL,
        NoteBlockInstrument.DIDGERIDOO,
        NoteBlockInstrument.BIT,
        NoteBlockInstrument.BANJO,
        NoteBlockInstrument.PLING,
    };

    public static NoteBlockInstrument[] instrumentsForVersion(int version) {
        if (version >= 0 && version <= 3) return NBS_V0;
        return NBS_V4;
    }

    public static NoteBlockInstrument fromNbs(int version, int instrumentId) {
        NoteBlockInstrument[] table = instrumentsForVersion(version);
        if (instrumentId < 0 || instrumentId >= table.length) {
            return NoteBlockInstrument.HARP;
        }
        return table[instrumentId];
    }

    // 原版 INSTRUMENTS 数组保留，向后兼容
    public static final NoteBlockInstrument[] INSTRUMENTS = new NoteBlockInstrument[]{
            NoteBlockInstrument.HARP,
            NoteBlockInstrument.BASS,
            NoteBlockInstrument.BASEDRUM,
            NoteBlockInstrument.SNARE,
            NoteBlockInstrument.HAT,
            NoteBlockInstrument.GUITAR,
            NoteBlockInstrument.FLUTE,
            NoteBlockInstrument.BELL,
            NoteBlockInstrument.CHIME,
            NoteBlockInstrument.XYLOPHONE,
            NoteBlockInstrument.IRON_XYLOPHONE,
            NoteBlockInstrument.COW_BELL,
            NoteBlockInstrument.DIDGERIDOO,
            NoteBlockInstrument.BIT,
            NoteBlockInstrument.BANJO,
            NoteBlockInstrument.PLING,
            NoteBlockInstrument.TRUMPET,
            NoteBlockInstrument.TRUMPET_EXPOSED,
            NoteBlockInstrument.TRUMPET_WEATHERED,
            NoteBlockInstrument.TRUMPET_OXIDIZED
    };

    static {
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.HARP, Blocks.AIR);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BASEDRUM, Blocks.STONE);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.SNARE, Blocks.SAND);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.HAT, Blocks.GLASS);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BASS, Blocks.OAK_PLANKS);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.FLUTE, Blocks.CLAY);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BELL, Blocks.GOLD_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.GUITAR, (net.minecraft.world.level.block.Block) Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE));
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.CHIME, Blocks.PACKED_ICE);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.XYLOPHONE, Blocks.BONE_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.IRON_XYLOPHONE, Blocks.IRON_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.COW_BELL, Blocks.SOUL_SAND);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.DIDGERIDOO, Blocks.PUMPKIN);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BIT, Blocks.EMERALD_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BANJO, Blocks.HAY_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.PLING, Blocks.GLOWSTONE);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.TRUMPET, (net.minecraft.world.level.block.Block) Blocks.COPPER_BLOCK.weathering().unaffected());
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.TRUMPET_EXPOSED, (net.minecraft.world.level.block.Block) Blocks.COPPER_BLOCK.weathering().exposed());
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.TRUMPET_WEATHERED, (net.minecraft.world.level.block.Block) Blocks.COPPER_BLOCK.weathering().weathered());
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.TRUMPET_OXIDIZED, (net.minecraft.world.level.block.Block) Blocks.COPPER_BLOCK.weathering().oxidized());
    }
}