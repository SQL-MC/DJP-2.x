package semmiedev.disc_jockey;

import java.util.List;
import java.util.ArrayList;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@me.shedaniel.autoconfig.annotation.Config(name = Main.MOD_ID)
@me.shedaniel.autoconfig.annotation.Config.Gui.Background("textures/block/note_block.png")
public class Config implements ConfigData {

    /** ✅ 正式版必须：配置版本号 */
    public int configVersion = 1;

    public boolean hideWarning;

    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean disableAsyncPlayback = true;

    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean omnidirectionalNoteBlockSounds = true;

    /* ========== ✅ 频谱常驻开关 ========== */
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean spectrumAlwaysVisible = true;

    /* ================== Enum 区 ================== */
    public enum ExpectedServerVersion {
        All,
        v1_20_4_Or_Earlier,
        v1_20_5_Or_Later;

        @Override
        public String toString() {
            return switch (this) {
                case All -> "All (universal)";
                case v1_20_4_Or_Earlier -> "≤1.20.4";
                case v1_20_5_Or_Later -> "≥1.20.5";
            };
        }
    }

    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public ExpectedServerVersion expectedServerVersion = ExpectedServerVersion.All;

    public enum TuningSpeed {
        Snail,
        Safe,
        Spigot,
        Flash;

        @Override
        public String toString() {
            return switch (this) {
                case Snail -> "Snail (10/sec)";
                case Safe -> "Safe (20/sec)";
                case Spigot -> "Spigot (recommended)";
                case Flash -> "Flash";
            };
        }
    }

    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    @ConfigEntry.Gui.Tooltip(count = 7)
    public TuningSpeed tuningSpeed = TuningSpeed.Spigot;

    public enum PlaybackPacketRatelimit {
        Limit100,
        Limit200,
        Limit300,
        Limit500,
        NoLimit;

        @Override
        public String toString() {
            return switch (this) {
                case Limit100 -> "100 Packets/sec";
                case Limit200 -> "200 Packets/sec";
                case Limit300 -> "300 Packets/sec";
                case Limit500 -> "500 Packets/sec";
                case NoLimit -> "No Limit";
            };
        }

        public int getReducePacketsPer100Millis() {
            return switch (this) {
                case Limit100 -> 30 / 10;
                case Limit200 -> 130 / 10;
                case Limit300 -> 200 / 10;
                case Limit500 -> 300 / 10;
                case NoLimit -> Integer.MAX_VALUE;
            };
        }

        public int getMaxPacketsPer100Millis() {
            return switch (this) {
                case Limit100 -> 70 / 10;
                case Limit200 -> 150 / 10;
                case Limit300 -> 250 / 10;
                case Limit500 -> 450 / 10;
                case NoLimit -> Integer.MAX_VALUE;
            };
        }
    }

    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public PlaybackPacketRatelimit playbackPacketRatelimit = PlaybackPacketRatelimit.Limit500;

    @ConfigEntry.Gui.Tooltip(count = 1)
    public float delayPlaybackStartBySecs = 0.0f;

    @ConfigEntry.Gui.Tooltip(count = 3)
    public boolean instrumentDetectionWorkaround = true;

    @ConfigEntry.Gui.Excluded
    public ArrayList<String> favorites = new ArrayList<>();

    @ConfigEntry.Gui.Tooltip(count = 2)
    @ConfigEntry.Gui.RequiresRestart
    public boolean lyricsChatOutput = true;

    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean lyricsOutputToPublic = false;

    @ConfigEntry.Gui.Tooltip(count = 1)
    public long lyricsMinIntervalMs = 200L;

    @ConfigEntry.Gui.Tooltip(count = 1)
    public int lyricsDmBurst = 5;

    @ConfigEntry.Gui.Tooltip(count = 1)
    public int lyricsDmMaxTargets = 10;

    @ConfigEntry.Gui.Tooltip(count = 1)
    public int lyricsDmRadius = 20;

    @ConfigEntry.Gui.Tooltip(count = 1)
    public String lyricsCommand = "msg";

    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean lyricsUseSelector = true;

    /** 播放列表（存储歌曲的 relativePath） */
    public List<String> playlist = new ArrayList<>();

    /** 播放列表循环模式 */
    public enum RepeatMode {
        SEQUENTIAL,   // 顺序播完即停
        PLAYLIST,     // 播完列表从头循环
        SINGLE        // 单曲循环
    }

    /** 播放列表循环模式，默认 SEQUENTIAL */
    public RepeatMode repeatMode = RepeatMode.SEQUENTIAL;   // ← 原来是 null

    /** 是否随机播放 */
    public boolean shufflePlaylist = false;

    /* ========== ✅ Modrinth 项目标识（供 /discjockey update 使用）==========
       ✅ 公开项目查询无需 API key；填你的 Modrinth slug（如 disc-jockey-plus）或 project ID */
    public String modrinthProjectId = "disc-jockey-plus";

    /* ========== ✅ 26.3：反序列化兜底，修复旧存档里的 null 字段 ========== */
    @Override
    public void validatePostLoad() {
        if (repeatMode == null) {
            repeatMode = RepeatMode.SEQUENTIAL;
        }
        if (playbackPacketRatelimit == null) {
            playbackPacketRatelimit = PlaybackPacketRatelimit.Limit500;
        }
        if (expectedServerVersion == null) {
            expectedServerVersion = ExpectedServerVersion.All;
        }
        if (tuningSpeed == null) {
            tuningSpeed = TuningSpeed.Spigot;
        }
        if (playlist == null) {
            playlist = new ArrayList<>();
        }
        if (favorites == null) {
            favorites = new ArrayList<>();
        }
    }
    /* ==================================================================== */
}