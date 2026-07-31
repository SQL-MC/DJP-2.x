package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

public class Previewer implements ClientTickEvents.StartLevelTick {

    private static final Previewer INSTANCE = new Previewer();

    public static float previewVolume = 3.0F;
    public static boolean previewMute = false;
    public static boolean loopPreview = false;
    public static boolean running = false;

    private int i;
    private float tick;
    private Song song;

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

    /**
     * ✅ 八度折叠（与 NoteClamper 数学一致）
     */
    private int applyTranspose(int rawNoteId) {
        int result = rawNoteId;
        result = ((result % 25) + 25) % 25;
        return result;
    }

    public static void start(Song song) {
        if (song == null || song.notes == null || song.notes.length == 0) return;

        // ✅【互斥】Preview 启动，先停 DJ（唯一新增行）
        Main.SONG_PLAYER.stop();

        INSTANCE.song = song;
        INSTANCE.i = 0;
        INSTANCE.tick = 0.0F;

        // ✅ Preview 也生成运行时折叠缓存
        NoteClamper.buildFoldedNotes(song, 0);

        if (!running) {
            Main.TICK_LISTENERS.add(INSTANCE);
            running = true;
        }
    }

    public static void stop() {
        if (!running) return;

        running = false;
        INSTANCE.i = 0;
        INSTANCE.tick = 0.0F;
        INSTANCE.song = null;

        Main.TICK_LISTENERS.remove(INSTANCE);
    }

    /** ✅ 统一取音符：优先 foldedNotes */
    private long getNote(int idx) {
        if (song == null) return 0L;
        if (NoteClamper.hasFoldedNotes(song)) {
            return song.foldedNotes[idx];
        }
        return song.notes[idx];
    }

    private int getNoteCount() {
        if (song == null) return 0;
        if (NoteClamper.hasFoldedNotes(song)) {
            return song.foldedNotes.length;
        }
        return song.notes.length;
    }

    @Override
    public void onStartTick(ClientLevel world) {
        if (!running || song == null || getNoteCount() == 0) return;

        while (i < getNoteCount()) {
            long note = getNote(i);
            if ((short) note > Math.round(tick)) break;

            int instrumentId = (int) (note >> 32L) & 0xFF;
            int rawNoteId = (int) (note >> 40L) & 0xFF;

            int noteId = applyTranspose(rawNoteId);

            if (instrumentId < 0 || instrumentId >= NoteBlockInstrument.values().length) {
                i++;
                continue;
            }

            NoteBlockInstrument instrument = NoteBlockInstrument.values()[instrumentId];
            var sound = instrument.getSoundEvent();

            if (sound == null || !sound.isBound()) {
                i++;
                continue;
            }

            Vec3 pos = Minecraft.getInstance().player.position();
            float finalVolume = previewMute ? 0.0F : previewVolume;

            world.playLocalSound(
                    pos.x(), pos.y(), pos.z(),
                    sound.value(),
                    SoundSource.RECORDS,
                    finalVolume,
                    (float) Math.pow(2.0D, (noteId - 12) / 12.0D),
                    false
            );

            Main.SPECTRUM.onNotePlayed(instrumentId & 0xFF, noteId);

            i++;
            if (i >= getNoteCount()) {
                if (loopPreview) {
                    this.i = 0;
                    this.tick = 0.0F;
                    return;
                }
                stop();
                return;
            }
        }

        tick += song.tempo / 100.0F / 20.0F;
    }
}