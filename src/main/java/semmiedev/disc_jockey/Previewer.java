package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Previewer implements ClientTickEvents.StartLevelTick {

    private static final Previewer INSTANCE = new Previewer();
    private static final Logger LOGGER = LogManager.getLogger("Disc Jockey");

    public static float previewVolume = 3.0F;
    public static boolean previewMute = false;
    public static boolean loopPreview = false;
    public static boolean running = false;
    public static boolean continuousPreview = true; // ✅ 联播开关：true=列表循环，false=播完最后一首就停

    private int i;
    private float tick;
    private Song song;
    private ClientLevel prevWorld = null;

    // ✅ 静态块：主菜单 tick（只在无世界时执行）—— 原样保留
    static {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (running && mc.level == null) {
                INSTANCE.tickAndPlay(mc.level);
            }
        });
    }

    public Previewer() {}

    public static Previewer getInstance() {
        return INSTANCE;
    }

    public Song getSong() {
        return song;
    }

    public int getI() {
        return i;
    }

    private int foldToRange(int noteId) {
        int result = noteId;
        while (result > 24) result -= 12;
        while (result < 0)  result += 12;
        if (result < 0)  result = 0;
        if (result > 24) result = 24;
        return result;
    }

    public static void rebuildTranspose() {
        // 空操作，实时转调
    }

    // ✅ 改进：获取下一首歌（带日志，返回 null 只在列表为空时）
    private Song getNextSong() {
        if (SongLoader.SONGS.isEmpty()) {
            LOGGER.warn("[DJ] getNextSong: SONGS list is empty!");
            return null;
        }
        int size = SongLoader.SONGS.size();
        if (this.song == null) {
            LOGGER.info("[DJ] getNextSong: current song is null, returning first");
            return SongLoader.SONGS.get(0);
        }
        int idx = SongLoader.SONGS.indexOf(this.song);
        if (idx < 0) {
            LOGGER.warn("[DJ] getNextSong: current song not found in SONGS list (idx=-1), returning first");
            return SongLoader.SONGS.get(0); // 找不到就从头播
        }
        if (idx < size - 1) {
            Song next = SongLoader.SONGS.get(idx + 1);
            LOGGER.info("[DJ] getNextSong: idx={}, next='{}'", idx, next.displayName);
            return next;
        }
        // 到列表末尾
        if (continuousPreview) {
            LOGGER.info("[DJ] getNextSong: at end, looping back to first");
            return SongLoader.SONGS.get(0);
        }
        LOGGER.info("[DJ] getNextSong: at end, continuousPreview=false, stopping");
        return null;
    }

    // ========== start() —— 原样保留 ==========
    public static void start(Song song) {
        if (song == null || song.notes == null || song.notes.length == 0) {
            LOGGER.warn("[DJ] Preview start rejected: song null or empty");
            return;
        }

        LOGGER.info("[DJ] Preview start: notes={}", song.notes.length);

        if (running) {
            stop();
        }

        Main.SONG_PLAYER.stop();

        INSTANCE.song = song;
        INSTANCE.i = 0;
        INSTANCE.tick = 0.0F;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            INSTANCE.prevWorld = mc.level;
        } else {
            INSTANCE.prevWorld = null; // 主菜单
        }

        Main.TICK_LISTENERS.add(INSTANCE);
        running = true;
    }

    // ========== stop() —— 原样保留 ==========
    public static void stop() {
        if (!running) return;

        LOGGER.info("[DJ] Preview stop");
        running = false;
        INSTANCE.i = 0;
        INSTANCE.tick = 0.0F;
        INSTANCE.song = null;

        INSTANCE.prevWorld = null;

        Main.TICK_LISTENERS.remove(INSTANCE);

        if (Main.SPECTRUM != null && Main.SPECTRUM.currentLevels != null) {
            for (int k = 0; k < Main.SPECTRUM.currentLevels.length; k++) {
                Main.SPECTRUM.currentLevels[k] = 0f;
            }
        }
        Main.SMOOTHER.reset();
    }

    private long getNote(int idx) {
        if (song == null) return 0L;
        return song.notes[idx];
    }

    private int getNoteCount() {
        if (song == null) return 0;
        return song.notes.length;
    }

    // ========== tickAndPlay —— 联播逻辑改为直接切歌，不走 start() ==========
    private void tickAndPlay(ClientLevel world) {
        if (!running) return;
        if (song == null || getNoteCount() == 0) return;

        while (i < getNoteCount()) {
            long note = getNote(i);

            int tickField = (int)(note & 0xFFFFL);
            if (tickField > Math.round(tick)) break;

            int instrumentId = (int) (note >> 32L) & 0xFF;
            int rawNoteId = Note.extractNoteId(note);
            int transposedNoteId = rawNoteId + Main.SONG_PLAYER.transpose;
            int noteId = foldToRange(transposedNoteId);

            if (instrumentId < 0 || instrumentId >= NoteBlockInstrument.values().length) {
                i++;
                continue;
            }

            NoteBlockInstrument instrument = NoteBlockInstrument.values()[instrumentId];
            var soundHolder = instrument.getSoundEvent();

            SoundEvent sound;
            if (soundHolder != null && soundHolder.isBound()) {
                sound = soundHolder.value();
            } else {
                sound = SoundEvents.NOTE_BLOCK_HARP.value();
            }

            Vec3 pos = Minecraft.getInstance().player != null
                    ? Minecraft.getInstance().player.position()
                    : Vec3.ZERO;
            float finalVolume = previewMute ? 0.0F : previewVolume;
            float pitch = (float) Math.pow(2.0D, (noteId - 12) / 12.0D);

            playSoundSafe(world, pos, sound, SoundSource.RECORDS, finalVolume, pitch);

            Main.SPECTRUM.onNotePlayed(instrumentId & 0xFF, noteId);

            i++;
            if (i >= getNoteCount()) {
                if (loopPreview) {
                    // 单曲循环
                    this.i = 0;
                    this.tick = 0.0F;
                    return;
                }
                // ✅ 联播逻辑：直接切歌，不走 start()（避免 stop() 副作用）
                Song next = getNextSong();
                if (next != null) {
                    LOGGER.info("[DJ] Preview switching to next song: {}", next.displayName);
                    INSTANCE.song = next;
                    INSTANCE.i = 0;
                    INSTANCE.tick = 0.0F;
                    // 不清 prevWorld，不移除 TICK_LISTENERS，不碰 running
                    // 下一帧 while 循环会用新 song 继续
                } else {
                    LOGGER.info("[DJ] Preview: no next song, stopping");
                    stop();
                }
                return;
            }
        }

        tick += song.tempo / 100.0F / 20.0F * Main.PREVIEW_SPEED;
    }

    // ========== playSoundSafe —— 原样保留 ==========
    private void playSoundSafe(ClientLevel world, Vec3 pos, SoundEvent sound,
                               SoundSource source, float volume, float pitch) {
        if (sound == null) return;
        float safeVolume = Math.min(volume, 1.0f);

        if (world != null) {
            world.playLocalSound(pos.x(), pos.y(), pos.z(), sound, source, safeVolume, pitch, false);
        } else {
            try {
                Minecraft mc = Minecraft.getInstance();
                SimpleSoundInstance instance = SimpleSoundInstance.forUI(sound, pitch);
                mc.getSoundManager().play(instance);
            } catch (Throwable t) {
                LOGGER.warn("[DJ] Main menu preview play failed: {}", t.getMessage(), t);
            }
        }
    }

    // ========== onStartTick —— 原样保留 ==========
    @Override
    public void onStartTick(ClientLevel world) {
        if (prevWorld == null && world != null) {
            LOGGER.info("[DJ] Entered world, stopping main menu preview");
            stop();
            prevWorld = world;
            return;
        }

        if (world != null && Minecraft.getInstance().level == null) {
            prevWorld = world;
            return;
        }

        if (world == null) {
            prevWorld = null;
        } else {
            prevWorld = world;
        }
        tickAndPlay(world);
    }

    public static void tickClient() {
        if (!running) return;
        INSTANCE.tickAndPlay(Minecraft.getInstance().level);
    }
}