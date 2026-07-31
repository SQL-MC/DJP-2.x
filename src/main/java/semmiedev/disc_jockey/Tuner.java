package semmiedev.disc_jockey;

import org.apache.commons.lang3.NotImplementedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class Tuner {

    private HashMap<NoteBlockInstrument, HashMap<Byte, BlockPos>> noteBlocks = null;
    private long tunedAfter = Util.TIMESTAMP_UNINITIALIZED;
    private final HashMap<BlockPos, Map.Entry<Integer, Long>> notePredictions = new HashMap<>();
    private HashMap<Block, Integer> missingInstrumentBlocks = new HashMap<>();
    private long lastInteractAt = -1;
    private float availableInteracts = 8;
    private int tuneInitialUntunedBlocks = -1;
    private Song selectedSong = null;

    public @NotNull HashMap<NoteBlockInstrument, @Nullable NoteBlockInstrument> instrumentMap = new HashMap<>();

    public boolean isTuned() {
        return isSongSelected() && tunedAfter != Util.TIMESTAMP_UNINITIALIZED && tunedAfter <= Util.now();
    }

    public void cleanup() {
        ArrayList<BlockPos> outdatedPredictions = new ArrayList<>();
        for (Map.Entry<BlockPos, Map.Entry<Integer, Long>> entry : notePredictions.entrySet()) {
            if (entry.getValue().getValue() < Util.now())
                outdatedPredictions.add(entry.getKey());
        }
        for (BlockPos outdatedPrediction : outdatedPredictions) notePredictions.remove(outdatedPrediction);
    }

    private HashMap<Byte, BlockPos> getNotes(NoteBlockInstrument instrument) {
        return noteBlocks.computeIfAbsent(instrument, k -> new HashMap<>());
    }

    public boolean isSongSelected() {
        return noteBlocks != null && missingInstrumentBlocks != null && selectedSong != null;
    }

    public void reset() {
        selectedSong = null;
        noteBlocks = null;
        missingInstrumentBlocks = null;
        resetTuned();
    }

    public void resetTuned() {
        noteBlocks = null;
        notePredictions.clear();
        tunedAfter = Util.TIMESTAMP_UNINITIALIZED;
        tuneInitialUntunedBlocks = -1;
        availableInteracts = 0;
    }

    // ============================================================
    // ✅ DJP000016：新增方法
    // 从 foldedNotes 提取 (instrument, transposedNoteId) 列表
    // 保证 Tuner 绑定的 key == 播放时查询的 key
    // fallback：foldedNotes 不存在时用原始 uniqueNotes
    // ============================================================
    private ArrayList<Note> getNotesToBind(Song song) {
        if (song == null) return new ArrayList<>();
        
        if (NoteClamper.hasFoldedNotes(song)) {
            ArrayList<Note> result = new ArrayList<>();
            HashMap<Long, Boolean> seen = new HashMap<>();
            for (long foldedNote : song.foldedNotes) {
                // bits 32-39 = NBS instrument ID
                byte instrId = (byte) (foldedNote >> Note.INSTRUMENT_SHIFT);
                // bits 40-47 = transposed noteId
                byte nid = (byte) ((foldedNote >> Note.NOTE_SHIFT) & 0xFF);
                // 去重
                long key = ((long) (instrId & 0xFF) << 8) | (nid & 0xFF);
                if (seen.containsKey(key)) continue;
                seen.put(key, true);
                // NBS instrument ID → NoteBlockInstrument enum
                NoteBlockInstrument enumInst = Note.INSTRUMENTS[instrId & 0xFF];
                result.add(new Note(enumInst, nid));
            }
            return result;
        }
        // fallback：transpose=0 或 foldedNotes 未生成
        return song.uniqueNotes;
    }

    public boolean selectSong(Minecraft client, Song song) {
        reset();

        final LocalPlayer player = client.player;
        final ClientLevel world = client.level;
        if (player == null || world == null || song == null) return false;

        // Create list of available noteblock positions per used instrument
        HashMap<NoteBlockInstrument, ArrayList<BlockPos>> noteblocksForInstrument = new HashMap<>();
        for (NoteBlockInstrument instrument : NoteBlockInstrument.values())
            noteblocksForInstrument.put(instrument, new ArrayList<>());
        final Vec3 playerEyePos = player.getEyePosition();

        final int maxOffset;
        if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.v1_20_4_Or_Earlier) {
            maxOffset = 7;
        } else if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.v1_20_5_Or_Later) {
            maxOffset = (int) Math.ceil(player.blockInteractionRange() + 1.0 + 1.0);
        } else if (Main.config.expectedServerVersion == Config.ExpectedServerVersion.All) {
            maxOffset = Math.min(7, (int) Math.ceil(player.blockInteractionRange() + 1.0 + 1.0));
        } else {
            throw new NotImplementedException("ExpectedServerVersion Value not implemented: " + Main.config.expectedServerVersion.name());
        }
        final ArrayList<Integer> orderedOffsets = new ArrayList<>();
        for (int offset = 0; offset <= maxOffset; offset++) {
            orderedOffsets.add(offset);
            if (offset != 0) orderedOffsets.add(offset * -1);
        }

        for (NoteBlockInstrument instrument : noteblocksForInstrument.keySet().toArray(new NoteBlockInstrument[0])) {
            for (int y : orderedOffsets) {
                for (int x : orderedOffsets) {
                    for (int z : orderedOffsets) {
                        Vec3 vec3d = playerEyePos.add(x, y, z);
                        BlockPos blockPos = new BlockPos(Mth.floor(vec3d.x), Mth.floor(vec3d.y), Mth.floor(vec3d.z));
                        if (!Util.canInteractWith(player, blockPos))
                            continue;
                        BlockState blockState = world.getBlockState(blockPos);
                        NoteBlockInstrument blockInstrument = getInstrument(client, blockPos, blockState);
                        if (blockInstrument == null) continue;
                        if (blockInstrument == instrument)
                            noteblocksForInstrument.get(instrument).add(blockPos);
                    }
                }
            }
        }

        // Remap instruments
        if (!instrumentMap.isEmpty()) {
            HashMap<NoteBlockInstrument, ArrayList<BlockPos>> newNoteblocksForInstrument = new HashMap<>();
            for (NoteBlockInstrument orig : noteblocksForInstrument.keySet()) {
                NoteBlockInstrument mappedInstrument = instrumentMap.getOrDefault(orig, orig);
                if (mappedInstrument == null) {
                    newNoteblocksForInstrument.put(orig, null);
                    continue;
                }
                newNoteblocksForInstrument.put(orig, noteblocksForInstrument.getOrDefault(
                        instrumentMap.getOrDefault(orig, orig), new ArrayList<>()));
            }
            noteblocksForInstrument = newNoteblocksForInstrument;
        }

        noteBlocks = new HashMap<>();

        // ============================================================
        // ✅ DJP000016：用 foldedNotes 的 transposed noteIds 绑定
        // 原来：for (Note note : song.uniqueNotes)
        // 现在：for (Note note : getNotesToBind(song))
        // ============================================================
        ArrayList<Note> notesToBind = getNotesToBind(song);

        ArrayList<Note> capturedNotes = new ArrayList<>();
        for (Note note : notesToBind) {
            ArrayList<BlockPos> availableBlocks = noteblocksForInstrument.get(note.instrument());
            if (availableBlocks == null) {
                capturedNotes.add(note);
                getNotes(note.instrument()).put(note.note(), null);
                continue;
            }
            BlockPos bestBlockPos = null;
            int bestBlockTuningSteps = Integer.MAX_VALUE;
            for (BlockPos blockPos : availableBlocks) {
                int wantedNote = note.note();
                int currentNote = client.level.getBlockState(blockPos).getValue(BlockStateProperties.NOTE);
                int tuningSteps = wantedNote >= currentNote ? wantedNote - currentNote : (25 - currentNote) + wantedNote;

                if (tuningSteps < bestBlockTuningSteps) {
                    bestBlockPos = blockPos;
                    bestBlockTuningSteps = tuningSteps;
                }
            }

            if (bestBlockPos != null) {
                capturedNotes.add(note);
                availableBlocks.remove(bestBlockPos);
                getNotes(note.instrument()).put(note.note(), bestBlockPos);
            }
        }

        ArrayList<Note> missingNotes = new ArrayList<>(notesToBind);
        missingNotes.removeAll(capturedNotes);

        missingInstrumentBlocks = new HashMap<>();
        for (Note note : missingNotes) {
            NoteBlockInstrument mappedInstrument = instrumentMap.getOrDefault(note.instrument(), note.instrument());
            if (mappedInstrument == null) continue;
            Block block = Note.INSTRUMENT_BLOCKS.get(mappedInstrument);
            Integer got = missingInstrumentBlocks.get(block);
            if (got == null) got = 0;
            missingInstrumentBlocks.put(block, got + 1);
        }

        if (missingInstrumentBlocks.isEmpty()) {
            selectedSong = song;
            return true;
        } else {
            return false;
        }
    }

    private @Nullable NoteBlockInstrument getInstrument(Minecraft client, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof NoteBlock noteBlock)) return null;

        if (!Main.config.instrumentDetectionWorkaround) {
            NoteBlockInstrument instrument = state.getValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT);
            if (!instrument.worksAboveNoteBlock() && !client.level.isEmptyBlock(pos.above())) return null;
            return instrument;
        }

        NoteBlockInstrument aboveBlockInstrument = client.level.getBlockState(pos.above()).getBlock().defaultBlockState().instrument();
        if (aboveBlockInstrument.worksAboveNoteBlock()) {
            return aboveBlockInstrument;
        } else {
            NoteBlockInstrument belowBlockInstrument = client.level.getBlockState(pos.below()).getBlock().defaultBlockState().instrument();
            if (belowBlockInstrument.worksAboveNoteBlock()) return NoteBlockInstrument.HARP;
            if (!client.level.isEmptyBlock(pos.above())) return null;
            return belowBlockInstrument;
        }
    }

    public enum TuningFail {
        MovedTooFarAway,
        NoSongSelected,
        NotIngame,
        Unexpected,
    }

    private int getOwnPing(Minecraft client) {
        int ping = 0;
        {
            PlayerInfo playerListEntry;
            if (client.getConnection() != null && (playerListEntry = client.getConnection().getPlayerInfo(client.player.getGameProfile().id())) != null)
                ping = playerListEntry.getLatency();
        }
        if (ping <= 0) {
            ping = 150;
        }
        return ping;
    }

    public @Nullable TuningFail tickTuning(Minecraft client) {
        if (tunedAfter != Util.TIMESTAMP_UNINITIALIZED) return null;

        if (client.player == null || client.level == null) return TuningFail.NotIngame;
        if (!isSongSelected()) return TuningFail.NoSongSelected;
        int ping = getOwnPing(client);

        switch (Main.config.tuningSpeed) {
            case Snail -> availableInteracts = Math.clamp(availableInteracts + 0.5f, 0f, 1f);
            case Safe -> availableInteracts = 1;
            case Spigot -> {
                if (lastInteractAt == Util.TIMESTAMP_UNINITIALIZED) {
                    availableInteracts = 9f;
                } else {
                    availableInteracts += ((Util.now() - lastInteractAt) / (310.0f / 9.0f));
                    availableInteracts = Math.min(9f, Math.max(0f, availableInteracts));
                }
            }
            case Flash -> availableInteracts = Integer.MAX_VALUE;
        }

        if (lastInteractAt == Util.TIMESTAMP_UNINITIALIZED)
            lastInteractAt = Util.now();

        // ============================================================
        // ✅ DJP000016：tickTuning 也用 foldedNotes 的 transposed noteIds
        // 原来：for (Note note : selectedSong.uniqueNotes)
        // 现在：for (Note note : getNotesToBind(selectedSong))
        // ============================================================
        ArrayList<Note> tuningNotes = getNotesToBind(selectedSong);

        int fullyTunedBlocks = 0;
        HashMap<BlockPos, Integer> untunedNotes = new HashMap<>();
        for (Note note : tuningNotes) {
            if (noteBlocks == null || noteBlocks.get(note.instrument()) == null)
                continue;
            BlockPos blockPos = noteBlocks.get(note.instrument()).get(note.note());
            if (blockPos == null) continue;
            BlockState blockState = client.level.getBlockState(blockPos);
            int assumedNote = notePredictions.containsKey(blockPos) ? notePredictions.get(blockPos).getKey() : blockState.getValue(BlockStateProperties.NOTE);

            if (blockState.hasProperty(BlockStateProperties.NOTE)) {
                if (assumedNote == note.note() && blockState.getValue(BlockStateProperties.NOTE) == note.note())
                    fullyTunedBlocks++;
                if (assumedNote != note.note()) {
                    if (!Util.canInteractWith(client.player, blockPos))
                        return TuningFail.MovedTooFarAway;
                    untunedNotes.put(blockPos, blockState.getValue(BlockStateProperties.NOTE));
                }
            } else {
                noteBlocks = null;
                break;
            }
        }

        if (tuneInitialUntunedBlocks == -1 || tuneInitialUntunedBlocks < untunedNotes.size())
            tuneInitialUntunedBlocks = untunedNotes.size();

        int existingUniqueNotesCount = 0;
        for (Note n : tuningNotes) {
            if (noteBlocks.get(n.instrument()).get(n.note()) != null)
                existingUniqueNotesCount++;
        }

        if (untunedNotes.isEmpty() && fullyTunedBlocks == existingUniqueNotesCount) {
            if (lastInteractAt == Util.TIMESTAMP_UNINITIALIZED || Util.now() - lastInteractAt >= ping * 2L + 100) {
                tunedAfter = Util.now() + (long) Math.max(0, Main.config.delayPlaybackStartBySecs) * 1000;
                tuneInitialUntunedBlocks = -1;
            }
        }

        BlockPos lastBlockPos = null;
        int lastTunedNote = Integer.MIN_VALUE;
        while (availableInteracts >= 1f && !untunedNotes.isEmpty()) {
            BlockPos blockPos = null;
            int searches = 0;
            while (blockPos == null) {
                searches++;
                for (Map.Entry<BlockPos, Integer> entry : untunedNotes.entrySet()) {
                    if (entry.getValue() > lastTunedNote) {
                        blockPos = entry.getKey();
                        break;
                    }
                }
                if (blockPos == null) {
                    for (Map.Entry<BlockPos, Integer> entry : untunedNotes.entrySet()) {
                        if (entry.getValue() >= lastTunedNote) {
                            blockPos = entry.getKey();
                            break;
                        }
                    }
                }
                if (blockPos == null)
                    lastTunedNote = Integer.MIN_VALUE;
                if (blockPos == null && searches > 1) {
                    blockPos = untunedNotes.keySet().toArray(new BlockPos[0])[0];
                    break;
                }
            }
            if (blockPos == null)
                return TuningFail.Unexpected;

            lastTunedNote = untunedNotes.get(blockPos);
            untunedNotes.remove(blockPos);
            int assumedNote = notePredictions.containsKey(blockPos) ? notePredictions.get(blockPos).getKey() : client.level.getBlockState(blockPos).getValue(BlockStateProperties.NOTE);
            notePredictions.put(blockPos, new AbstractMap.SimpleEntry<>((assumedNote + 1) % 25, Util.now() + ping * 2 + 100));
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(blockPos), Direction.UP, blockPos, false));
            lastInteractAt = Util.now();
            availableInteracts -= 1f;
            lastBlockPos = blockPos;
        }
        if (lastBlockPos != null) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        return null;
    }

    public HashMap<Block, Integer> getMissingInstrumentBlocks() {
        return missingInstrumentBlocks;
    }

    public HashMap<NoteBlockInstrument, HashMap<Byte, BlockPos>> getNoteBlocks() {
        return noteBlocks;
    }
}