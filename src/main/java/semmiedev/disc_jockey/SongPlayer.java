package semmiedev.disc_jockey;
import net.minecraft.world.item.component.SwingAnimation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

public class SongPlayer implements ClientTickEvents.StartLevelTick {

    private static boolean warned;
    public boolean running;
    public Song song;

    private int index;
    private double tick;
    private long lastPlaybackTickAt = Util.TIMESTAMP_UNINITIALIZED;
    private Thread playbackThread = null;
    public long playbackLoopDelay = 5;
    public float speed = 1.0f;
    public boolean didSongReachEnd = false;
    public boolean loopSong = false;
    public boolean autoPlay = false;
    public boolean shuffle = false;
    public boolean paused = false;
    private long sleepEndTimeMs = -1;
    private final RateLimiter rateLimiter = new RateLimiter();
    public final Tuner tuner = new Tuner();

    public int transpose = 0;
    public final ArrayList<Song> shuffleQueue = new ArrayList<>();

    /**
     * ✅ 运行时 transpose（委托给 NoteClamper，保证算法一致）
     */
    public int applyTranspose(int rawNoteId) {
        return NoteClamper.applyTranspose(rawNoteId, this.transpose);
    }

    public SongPlayer() {
        Main.TICK_LISTENERS.add(this);
    }

    public synchronized void startPlaybackThread() {
        if (Main.config.disableAsyncPlayback) {
            playbackThread = null;
            return;
        }
        this.playbackThread = new Thread(() -> {
            Thread ownThread = this.playbackThread;
            while (ownThread == this.playbackThread) {
                try { Thread.sleep(playbackLoopDelay); } catch (InterruptedException ignored) {}
                tickPlayback();
            }
        });
        this.playbackThread.start();
    }

    public synchronized void stopPlaybackThread() {
        this.playbackThread = null;
    }

    public synchronized void start(Song song) {
        if (!Main.config.hideWarning && !warned) {
            Minecraft.getInstance().gui.chatListener().handleSystemMessage(
                    Component.translatable("disc_jockey.warning").withStyle(ChatFormatting.BOLD, ChatFormatting.RED), false);
            warned = true;
            return;
        }
        if (running) stop();

        // ✅【互斥】DJ 启动，先停 Preview
        Main.PREVIEWER.stop();

        tick = 0;
        index = 0;
        this.song = song;

        // ✅ 安全：先清除旧缓存，再生成新缓存
        if (song != null) {
            NoteClamper.clearFoldedNotes(song);
            NoteClamper.buildFoldedNotes(song, this.transpose);
        }

        if (this.playbackThread == null) startPlaybackThread();
        running = true;
        paused = false;
        shuffleQueue.clear();
        rateLimiter.reset();
        tuner.reset();
        didSongReachEnd = false;
    }

    public synchronized void stop() {
        stopPlaybackThread();
        running = false;
        paused = false;
        sleepEndTimeMs = -1;
        shuffleQueue.clear();
        index = 0;
        tick = 0;
        rateLimiter.reset();
        tuner.reset();
        didSongReachEnd = false;
    }

    public void togglePause() {
        if (!running) return;
        paused = !paused;
        if (paused) {
            lastPlaybackTickAt = Util.TIMESTAMP_UNINITIALIZED;
        }
    }

    public void setSleepTimer(int minutes) {
        if (minutes <= 0) {
            sleepEndTimeMs = -1;
            return;
        }
        sleepEndTimeMs = System.currentTimeMillis() + minutes * 60L * 1000L;
        Main.LOGGER.info("Sleep timer set: " + minutes + " min");
    }

    private void checkSleepTimer() {
        if (sleepEndTimeMs > 0 && System.currentTimeMillis() >= sleepEndTimeMs) {
            Main.LOGGER.info("Sleep timer triggered — stopping");
            stop();
            Minecraft.getInstance().gui.chatListener().handleSystemMessage(
                    Component.literal("⏰ 定时停止：已到时间，播放已停止"), false);
            sleepEndTimeMs = -1;
        }
    }

    /**
     * ✅ 统一取音符
     * - 优先 foldedNotes（已含 transpose + fold）
     * - 否则回退原始 notes
     */
    private long getNote(int idx) {
        if (song == null) return 0L;
        if (NoteClamper.hasFoldedNotes(song)) {
            return song.foldedNotes[idx];
        }
        return song.notes[idx];
    }

    /**
     * ✅ DJP000016 修复：多层 fallback 查找方块
     * 
     * 问题：transpose 后，目标 (instrument, noteId) 在 Tuner 里可能没有方块
     *       → blockPos == null → index++ 跳过 → 静音
     * 
     * 修复：依次尝试
     *   1. 直接查找 transposedNoteId
     *   2. 降八度 (noteId - 12)
     *   3. 升八度 (noteId + 12)
     *   4. 八度折叠回 0~24
     * 
     * 这样即使 Tuner 没有精确匹配的方块，也能找到"同乐器、最近八度"的方块
     * → 不会静音，最多差一个八度（听起来仍然和谐）
     */
    private @Nullable BlockPos findNoteBlock(byte instrumentId, int noteId) {
        var instrumentMap = tuner.getNoteBlocks().get(Note.INSTRUMENTS[instrumentId]);
        if (instrumentMap == null) return null;
        return instrumentMap.get((byte) noteId);
    }

    public synchronized void tickPlayback() {
        if (!running) {
            lastPlaybackTickAt = Util.TIMESTAMP_UNINITIALIZED;
            rateLimiter.reset();
            return;
        }

        checkSleepTimer();

        if (paused) {
            return;
        }

        long previousPlaybackTickAt = lastPlaybackTickAt;
        lastPlaybackTickAt = Util.now();
        rateLimiter.tick();

        if (!tuner.isTuned()) return;

        while (running && !paused) {
            Minecraft client = Minecraft.getInstance();
            GameType gameMode = client.gameMode == null ? null : client.gameMode.getPlayerMode();
            if (gameMode == null || !gameMode.isSurvival()) {
                client.gui.chatListener().handleSystemMessage(
                        Component.translatable(Main.MOD_ID + ".player.invalid_game_mode",
                                        gameMode == null ? "unknown" : gameMode.getLongDisplayName())
                                .withStyle(ChatFormatting.RED), false);
                stop();
                return;
            }

            long note = getNote(index);

            if ((short) note > Math.round(tick)) break;

            // ✅ 从 foldedNotes 读出（已含 transpose + fold）
            byte instrumentId = (byte) (note >> Note.INSTRUMENT_SHIFT);
            int transposedNoteId = (int) (note >> Note.NOTE_SHIFT) & 0xFF;

            // ✅【DJP000016】多层 fallback 查找方块
            BlockPos blockPos = findNoteBlock(instrumentId, transposedNoteId);

            // Fallback 1: 降八度（高音区没方块 → 试低八度）
            if (blockPos == null && transposedNoteId >= 12) {
                blockPos = findNoteBlock(instrumentId, transposedNoteId - 12);
            }

            // Fallback 2: 升八度（低音区没方块 → 试高八度）
            if (blockPos == null && transposedNoteId <= 12) {
                blockPos = findNoteBlock(instrumentId, transposedNoteId + 12);
            }

            // Fallback 3: 八度折叠（都没 → 折叠到 0~24 内再试）
            if (blockPos == null) {
                int folded = ((transposedNoteId % 25) + 25) % 25;
                if (folded != transposedNoteId) {
                    blockPos = findNoteBlock(instrumentId, folded);
                }
            }

            // ✅ 都找不到才跳过（极小概率：这个乐器在 Tuner 里完全没有方块）
            if (blockPos == null) {
                index++;
                continue;
            }

            if (!Util.canInteractWith(client.player, blockPos)) {
                stop();
                client.gui.chatListener().handleSystemMessage(
                        Component.translatable(Main.MOD_ID + ".player.too_far").withStyle(ChatFormatting.RED), false);
                return;
            }

            // ✅ 频谱喂原始 noteId（不是 transposed 的）
            int originalNoteId = (int) (song.notes[index] >> Note.NOTE_SHIFT) & 0xFF;
            Main.SPECTRUM.onNotePlayed(instrumentId & 0xFF, originalNoteId);

            Vec3 unit = Vec3.upFromBottomCenterOf(blockPos, 0.5)
                    .subtract(client.player.getEyePosition()).normalize();

            if (rateLimiter.canSendLookPacket() && PacketThrottle.canSend() && PacketThrottle.canSendInterval()) {
                client.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                        Mth.wrapDegrees((float) (Mth.atan2(unit.z, unit.x) * 57.2957763671875 - 90.0f)),
                        Mth.wrapDegrees((float) (-(Mth.atan2(unit.y, Math.sqrt(unit.x * unit.x + unit.z * unit.z)) * 57.2957763671875))),
                        client.player.onGround(), client.player.horizontalCollision));
                rateLimiter.onLookPacketSent();
            }

            if (rateLimiter.canSendAnyPacket() && PacketThrottle.canSend() && PacketThrottle.canSendInterval()) {
                client.player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                rateLimiter.onPacketSent();
            }

            if (rateLimiter.canSendCosmeticPacket() && PacketThrottle.canSend() && PacketThrottle.canSendInterval()) {
                client.player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                rateLimiter.onPacketSent();
            }

            if (rateLimiter.canSendSwingPacket() && PacketThrottle.canSend() && PacketThrottle.canSendInterval()) {
                client.executeIfPossible(() -> client.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false));
                rateLimiter.onSwingPacketSent();
            }

            index++;
            if (index >= getNoteCount()) {
                stop();
                didSongReachEnd = true;

                if (autoPlay && !SongLoader.SONGS.isEmpty()) {
                    Song next = shuffle ? pickShuffleNext() : pickRandomNext();
                    if (next != null) {
                        Main.LOGGER.info("Auto-playing next song: " + next.displayName);
                        start(next);
                    }
                } else if (loopSong) {
                    start(song);
                }
                break;
            }
        }

        if (running && !paused) {
            long elapsedMs = (previousPlaybackTickAt != -1L && lastPlaybackTickAt != -1L)
                    ? lastPlaybackTickAt - previousPlaybackTickAt
                    : 16;
            tick += song.millisecondsToTicks(elapsedMs) * speed;
        }
    }

    private int getNoteCount() {
        if (NoteClamper.hasFoldedNotes(song)) {
            return song.foldedNotes.length;
        }
        return song.notes.length;
    }

    private Song pickRandomNext() {
        if (SongLoader.SONGS.size() == 1) return SongLoader.SONGS.get(0);
        ArrayList<Song> pool = new ArrayList<>(SongLoader.SONGS);
        pool.removeIf(s -> s == this.song);
        if (pool.isEmpty()) return null;
        return pool.get(new Random().nextInt(pool.size()));
    }

    private Song pickShuffleNext() {
        if (shuffleQueue.isEmpty()) {
            shuffleQueue.addAll(SongLoader.SONGS);
            shuffleQueue.removeIf(s -> s == this.song);
            if (shuffleQueue.isEmpty()) return null;
            Collections.shuffle(shuffleQueue, new Random());
        }
        return shuffleQueue.remove(0);
    }

    @Override
    public void onStartTick(ClientLevel world) {
        Minecraft client = Minecraft.getInstance();
        if (world == null || client.level == null || client.player == null) return;
        if (song == null || !running) return;

        tuner.cleanup();

        if (!tuner.isSongSelected()) {
            if (!tuner.selectSong(client, song)) {
                if (!tuner.getMissingInstrumentBlocks().isEmpty()) {
                    client.gui.chatListener().handleSystemMessage(
                            Component.translatable(Main.MOD_ID + ".player.invalid_note_blocks").withStyle(ChatFormatting.RED), false);
                    tuner.getMissingInstrumentBlocks().forEach((block, integer) ->
                            client.gui.chatListener().handleSystemMessage(
                                    Component.literal(block.getName().getString() + " × " + integer).withStyle(ChatFormatting.RED), false));
                    stop();
                    return;
                } else {
                    Main.LOGGER.error("Failed to select song to unknown / unexpected reason!");
                    client.gui.chatListener().handleSystemMessage(
                            Component.translatable(Main.MOD_ID + ".selectsong_fail_unknown").withStyle(ChatFormatting.RED), false);
                    stop();
                    return;
                }
            } else {
                Main.LOGGER.info("Selected song: " + song.displayName + " (" + song.fileName + ")");
            }
        }

        if (!tuner.isTuned()) {
            Tuner.TuningFail tuningFail = tuner.tickTuning(client);
            if (tuningFail == Tuner.TuningFail.MovedTooFarAway) {
                stop();
                client.gui.chatListener().handleSystemMessage(
                        Component.translatable(Main.MOD_ID + ".player.too_far").withStyle(ChatFormatting.RED), false);
                return;
            } else if (tuningFail != null) {
                stop();
                Main.LOGGER.error("Tuning song failed: " + tuningFail.name());
                client.gui.chatListener().handleSystemMessage(
                        Component.translatable(Main.MOD_ID + ".player.tuning_fail_other", tuningFail.name()).withStyle(ChatFormatting.RED), false);
                return;
            }
        }

        if (tuner.isTuned() && (playbackThread == null || !playbackThread.isAlive()) && running && Main.config.disableAsyncPlayback) {
            try {
                tickPlayback();
            } catch (Exception ex) {
                Main.LOGGER.error("Failed to tick playback synchronously!", ex);
                stop();
            }
        }
    }

    public void setSongElapsedSeconds(double seconds) {
        tick = song.millisecondsToTicks((long) seconds * 1000);
        index = 0;
        int count = getNoteCount();
        for (int i = 0; i < count; i++) {
            if ((short) getNote(i) >= Math.round(tick)) {
                index = i;
                break;
            }
        }
    }

    public double getSongElapsedSeconds() {
        if (song == null) return 0;
        return song.ticksToMilliseconds(tick) / 1000;
    }

    public int getIndex() {
        return index;
    }

    public float[] getSpectrumLevels(int bands) {
        float[] levels = new float[bands];
        return levels;
    }

    public Song getSong() {
        return song;
    }
}