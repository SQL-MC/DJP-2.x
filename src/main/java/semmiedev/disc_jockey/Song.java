package semmiedev.disc_jockey;

import java.util.ArrayList;
import semmiedev.disc_jockey.gui.SongListWidget;

public class Song {

    /** ✅ 原始 NBS 音符（永不修改，永不写盘） */
    public long[] notes = new long[0];

    /** ✅ 运行时八度折叠缓存（由 NoteClamper 生成，播放优先使用） */
    public long[] foldedNotes = null;

    public short length;
    public short height;
    public short tempo;
    public short loopStartTick;

    public String fileName;
    public String name;
    public String author;
    public String originalAuthor;
    public String description;
    public String displayName;

    public byte autoSaving;
    public byte autoSavingDuration;
    public byte timeSignature;
    public byte vanillaInstrumentCount;
    public byte formatVersion;
    public byte loop;
    public byte maxLoopCount;

    public int minutesSpent;
    public int leftClicks;
    public int rightClicks;
    public int blocksAdded;
    public int blocksRemoved;

    public String importFileName;

    public final ArrayList<Note> uniqueNotes = new ArrayList<>();

    public SongListWidget.SongEntry entry;

    public String searchableFileName;
    public String searchableName;

    @Override
    public String toString() {
        return this.displayName;
    }

    public double millisecondsToTicks(long milliseconds) {
        double songSpeed = this.tempo / 100.0D / 20.0D;
        double oneMsTo20TickFraction = 0.02D;
        return milliseconds * oneMsTo20TickFraction * songSpeed;
    }

    public double ticksToMilliseconds(double ticks) {
        double songSpeed = this.tempo / 100.0D / 20.0D;
        double oneMsTo20TickFraction = 0.02D;
        return ticks / oneMsTo20TickFraction / songSpeed;
    }

    public double getLengthInSeconds() {
        return ticksToMilliseconds(this.length) / 1000.0D;
    }
}