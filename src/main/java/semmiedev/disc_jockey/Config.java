package semmiedev.disc_jockey;

import com.google.common.collect.Lists;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NeoForge 26.3 配置：AutoConfig(8 个 @Config 注解) -> ModConfigSpec。
 *
 * 关键约束：其他 41 个文件通过 Main.config.xxx 直接读取字段，
 * 因此字段名、类型、默认值必须原样保留，不得改为 ModConfigSpec.BooleanValue 之类。
 * 做法：SPEC 负责持久化；加载 / 重载时 syncFromSpec() 把值写回 INSTANCE 的 public 字段。
 */
public class Config {

    public static final ModConfigSpec SPEC;
    public static final Config INSTANCE = new Config();

    // ===== SPEC 值对象 =====
    private static final ModConfigSpec.IntValue    SPEC_configVersion;
    private static final ModConfigSpec.BooleanValue SPEC_disableAsyncPlayback;
    private static final ModConfigSpec.BooleanValue SPEC_omnidirectionalNoteBlockSounds;
    private static final ModConfigSpec.BooleanValue SPEC_spectrumAlwaysVisible;
    private static final ModConfigSpec.EnumValue<ExpectedServerVersion> SPEC_expectedServerVersion;
    private static final ModConfigSpec.EnumValue<TuningSpeed> SPEC_tuningSpeed;
    private static final ModConfigSpec.EnumValue<PlaybackPacketRatelimit> SPEC_playbackPacketRatelimit;
    private static final ModConfigSpec.DoubleValue SPEC_delayPlaybackStartBySecs;
    private static final ModConfigSpec.BooleanValue SPEC_instrumentDetectionWorkaround;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> SPEC_favorites;
    private static final ModConfigSpec.BooleanValue SPEC_lyricsChatOutput;
    private static final ModConfigSpec.BooleanValue SPEC_lyricsOutputToPublic;
    private static final ModConfigSpec.LongValue   SPEC_lyricsMinIntervalMs;
    private static final ModConfigSpec.IntValue    SPEC_lyricsDmBurst;
    private static final ModConfigSpec.IntValue    SPEC_lyricsDmMaxTargets;
    private static final ModConfigSpec.IntValue    SPEC_lyricsDmRadius;
    private static final ModConfigSpec.ConfigValue<String> SPEC_lyricsCommand;
    private static final ModConfigSpec.BooleanValue SPEC_lyricsUseSelector;
    private static final ModConfigSpec.EnumValue<RepeatMode> SPEC_repeatMode;
    private static final ModConfigSpec.ConfigValue<String> SPEC_modrinthProjectId;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        SPEC_configVersion = b
                .comment("Config file version. Do not edit manually.")
                .defineInRange("configVersion", 1, Integer.MIN_VALUE, Integer.MAX_VALUE);

        SPEC_disableAsyncPlayback = b
                .comment("Disable the asynchronous playback thread; playback then runs on the client tick.")
                .define("disableAsyncPlayback", true);

        SPEC_omnidirectionalNoteBlockSounds = b
                .comment("Play note block sounds from all directions instead of directional audio.")
                .define("omnidirectionalNoteBlockSounds", true);

        SPEC_spectrumAlwaysVisible = b
                .comment("Always show the spectrum visualizer HUD (main menu and in-game).")
                .define("spectrumAlwaysVisible", true);

        SPEC_expectedServerVersion = b
                .comment("Expected server version behaviour.")
                .defineEnum("expectedServerVersion", ExpectedServerVersion.All);

        SPEC_tuningSpeed = b
                .comment("How fast the tuner retunes note blocks.")
                .defineEnum("tuningSpeed", TuningSpeed.Spigot);

        SPEC_playbackPacketRatelimit = b
                .comment("Maximum playback packet rate.")
                .defineEnum("playbackPacketRatelimit", PlaybackPacketRatelimit.Limit500);

        SPEC_delayPlaybackStartBySecs = b
                .comment("Delay playback start by this many seconds.")
                .defineInRange("delayPlaybackStartBySecs", 0.0, -3600.0, 3600.0);

        SPEC_instrumentDetectionWorkaround = b
                .comment("Work around servers that report wrong note block instruments.")
                .define("instrumentDetectionWorkaround", true);

        SPEC_favorites = b
                .comment("Favourite song names.")
                .defineListAllowEmpty("favorites",
                        () -> new ArrayList<>(),
                        () -> "",
                        o -> o instanceof String);

        SPEC_lyricsChatOutput = b
                .comment("Output lyrics to chat.")
                .define("lyricsChatOutput", true);

        SPEC_lyricsOutputToPublic = b
                .comment("Send lyrics to public chat instead of DM.")
                .define("lyricsOutputToPublic", false);

        SPEC_lyricsMinIntervalMs = b
                .comment("Minimum interval between lyric messages, in milliseconds.")
                .defineInRange("lyricsMinIntervalMs", 200L, 0L, 600000L);

        SPEC_lyricsDmBurst = b.defineInRange("lyricsDmBurst", 5, 1, 100);
        SPEC_lyricsDmMaxTargets = b.defineInRange("lyricsDmMaxTargets", 10, 1, 100);
        SPEC_lyricsDmRadius = b.defineInRange("lyricsDmRadius", 20, 1, 256);

        SPEC_lyricsCommand = b
                .comment("Command used to DM lyrics (without leading slash).")
                .define("lyricsCommand", "msg");

        SPEC_lyricsUseSelector = b.define("lyricsUseSelector", true);

        SPEC_repeatMode = b.defineEnum("repeatMode", RepeatMode.SEQUENTIAL);

        SPEC_modrinthProjectId = b.define("modrinthProjectId", "disc-jockey-plus");

        SPEC = b.build();
    }

    // ==================== 以下字段全部保持原样（其他文件直接访问） ====================

    public int configVersion = 1;

    public boolean disableAsyncPlayback = true;

    public boolean hideWarning;
    public boolean omnidirectionalNoteBlockSounds = true;

    public boolean spectrumAlwaysVisible = true;

    public ExpectedServerVersion expectedServerVersion = ExpectedServerVersion.All;

    public TuningSpeed tuningSpeed = TuningSpeed.Spigot;

    public PlaybackPacketRatelimit playbackPacketRatelimit = PlaybackPacketRatelimit.Limit500;

    public float delayPlaybackStartBySecs = 0.0f;

    public boolean instrumentDetectionWorkaround = true;

    public ArrayList<String> favorites = new ArrayList<>();

    public boolean lyricsChatOutput = true;

    public boolean lyricsOutputToPublic = false;

    public long lyricsMinIntervalMs = 200L;

    public int lyricsDmBurst = 5;

    public int lyricsDmMaxTargets = 10;

    public int lyricsDmRadius = 20;

    public String lyricsCommand = "msg";

    public boolean lyricsUseSelector = true;

    public RepeatMode repeatMode = RepeatMode.SEQUENTIAL;

    public String modrinthProjectId = "disc-jockey-plus";

    // ==================== SPEC <-> 实例同步 ====================

    /** 从 SPEC 读回实例字段。配置加载与重载时调用。 */
    public static void syncFromSpec() {
        Config c = INSTANCE;
        c.configVersion                    = SPEC_configVersion.get();
        c.disableAsyncPlayback             = SPEC_disableAsyncPlayback.get();
        c.omnidirectionalNoteBlockSounds   = SPEC_omnidirectionalNoteBlockSounds.get();
        c.spectrumAlwaysVisible            = SPEC_spectrumAlwaysVisible.get();
        c.expectedServerVersion            = SPEC_expectedServerVersion.get();
        c.tuningSpeed                      = SPEC_tuningSpeed.get();
        c.playbackPacketRatelimit          = SPEC_playbackPacketRatelimit.get();
        c.delayPlaybackStartBySecs         = SPEC_delayPlaybackStartBySecs.get().floatValue();
        c.instrumentDetectionWorkaround    = SPEC_instrumentDetectionWorkaround.get();
        c.favorites                        = new ArrayList<>(
                Lists.newArrayList(SPEC_favorites.get()));
        c.lyricsChatOutput                 = SPEC_lyricsChatOutput.get();
        c.lyricsOutputToPublic             = SPEC_lyricsOutputToPublic.get();
        c.lyricsMinIntervalMs              = SPEC_lyricsMinIntervalMs.get();
        c.lyricsDmBurst                    = SPEC_lyricsDmBurst.get();
        c.lyricsDmMaxTargets               = SPEC_lyricsDmMaxTargets.get();
        c.lyricsDmRadius                   = SPEC_lyricsDmRadius.get();
        c.lyricsCommand                    = SPEC_lyricsCommand.get();
        c.lyricsUseSelector                = SPEC_lyricsUseSelector.get();
        c.repeatMode                       = SPEC_repeatMode.get();
        c.modrinthProjectId                = SPEC_modrinthProjectId.get();
        c.validatePostLoad();
    }

    /** 把实例字段写回 SPEC，随后落盘。 */
    public static void save() {
        Config c = INSTANCE;
        try { SPEC_configVersion.set(c.configVersion); } catch (Throwable ignored) {}
        try { SPEC_disableAsyncPlayback.set(c.disableAsyncPlayback); } catch (Throwable ignored) {}
        try { SPEC_omnidirectionalNoteBlockSounds.set(c.omnidirectionalNoteBlockSounds); } catch (Throwable ignored) {}
        try { SPEC_spectrumAlwaysVisible.set(c.spectrumAlwaysVisible); } catch (Throwable ignored) {}
        try { SPEC_expectedServerVersion.set(c.expectedServerVersion); } catch (Throwable ignored) {}
        try { SPEC_tuningSpeed.set(c.tuningSpeed); } catch (Throwable ignored) {}
        try { SPEC_playbackPacketRatelimit.set(c.playbackPacketRatelimit); } catch (Throwable ignored) {}
        try { SPEC_delayPlaybackStartBySecs.set((double) c.delayPlaybackStartBySecs); } catch (Throwable ignored) {}
        try { SPEC_instrumentDetectionWorkaround.set(c.instrumentDetectionWorkaround); } catch (Throwable ignored) {}
        try { SPEC_favorites.set(new ArrayList<>(c.favorites)); } catch (Throwable ignored) {}
        try { SPEC_lyricsChatOutput.set(c.lyricsChatOutput); } catch (Throwable ignored) {}
        try { SPEC_lyricsOutputToPublic.set(c.lyricsOutputToPublic); } catch (Throwable ignored) {}
        try { SPEC_lyricsMinIntervalMs.set(c.lyricsMinIntervalMs); } catch (Throwable ignored) {}
        try { SPEC_lyricsDmBurst.set(c.lyricsDmBurst); } catch (Throwable ignored) {}
        try { SPEC_lyricsDmMaxTargets.set(c.lyricsDmMaxTargets); } catch (Throwable ignored) {}
        try { SPEC_lyricsDmRadius.set(c.lyricsDmRadius); } catch (Throwable ignored) {}
        try { SPEC_lyricsCommand.set(c.lyricsCommand); } catch (Throwable ignored) {}
        try { SPEC_lyricsUseSelector.set(c.lyricsUseSelector); } catch (Throwable ignored) {}
        try { SPEC_repeatMode.set(c.repeatMode); } catch (Throwable ignored) {}
        try { SPEC_modrinthProjectId.set(c.modrinthProjectId); } catch (Throwable ignored) {}
        SPEC.save();
    }

    public void validatePostLoad() {
        if (repeatMode == null) repeatMode = RepeatMode.SEQUENTIAL;
        if (playbackPacketRatelimit == null) playbackPacketRatelimit = PlaybackPacketRatelimit.Limit500;
        if (expectedServerVersion == null) expectedServerVersion = ExpectedServerVersion.All;
        if (tuningSpeed == null) tuningSpeed = TuningSpeed.Spigot;
        if (favorites == null) favorites = new ArrayList<>();
    }

    // ==================== 枚举（原样保留） ====================

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

    public enum RepeatMode {
        SEQUENTIAL,
        SINGLE
    }
}
