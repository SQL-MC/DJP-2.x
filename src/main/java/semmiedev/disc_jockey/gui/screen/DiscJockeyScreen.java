package semmiedev.disc_jockey.gui.screen;

import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager;
import me.shedaniel.autoconfig.AutoConfigClient;
import java.nio.file.StandardCopyOption;   
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;            
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import java.lang.reflect.Method;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import semmiedev.disc_jockey.util.BlitCompat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.BlockPos;
import semmiedev.disc_jockey.*;
import semmiedev.disc_jockey.gui.SongListWidget;
import semmiedev.disc_jockey.gui.SongTimeSliderWidget;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class DiscJockeyScreen extends Screen {
    
    public SongListWidget getSongListWidget() {
            return songListWidget;
        }
    private static final Path NBS_SOURCE = Paths.get("D:/songs/songs");

    
    private static String encodePath(String path) {
        if (path == null || path.isEmpty()) return path;
        StringBuilder sb = new StringBuilder(path.length() + 16);
        String[] segs = path.split("/", -1);
        try {
            for (int i = 0; i < segs.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(java.net.URLEncoder.encode(segs[i], "UTF-8").replace("+", "%20"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            return path;
        }
        return sb.toString();
    }

    private static final String GITHUB_API = "https://api.github.com/repos/SQL-MC/Nbs/git/trees/main?recursive=1";
    private static final String RAW_BASE  = "https://raw.githubusercontent.com/SQL-MC/Nbs/main/";

    private void downloadNbsFromGithub() {
        File dest = Main.songsFolder;
        if (dest == null) { Main.LOGGER.warn("[DJ] Main.songsFolder 未初始化"); return; }
        Path destDir = dest.toPath();
        try { Files.createDirectories(destDir); } catch (IOException e) {
            Main.LOGGER.warn("[DJ] 创建目标目录失败: {}", e.getMessage()); return;
        }

        new Thread(() -> {
            java.util.List<String> nbsFiles = new java.util.ArrayList<>();
            try {
                
                String treeJson = httpGet(GITHUB_API);
                nbsFiles = parseTree(treeJson);
            } catch (IOException e) {
                Main.LOGGER.warn("[DJ] 获取 GitHub 文件列表失败: {}", e.getMessage());
                minecraft.execute(() -> minecraft.gui.chatListener().handleSystemMessage(
                        Component.literal("§c[DiscJockey] 连接 GitHub 失败: " + e.getMessage()), false));
                return;
            }

            if (nbsFiles.isEmpty()) {
                minecraft.execute(() -> minecraft.gui.chatListener().handleSystemMessage(
                        Component.literal("§c[DiscJockey] GitHub 仓库未找到 .nbs 文件"), false));
                return;
            }

            
            int ok = 0, fail = 0;
            for (String path : nbsFiles) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                try {
                    String url = RAW_BASE + encodePath(path);   
                    java.net.URL u = new java.net.URL(url);
                    Path out = destDir.resolve(name);
                    try (java.io.InputStream in = u.openStream();
                         java.io.OutputStream os = Files.newOutputStream(out,
                                 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    ok++;
                } catch (IOException e) {
                    fail++;
                    Main.LOGGER.warn("[DJ] 下载失败 {}: {} for URL: {}", name, e.getMessage(), RAW_BASE + encodePath(path));
                }
            }
            int fOk = ok, fFail = fail;
            Main.LOGGER.info("[DJ] GitHub 下载完成：成功 {} 个，失败 {} 个", fOk, fFail);
            minecraft.execute(() -> {
                SongLoader.loadSongs();
                awaitSongReload();
                minecraft.gui.chatListener().handleSystemMessage(
                        Component.literal("§a[DiscJockey] 下载完成：成功 " + fOk + "，失败 " + fFail), false);
            });
        }, "DJ-GithubNbs").start();
    }

    
    private java.util.List<String> parseTree(String json) {
        java.util.List<String> list = new java.util.ArrayList<>();
        // 找所有 "path":"..." 字段
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"path\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        while (m.find()) {
            String p = m.group(1);
            if (p.startsWith("songs/") && p.toLowerCase().endsWith(".nbs")) {
                list.add(p);
            }
        }
        return list;
    }

    
    private String httpGet(String urlStr) throws IOException {
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "DiscJockey/2.6.3");   
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " for " + urlStr);
        try (java.io.InputStream in = conn.getInputStream();
             java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    
    private void awaitSongReload() {
        if (SongLoader.loadingSongs) {
            minecraft.execute(() -> awaitSongReload());
            return;
        }
        markSongsDirty();   // 走 tick() 的重建分支：带搜索过滤 + 收藏置顶 + 绑定 songListWidget
    }

    
    private void rebuildSongList() {
        if (this.songListWidget == null) return;
        java.util.List<SongListWidget.SongEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < SongLoader.SONGS.size(); i++) {
            entries.add(new SongListWidget.SongEntry(SongLoader.SONGS.get(i), i));
        }
        songListWidget.safeReplaceEntries(entries);
    }
    
    private Screen parent = null;

    public void setParent(Screen parent) { this.parent = parent; }
    
    private static final MutableComponent
            SELECT_SONG = Component.translatable(Main.MOD_ID + ".screen.select_song"),
            PLAY = Component.translatable(Main.MOD_ID + ".screen.play"),
            PLAY_STOP = Component.translatable(Main.MOD_ID + ".screen.play.stop"),
            PREVIEW = Component.translatable(Main.MOD_ID + ".screen.preview"),
            PREVIEW_STOP = Component.translatable(Main.MOD_ID + ".screen.preview.stop"),
            DROP_HINT = Component.translatable(Main.MOD_ID + ".screen.drop_hint").withStyle(ChatFormatting.GRAY),
            SONGSTATE_PLAYING = Component.translatable(Main.MOD_ID + ".screen.songstate.playing")
                    .withStyle(s -> s.withItalic(true).withColor(0xDDDDDD)),
            SONGSTATE_PAUSED = Component.translatable(Main.MOD_ID + ".screen.songstate.paused")
                    .withStyle(s -> s.withItalic(true).withColor(0xDDDDDD)),
            SONGSTATE_FINISHED = Component.translatable(Main.MOD_ID + ".screen.songstate.finished")
                    .withStyle(s -> s.withItalic(true).withColor(0xDDDDDD)),
            SONGSTATE_STOPPED = Component.translatable(Main.MOD_ID + ".screen.songstate.stopped")
                    .withStyle(s -> s.withItalic(true).withColor(0xDDDDDD)),
            SONGSTATE_TUNING = Component.translatable(Main.MOD_ID + ".screen.songstate.tuning")
                    .withStyle(s -> s.withItalic(true).withColor(0xDDDDDD)),
            PLEASE_SELECT_SONG = Component.translatable(Main.MOD_ID + ".screen.please_select_song")
                    .withStyle(s -> s.withItalic(true)),
            CONFIG = Component.translatable(Main.MOD_ID + ".screen.config");

    private StringWidget songTitle;
    private StringWidget songState;
    private CycleButton<Boolean> playPauseButton;
    private Button stopButton;
    private SongTimeSliderWidget timeBar;
    private Button configButton;

    
    private CycleButton<SpectrumRendererManager.Style> spectrumStyleButton;

    private CycleButton<Boolean> lyricsToggleButton;      
    private CycleButton<Boolean> lyricsOutputButton;      // 公屏/私聊
    private Button lyricsOffsetMinusButton;                
    private net.minecraft.client.gui.components.EditBox lyricsOffsetEditBox; 
    private Button lyricsOffsetPlusButton;                 
    
    
    private static final Float[] SPEED_VALUES = { 0.5F, 0.75F, 1.0F, 1.25F, 1.5F };
    
    private static final Integer[] TRANSPOSE_VALUES = { -12, -6, -3, 0, 3, 6, 12 };

    // ---- 全局播放（playsound 广播）----
    /** 每 tick 最多发送的音效命令数，避免服务器命令洪水 */
    private static final int MAX_GLOBAL_SOUNDS_PER_TICK = 6;
    /** 广播音量（>1 放大，保证全服听清） */
    private static final float GLOBAL_VOLUME = 2.0F;
    /** 全局广播时临时关闭服务器命令反馈（聊天栏不再刷 playsound 回显） */
    private static final boolean GLOBAL_MUTE_FEEDBACK = true;
    /** 广播结束后把 send_command_feedback 恢复成什么（服务器默认 true） */
    private static final boolean GLOBAL_FEEDBACK_RESTORE = true;
    /** 聊天提示等级：0=完全不提示 1=仅错误 2=错误+开始/停止 3=全部 */
    private static final int GLOBAL_CHAT_LEVEL = 0;
    /** NBS 乐器 id → 音符盒音效，顺序与 Note.NBS_V4 一致 */
    private static final String[] NOTE_SOUND = {
            "minecraft:block.note_block.harp",
            "minecraft:block.note_block.bass",
            "minecraft:block.note_block.basedrum",
            "minecraft:block.note_block.snare",
            "minecraft:block.note_block.hat",
            "minecraft:block.note_block.guitar",
            "minecraft:block.note_block.flute",
            "minecraft:block.note_block.bell",
            "minecraft:block.note_block.chime",
            "minecraft:block.note_block.xylophone",
            "minecraft:block.note_block.iron_xylophone",
            "minecraft:block.note_block.cow_bell",
            "minecraft:block.note_block.didgeridoo",
            "minecraft:block.note_block.bit",
            "minecraft:block.note_block.banjo",
            "minecraft:block.note_block.pling"
    };
    /** 频谱柱数（与 Main.SMOOTHER 输出一致） */
    private static final int SPECTRUM_BINS = 16;

    private CycleButton<Float> speedButton;
    private Button refreshSongsButton;
    private CycleButton<Integer> transposeButton;

    // ---- 全局播放（static：退出菜单后广播继续，重新打开界面仍可停止）----
    private Button globalPlayButton;
    private static volatile boolean globalFeedbackMuted = false;
    private static volatile boolean globalPlaying = false;
    private static Thread globalPlayThread = null;
    /** 正在广播的歌曲名（跨界面实例保留，重新打开界面时用于提示） */
    private static String globalPlaySongName = null;

    // ==================== 全局播放频谱（自算 + 推送官方双路） ====================
    //
    // 为什么改成自算：官方链路依赖 onNotePlayed(...) 的真实签名与内部过滤，
    // 反射一旦没命中就全盘静默失败（表现为 GUI 内外都不显示）。自算不依赖
    // 任何官方方法签名，一定能显示；算完再把结果推给官方实例，驱动 HUD。
    //
    // GUI 内（DJ 界面）：直接读 globalLevels
    // HUD（始终显示）：把 globalLevels 写进官方实例的 float[16] 字段

    /**
     * 单音符峰值能量，直接写进官方 previewPeaks（绕过官方常量 PEAK_ENERGY=0.5）。
     * 官方预览是固定 0.5（经 OUTPUT_BOOST 1.14514 后最高 0.573，且每根一样高）。
     * 这里默认 0.85 → 柱高约 0.97，比预览更饱满；嫌高改 0.6，嫌矮改 1.0。
     */
    private static final float  GLOBAL_PEAK_ENERGY   = 0.85F;
    /** 力度带来的高度起伏（0=每根一样高，0.35=有明显层次）。治「生硬/太平」 */
    private static final float  GLOBAL_VELOCITY_VAR  = 0.35F;
    /** 每秒残余比例：官方每 tick×0.6、每秒 20 tick → 0.6^20。帧率无关 */
    private static final double GLOBAL_DECAY_PER_SEC = Math.pow(0.6D, 20.0D);
    /** 回落速度：1.0=与官方预览一致，0.6=更慢更拖尾，1.5=更快 */
    private static final float  GLOBAL_FALL_SPEED    = 1.0F;
    /** 上升柔和度（0..1）。越小柱子升起越软，越大越硬 */
    private static final float  GLOBAL_ATTACK        = 0.45F;
    /** 邻带泄漏：让柱子有宽度，不再是一根孤立的针 */
    private static final float  GLOBAL_SPILL_1       = 0.28F;
    private static final float  GLOBAL_SPILL_2       = 0.10F;
    /** band 映射：0=官方取模（与预览一致） 1=对数频率（低音左、高音右） */
    private static final int    GLOBAL_BAND_MODE     = 0;
    /** 诊断：打印频谱链路是否激活（排查用，正常关掉） */
    private static final boolean GLOBAL_SPECTRUM_DEBUG = false;
    /** 是否把自算结果推给官方实例（驱动 HUD 的「始终显示」） */
    private static final boolean GLOBAL_FEED_HUD     = true;
    /** 官方实例查找失败后的重试间隔（毫秒）。不再一次性判死 */
    private static final long   SPECTRUM_RETRY_MS    = 2000L;

    private static volatile boolean globalSpectrumActive = false;
    /** 显示值（平滑后） */
    private static final float[] globalLevels = new float[SPECTRUM_BINS];
    /** 目标值（音符注入，取 max，每帧随衰减回落） */
    private static final float[] globalTarget = new float[SPECTRUM_BINS];
    private static volatile long globalLastTickNanos = 0L;
    /** 官方 SpectrumVisualizer 实例（来自 Main.SPECTRUM） */
    private static volatile Object cachedSpectrum = null;
    private static volatile long lastSpectrumLookupMs = 0L;
    /** 驱动频谱推进的守护线程：界面关掉后 HUD 仍要动 */
    private static Thread globalSpectrumThread = null;
    private static final Object LEVEL_LOCK = new Object();

    private SongListWidget songListWidget;
    private Button playButton, previewButton;
    private boolean shouldFilter;
    private String query = "";
    private String lastShownKey = null;
    private void saveConfig() {
        try {
            Main.configHolder.save();
        } catch (Throwable t) {
            Main.LOGGER.warn("[DJ] 配置保存失败：{}", t.getMessage());
        }
    }

    

    private void adjustLyricsOffset(long delta) {
        Song playing = Main.SONG_PLAYER.song;
        if (playing == null || playing.lyrics == null) {
            this.minecraft.gui.chatListener().handleSystemMessage(
                    Component.literal("§c[DiscJockey] 当前歌曲无歌词"), false);
            return;
        }
        long newOffset = playing.lyrics.offsetMillis() + delta;
        try {
            playing.lyrics.setOffsetMillis(newOffset);   
        } catch (IOException e) {
            Main.LOGGER.warn("[DJ] 歌词偏移写入失败：{}", e.getMessage());
            this.minecraft.gui.chatListener().handleSystemMessage(
                    Component.literal("§c[DiscJockey] 歌词偏移保存失败"), false);
            return;
        }
        saveConfig();
        refreshOffsetDisplay();
        this.minecraft.gui.chatListener().handleSystemMessage(
                Component.literal("§a[DiscJockey] 歌词偏移: §f" + (newOffset / 1000.0) + "s §7(已保存)"),
                false);
    }


    private void refreshOffsetDisplay() {
        if (lyricsOffsetEditBox == null) return;
        if (lyricsOffsetEditBox.isFocused()) return;   
        Song playing = Main.SONG_PLAYER.song;
        long offset = (playing != null && playing.lyrics != null) ? playing.lyrics.offsetMillis() : 0L;
        lyricsOffsetEditBox.setValue(String.format("%+d", offset));
    }
    
    private float[] smoothedLevels = new float[16];

    
    
    public DiscJockeyScreen() {
        super(Main.NAME);
        this.parent = null;
    }

    
    public DiscJockeyScreen(Screen parent) {
        super(Main.NAME);
        this.parent = parent;
    }

    @Override
    protected void init() {
        shouldFilter = true;

        songListWidget = new SongListWidget(minecraft, width / 2 - 10, height - 64 - 32, 32, 20);
        songListWidget.setX(width / 2);
        addRenderableWidget(songListWidget);

        for (Song song : SongLoader.SONGS) {
            if (song.entry != null) {
                song.entry.songListWidget = songListWidget;
                if (song.entry.selected) {
                    songListWidget.setSelected(song.entry);
                }
            }
        }

        playButton = Button.builder(PLAY, b -> {
            if (Main.SONG_PLAYER.running) { Main.SONG_PLAYER.stop(); return; }
            SongListWidget.SongEntry e = songListWidget.getSelected();
            if (e == null || e.song == null) {
                minecraft.gui.chatListener().handleSystemMessage(
                        Component.translatable(Main.MOD_ID + ".screen.please_select_song"), false);
                return;
            }
            // 创造模式无法放置/敲击音符盒，直接提示而不是静默失败
            if (minecraft.gameMode != null
                    && minecraft.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.CREATIVE) {
                minecraft.gui.chatListener().handleSystemMessage(
                        Component.literal("§c[DiscJockey] 创造模式无法播放，请切换到生存模式"), false);
                return;
            }
            Main.SONG_PLAYER.start(e.song);
        }).bounds((width / 4 * 3) - 160, height - 61, 100, 20).build();
        addRenderableWidget(playButton);

        previewButton = Button.builder(PREVIEW, b -> {
            if (Main.PREVIEWER.running) Main.PREVIEWER.stop();
            else {
                SongListWidget.SongEntry e = songListWidget.getSelected();
                if (e != null) Main.PREVIEWER.start(e.song);
            }
        }).bounds((width / 4 * 3) - 50, height - 61, 100, 20).build();
        addRenderableWidget(previewButton);

        addRenderableWidget(Button.builder(
                Component.translatable(Main.MOD_ID + ".screen.blocks"),
                b -> minecraft.execute(() -> {
                    if (minecraft.player == null || minecraft.level == null) {
                        minecraft.gui.chatListener().handleSystemMessage(
                                Component.literal("§c[DiscJockey] player/level 尚未就绪"),
                                false
                        );
                        return;
                    }

                    BlockPos center = minecraft.player.blockPosition();
                    final int radius = 8;
                    int count = 0;

                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dy = -radius; dy <= radius; dy++) {
                            for (int dz = -radius; dz <= radius; dz++) {
                                BlockPos pos = center.offset(dx, dy, dz);
                                if (minecraft.level.getBlockState(pos).getBlock()
                                        instanceof net.minecraft.world.level.block.NoteBlock) {
                                    count++;
                                }
                            }
                        }
                    }

                    minecraft.gui.chatListener().handleSystemMessage(
                            Component.literal("§a[DiscJockey] 检测到音符盒: §f" + count + " §a（半径 " + radius + " 格）"),
                            false
                    );

                    SongListWidget.SongEntry entry = songListWidget.getSelected();
                    if (entry != null && entry.song != null) {
                        int needed = entry.song.uniqueNotes.size();
                        if (count >= needed) {
                            minecraft.gui.chatListener().handleSystemMessage(
                                    Component.literal("§a✅ 数量充足（需要 " + needed + "）"),
                                    false
                            );
                        } else {
                            minecraft.gui.chatListener().handleSystemMessage(
                                    Component.literal("§e⚠ 不足（需要 " + needed + "，当前 " + count + "）"),
                                    false
                            );
                        }
                    }
                })
        ).bounds((width / 4 * 3) + 60, height - 61, 100, 20).build());

        EditBox searchBar = new EditBox(
                font,
                (width / 4 * 3) - 75,
                height - 31,
                150,
                20,
                Component.empty()
        );
        searchBar.setHint(Component.translatable(Main.MOD_ID + ".screen.search")
                .withStyle(s -> s.withItalic(true).withColor(0xDDDDDD)));
        searchBar.setResponder(q -> {
            q = q.toLowerCase().replaceAll("\\s", "");
            if (!this.query.equals(q)) {
                this.query = q;
                shouldFilter = true;
            }
        });
        addRenderableWidget(searchBar);

        songState = new StringWidget(10, 32, width / 2 - 20, 20, Component.empty(), this.font);
        addRenderableWidget(songState);

        songTitle = new StringWidget(10, 32 + 20, width / 2 - 20, 20, Component.empty(), this.font);
        addRenderableWidget(songTitle);

        timeBar = new SongTimeSliderWidget(10, 32 + 20 + 20, width / 2 - 20, 30);
        addRenderableWidget(timeBar);

        playPauseButton = CycleButton.<Boolean>builder(
                v -> Component.literal(v ? "⏸" : "▶"),
                Main.SONG_PLAYER.running
        )
                .displayOnlyValue()
                .withValues(true, false)
                .create(
                        (width / 4) - 25,
                        32 + 20 + 20 + 30 + 5,
                        20,
                        20,
                        Component.empty(),
                        (b, v) -> {
                            if (v && Main.SONG_PLAYER.song != null && Main.SONG_PLAYER.didSongReachEnd)
                                Main.SONG_PLAYER.start(Main.SONG_PLAYER.song);
                            else Main.SONG_PLAYER.running = v;
                        }
                );
        addRenderableWidget(playPauseButton);

        stopButton = Button.builder(Component.literal("⏹"), b -> Main.SONG_PLAYER.stop())
                .pos((width / 4) + 5, 32 + 20 + 20 + 30 + 5)
                .size(20, 20)
                .build();
        addRenderableWidget(stopButton);

        int lx = 115;
        int ly = height - 79;

        
        lyricsToggleButton = CycleButton.onOffBuilder(Main.config.lyricsChatOutput)
                .withValues(true, false)
                .create(lx, ly, 72, 18,
                        Component.translatable(Main.MOD_ID + ".screen.lyrics"),
                        (btn, value) -> {
                            Main.config.lyricsChatOutput = value;
                            saveConfig();
                        });
        lyricsToggleButton.setTooltip(Tooltip.create(
                Component.translatable(Main.MOD_ID + ".screen.lyrics.tooltip")));
        addRenderableWidget(lyricsToggleButton);

        // 公屏 / 私聊（通用 builder 绕过 booleanBuilder 重载歧义）
        lyricsOutputButton = CycleButton.<Boolean>builder(
                        value -> value
                                ? Component.translatable(Main.MOD_ID + ".screen.lyrics.public")
                                : Component.translatable(Main.MOD_ID + ".screen.lyrics.private"),
                        Main.config.lyricsOutputToPublic)
                .withValues(true, false)
                .create(lx + 76, ly, 60, 18,
                        Component.translatable(Main.MOD_ID + ".screen.lyrics.output"),
                        (btn, value) -> {
                            Main.config.lyricsOutputToPublic = value;
                            saveConfig();
                        });
        addRenderableWidget(lyricsOutputButton);

        
        lyricsOffsetMinusButton = Button.builder(
                        Component.literal("-"),
                        b -> adjustLyricsOffset(-500))
                .bounds(lx + 140, ly, 18, 18).build();
        addRenderableWidget(lyricsOffsetMinusButton);

        
        lyricsOffsetEditBox = new net.minecraft.client.gui.components.EditBox(
                this.font, lx + 161, ly + 1, 56, 16,
                Component.translatable(Main.MOD_ID + ".screen.lyrics.offset"));
        lyricsOffsetEditBox.setMaxLength(8);
        lyricsOffsetEditBox.setResponder(text -> {
            try {
                long ms = Long.parseLong(text.replaceAll("[^+-?\\d]", ""));
                Song playing = Main.SONG_PLAYER.song;
                if (playing != null && playing.lyrics != null) {
                    playing.lyrics.setOffsetMillis(ms);
                    saveConfig();
                }
            } catch (NumberFormatException ignored) {  }
              catch (IOException e) {
                Main.LOGGER.warn("[DJ] 歌词偏移写入失败：{}", e.getMessage());
            }
        });
        refreshOffsetDisplay();
        addRenderableWidget(lyricsOffsetEditBox);

        
        lyricsOffsetPlusButton = Button.builder(
                        Component.literal("+"),
                        b -> adjustLyricsOffset(+500))
                .bounds(lx + 220, ly, 18, 18).build();
        addRenderableWidget(lyricsOffsetPlusButton);
        
        configButton = Button.builder(CONFIG, b ->
                openConfigScreenCompat()
        ).pos(10, height - 30).size(100, 20).build();
        addRenderableWidget(configButton);
        refreshSongsButton = Button.builder(
                Component.translatable(Main.MOD_ID + ".screen.refresh_songs"),
                b -> {
                    if (SongLoader.loadingSongs) {
                        minecraft.gui.chatListener().handleSystemMessage(
                                Component.translatable(Main.MOD_ID + ".screen.refresh_songs.already"), false);
                        return;
                    }
                    SongLoader.loadSongs();
                    markSongsDirty();   // 触发 tick() 重建列表，否则界面仍显示旧列表
                    minecraft.gui.chatListener().handleSystemMessage(
                            Component.translatable(Main.MOD_ID + ".screen.refresh_songs.done"), false);
                })
                .bounds(lx + 138, ly + 22, 90, 18)
                .tooltip(Tooltip.create(
                        Component.translatable(Main.MOD_ID + ".screen.refresh_songs.tooltip")))
                .build();
        addRenderableWidget(refreshSongsButton);
        
        Button exportMidiButton = Button.builder(
                Component.translatable("disc_jockey.screen.export_midi"),
                btn -> {
                    SongListWidget.SongEntry entry = songListWidget.getSelected();
                    if (entry == null || entry.song == null) {
                        minecraft.gui.chatListener().handleSystemMessage(
                                Component.translatable("disc_jockey.export.select_song_first"),
                                false
                        );
                        return;
                    }
                    try {
                        NbsToMidiExporter.exportSong(entry.song);

                        String safeName = ((entry.song.displayName != null) ? entry.song.displayName : entry.song.fileName)
                                .replaceAll("[^a-zA-Z0-9_\\-\\s]", "_");
                        File midiDir = new File(Main.songsFolder.getParentFile(), "midi");
                        File midiFile = new File(midiDir, safeName + ".mid");

                        minecraft.gui.chatListener().handleSystemMessage(
                                Component.translatable(
                                        "disc_jockey.export.success",
                                        midiFile.getAbsolutePath()
                                ),
                                false
                        );

                        if (Desktop.isDesktopSupported() && midiDir.exists()) {
                            Desktop.getDesktop().open(midiDir);
                        }

                    } catch (Exception e) {
                        Main.LOGGER.error("MIDI export failed", e);
                        minecraft.gui.chatListener().handleSystemMessage(
                                Component.translatable("disc_jockey.export.failed"),
                                false
                        );
                    }
                }
        ).pos(115, height - 30).size(100, 20).build();
        addRenderableWidget(exportMidiButton);


        Button downloadNbsButton = Button.builder(
                Component.translatable(Main.MOD_ID + ".button.download_nbs"),
                btn -> downloadNbsFromGithub()
        ).pos(10, height - 55)
         .size(100, 20)
         .tooltip(Tooltip.create(Component.translatable(Main.MOD_ID + ".button.download_nbs.tooltip")))
         .build();
        addRenderableWidget(downloadNbsButton);
        
        Button exportWavButton = Button.builder(
                Component.translatable(Main.MOD_ID + ".button.wav_out"),
                btn -> {
                    SongListWidget.SongEntry entry = songListWidget.getSelected();
                    if (entry == null || entry.song == null) {
                        minecraft.gui.chatListener().handleSystemMessage(
                                Component.literal("·"),
                                false
                        );
                        return;
                    }
                    final Song song = entry.song;
                    btn.active = false;   
                    new Thread(() -> {
                        try {
                            NbsToWavExporter.exportSong(song);   // → config/disc_jockey/wav/<名>.wav
                            minecraft.execute(() ->
                                    minecraft.gui.chatListener().handleSystemMessage(
                                            Component.literal("O.K."),
                                            false
                                    )
                            );
                        } catch (Exception ex) {
                            Main.LOGGER.error("WAV export failed", ex);
                            minecraft.execute(() ->
                                    minecraft.gui.chatListener().handleSystemMessage(
                                            Component.literal("Try again"),
                                            false
                                    )
                            );
                        } finally {
                            minecraft.execute(() -> btn.active = true);  
                        }
                    }, "DJ-WAV-Export").start();
                }
        ).pos(115, height - 55).size(100, 20).build();
        addRenderableWidget(exportWavButton);
        Button editButton = Button.builder(
                Component.translatable("disc_jockey.screen.edit"),
                btn -> {
                    SongListWidget.SongEntry e = songListWidget.getSelected();
                    if (e == null || e.song == null) {
                        showMsg("§c" + Component.translatable("disc_jockey.editor.select_first").getString());
                        return;
                    }
                    Main.setScreenCompatStatic(minecraft,
                            new NoteBlockEditScreen(DiscJockeyScreen.this, e.song));
                }
        ).pos(10, 5).size(60, 20).build();   // ← 左上角，位置不变
        addRenderableWidget(editButton);
        
        CycleButton.Builder<SpectrumRendererManager.Style> styleBuilder =
                CycleButton.<SpectrumRendererManager.Style>builder(
                        s -> Component.literal(s.displayName),
                        SpectrumRendererManager.getCurrentStyle()
                );
        this.spectrumStyleButton = styleBuilder
                .withValues(SpectrumRendererManager.Style.values())
                .create(
                        220, height - 30, 100, 20,
                        Component.translatable(Main.MOD_ID + ".screen.spectrum_style"),
                        (btn, st) -> SpectrumRendererManager.setStyle(st)
                );
        addRenderableWidget(this.spectrumStyleButton);

        // ★ 全局播放：管理员权限 playsound 广播给全服（位于变调按钮左侧）
        globalPlayButton = Button.builder(
                Component.translatable(Main.MOD_ID + ".screen.global_play"),
                b -> toggleGlobalPlay()
        ).pos(width - 440, 5).size(100, 20)
         .tooltip(Tooltip.create(
                 Component.translatable(Main.MOD_ID + ".screen.global_play.tooltip")))
         .build();
        addRenderableWidget(globalPlayButton);

        
        this.transposeButton = CycleButton.<Integer>builder(
                v -> Component.literal("🎵 " + (v == 0 ? "0" : String.format("%+d", v))),
                Main.SONG_PLAYER.transpose
        )
                .displayOnlyValue()
                .withValues(TRANSPOSE_VALUES)
                .create(
                        width - 330, 5, 100, 20,
                        Component.translatable(Main.MOD_ID + ".screen.transpose"),
                        (btn, val) -> {
                            try {
                                // ✅ 与命令 /discjockey transpose 完全对齐（L384-392）
                                Main.SONG_PLAYER.transpose = val;
                                if (Main.SONG_PLAYER.song != null) {
                                    NoteClamper.buildFoldedNotes(Main.SONG_PLAYER.song, val);
                                }
                                if (Main.SONG_PLAYER.song != null && Main.SONG_PLAYER.running) {
                                    Main.SONG_PLAYER.tuner.reset();
                                }
                            } catch (Throwable t) {
                                Main.LOGGER.error("Failed to set transpose", t);
                            }
                        }
                );
        addRenderableWidget(this.transposeButton);

        
        this.speedButton = CycleButton.<Float>builder(
                v -> Component.literal("⚡ " + (v == 1.0F ? "1.0x" : String.format("%sx", v))),
                getCurrentSpeed()
        )
                .displayOnlyValue()
                .withValues(SPEED_VALUES)
                .create(
                        width - 220, 5, 100, 20,
                        Component.translatable(Main.MOD_ID + ".screen.speed"),
                        (btn, v) -> {
                            try {
                                Main.PREVIEW_SPEED = v;
                                Main.SONG_PLAYER.speed = v;
                            } catch (Throwable t) {
                                Main.LOGGER.error("Failed to set playback speed", t);
                            }
                        }
                );
        addRenderableWidget(this.speedButton);

        
        Button pianoButton = Button.builder(
                Component.literal("🎹 🎶→"),
                btn -> {
                    try {
                        PianoKeyboardScreen piano = new PianoKeyboardScreen(this);
                        Main.setScreenCompatStatic(minecraft, piano);
                    } catch (Throwable t) {
                        Main.LOGGER.error("Failed to open Piano Keyboard screen", t);
                    }
                }
        ).pos(325, height - 30).size(100, 20).build();
        addRenderableWidget(pianoButton);

        
        Button importMidiButton = Button.builder(
                Component.translatable("disc_jockey.screen.import_midi"),
                btn -> {
                    try {
                        Screen midi = new MidiFileSelectScreen(this);
                        Main.setScreenCompatStatic(minecraft, midi);
                    } catch (Throwable t) {
                        Main.LOGGER.error("Failed to open MIDI selector screen", t);
                        minecraft.gui.chatListener().handleSystemMessage(
                                Component.literal("§c[DiscJockey] 无法打开 MIDI 选择界面：")
                                        .append(t.getMessage()),
                                false
                        );
                    }
                }
        ).pos(width - 110, 5).size(100, 20).build();
        addRenderableWidget(importMidiButton);

        
        File midiDir = new File(Main.songsFolder.getParentFile(), "midi");
        String fullPath = midiDir.getAbsolutePath();
        String displayPath = fullPath;
        if (displayPath.length() > 26) {
            displayPath = "..." + displayPath.substring(displayPath.length() - 23);
        }

        StringWidget midiPathHint = new StringWidget(
                width - 110,
                27,
                100,
                9,
                Component.literal(displayPath)
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
                this.font
        );
        midiPathHint.active = false;
        midiPathHint.visible = true;
        midiPathHint.setTooltip(Tooltip.create(Component.literal(fullPath)));
        addRenderableWidget(midiPathHint);
    }

    /**
     * Fabric 原版入口：me.shedaniel.autoconfig.AutoConfigClient
     * 原文件顶部 import 的就是它，重构时不能丢。
     * Cloth Config 各版本方法签名不统一，这里枚举静态方法做通用适配：
     *   参数：(Class) / (Screen) / (Class,Screen) / (Screen,Class) / ()
     *   返回：Screen / Optional<Screen> / Supplier<Screen>
     */
    private boolean tryAutoConfigClient(Class<?> screenCls) {
        try {
            Class<?> cls = Class.forName("me.shedaniel.autoconfig.AutoConfigClient");
            Class<?> cfgCls = (Main.configHolder != null)
                    ? Main.configHolder.getConfig().getClass() : null;
            if (cfgCls == null) {
                try { cfgCls = Class.forName("semmiedev.disc_jockey.Config"); }
                catch (Throwable t) { cfgCls = null; }
            }

            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                String nm = m.getName().toLowerCase();
                if (!nm.contains("configscreen") && !nm.contains("open")) continue;

                Class<?>[] ps = m.getParameterTypes();
                Object[] args;
                try {
                    if (ps.length == 0) {
                        args = new Object[0];
                    } else if (ps.length == 1) {
                        if (ps[0] == Class.class) {
                            if (cfgCls == null) continue;
                            args = new Object[]{cfgCls};
                        } else if (ps[0].isAssignableFrom(screenCls)) {
                            args = new Object[]{this};
                        } else continue;
                    } else if (ps.length == 2) {
                        Object a = null, b = null;
                        if (ps[0] == Class.class) a = cfgCls;
                        else if (ps[0].isAssignableFrom(screenCls)) a = this;
                        else continue;
                        if (ps[1] == Class.class) b = cfgCls;
                        else if (ps[1].isAssignableFrom(screenCls)) b = this;
                        else continue;
                        if (a == null || b == null) continue;
                        args = new Object[]{a, b};
                    } else continue;

                    Object r = m.invoke(null, args);
                    net.minecraft.client.gui.screens.Screen s = unwrapScreen(r);
                    if (s != null) { openScreenSafe(s); return true; }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 返回值适配：Screen / Optional<Screen> / Supplier / Provider */
    private static net.minecraft.client.gui.screens.Screen unwrapScreen(Object r) {
        if (r == null) return null;
        if (r instanceof net.minecraft.client.gui.screens.Screen s) return s;
        try {
            if (r instanceof java.util.Optional<?> o) {
                if (!o.isPresent()) return null;
                Object v = o.get();
                if (v instanceof net.minecraft.client.gui.screens.Screen s2) return s2;
                return null;
            }
        } catch (Throwable ignored) {}
        for (String mn : new String[]{"get", "getScreen", "provide", "create", "make"}) {
            try {
                java.lang.reflect.Method m = r.getClass().getMethod(mn);
                Object v = m.invoke(r);
                if (v instanceof net.minecraft.client.gui.screens.Screen s) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ==================== 配置界面（Fabric 版） ====================

    /**
     * 打开配置界面 —— Fabric 版。
     *
     * 为什么走反射：Fabric 侧 Main 没有 openConfigScreen(Screen)，
     * 直接调用会「找不到符号」编译失败；Fabric 的配置界面由
     * Cloth Config 的 AutoConfig 提供。
     *
     * 探测顺序（Cloth Config 优先）：
     *   1) AutoConfig.getConfigScreen(Config, 父界面)  ← Fabric 主用
     *   2) Main.openConfigScreen(Screen)               ← 兜底，Fabric 无此方法会自动跳过
     *   3) Main.openConfigScreen(Minecraft, Screen)
     *   4) Main.openConfigScreen()
     *   5) 自建 ConfigScreen 类
     *
     * 全部走反射：某个方法 / 类在不在都不影响编译。
     */
    private void openConfigScreenCompat() {
        Class<?> screenCls = net.minecraft.client.gui.screens.Screen.class;

        // 0) Fabric 原版入口：AutoConfigClient（原文件 import 的就是它）
        if (tryAutoConfigClient(screenCls)) return;

        // 4) Fabric：Cloth Config 的 AutoConfig.getConfigScreen(Class, Screen)
        try {
            Class<?> autoConfig = Class.forName("me.shedaniel.autoconfig.AutoConfig");
            Class<?> cfgCls = (Main.configHolder != null)
                    ? Main.configHolder.getConfig().getClass() : null;
            if (cfgCls == null) cfgCls = Class.forName("semmiedev.disc_jockey.Config");

            java.lang.reflect.Method gcs =
                    autoConfig.getMethod("getConfigScreen", Class.class, screenCls);
            Object r = gcs.invoke(null, cfgCls, this);
            if (r instanceof net.minecraft.client.gui.screens.Screen s) {
                openScreenSafe(s);
                return;
            }
        } catch (Throwable ignored) {}

        // 1) NeoForge：Main.openConfigScreen(Screen)
        try {
            java.lang.reflect.Method m = Main.class.getMethod("openConfigScreen", screenCls);
            Object r = m.invoke(null, this);
            if (r instanceof net.minecraft.client.gui.screens.Screen s) {
                openScreenSafe(s);
                return;
            }
        } catch (Throwable ignored) {}

        // 2) Main.openConfigScreen(Minecraft, Screen)
        try {
            java.lang.reflect.Method m = Main.class.getMethod(
                    "openConfigScreen", net.minecraft.client.Minecraft.class, screenCls);
            Object r = m.invoke(null, minecraft, this);
            if (r instanceof net.minecraft.client.gui.screens.Screen s) {
                openScreenSafe(s);
                return;
            }
        } catch (Throwable ignored) {}

        // 3) Main.openConfigScreen() 无参
        try {
            java.lang.reflect.Method m = Main.class.getMethod("openConfigScreen");
            Object r = m.invoke(null);
            if (r instanceof net.minecraft.client.gui.screens.Screen s) {
                openScreenSafe(s);
                return;
            }
        } catch (Throwable ignored) {}

        // 5) 兜底：自己找 ConfigScreen 类（构造器接收父界面）
        for (String cn : new String[]{
                "semmiedev.disc_jockey.gui.screen.ConfigScreen",
                "semmiedev.disc_jockey.gui.ConfigScreen",
                "semmiedev.disc_jockey.ConfigScreen"}) {
            try {
                Class<?> c = Class.forName(cn);
                java.lang.reflect.Constructor<?> ctor = c.getConstructor(screenCls);
                Object r = ctor.newInstance(this);
                if (r instanceof net.minecraft.client.gui.screens.Screen s) {
                    openScreenSafe(s);
                    return;
                }
            } catch (Throwable ignored) {}
        }

        showMsg("§c[DiscJockey] 当前平台未提供配置界面入口");
    }

    /**
     * 安全开界面：优先走 Main.openScreenOnNextTick（跨帧队列，根治偶发打不开），
     * 该方法不存在时回退 setScreenCompatStatic。
     */
    private void openScreenSafe(net.minecraft.client.gui.screens.Screen s) {
        if (s == null) return;
        try {
            java.lang.reflect.Method m =
                    Main.class.getMethod("openScreenOnNextTick",
                            net.minecraft.client.gui.screens.Screen.class);
            m.invoke(null, s);
            return;
        } catch (Throwable ignored) {}
        Main.setScreenCompatStatic(minecraft, s);
    }

    // ==================== 全局播放（playsound 广播） ====================

    /** 点击：正在播则停止，否则对选中歌曲开始广播 */
    private void toggleGlobalPlay() {
        if (globalPlaying) { stopGlobalPlay(); return; }

        SongListWidget.SongEntry e = songListWidget.getSelected();
        if (e == null || e.song == null) {
            globalChat(1, "§c" + Component.translatable("disc_jockey.editor.select_first").getString());
            return;
        }
        if (getConnection() == null) {
            globalChat(1, "§c[DiscJockey] 未连接到服务器，无法广播");
            return;
        }
        startGlobalPlay(e.song);
    }

    /** 按 tick 时间轴向全服发送 playsound（static：界面关闭后线程继续存活） */
    private static void startGlobalPlay(Song song) {
        stopGlobalPlay();

        // 拆包并按 tick 分组（与 NoteBlockEditScreen 一致的打包格式）
        java.util.Map<Integer, java.util.List<int[]>> byTick = new java.util.HashMap<>();
        long[] src = song.notes;
        int maxTick = 0;
        if (src != null) {
            for (long p : src) {
                int tick = (int) (p & 0xFFFFL);
                int inst = (int) ((p >>> 32) & 0xFFL);
                int noteId = Note.extractNoteId(p);
                byTick.computeIfAbsent(tick, k -> new java.util.ArrayList<>())
                      .add(new int[]{inst, noteId});
                if (tick > maxTick) maxTick = tick;
            }
        }
        if (byTick.isEmpty()) {
            globalChat(1, "§c[DiscJockey] 该曲没有音符");
            return;
        }

        // ★ 互斥：先停本地预览/播放，再激活官方频谱链路
        stopLocalPlaybackForGlobal();
        activateGlobalSpectrum();
        forceSpectrumGateOpen();       // ★ 无条件：即使空 Song 注入失败，也直接开闸门字段
        startGlobalSpectrumThread();   // ★ 无条件启动：官方链路没打通时 GUI 内也一定有频谱

        final int   fMax      = maxTick;
        final float speed     = Math.max(0.25F, getCurrentSpeed());
        final long  tickMs    = Math.max(20L,
                (long) (100000L / Math.max(1, song.tempo) / speed));

        globalPlaying = true;
        String name = (song.displayName != null) ? song.displayName : song.fileName;
        globalPlaySongName = name;
        muteCommandFeedback();   // ★ 先关服务器命令反馈，杜绝 playsound 回显刷屏
        globalChat(2, "§a[DiscJockey] 开始全局广播：" + name + "（需要管理员权限）");
        globalChat(3, "§7[DiscJockey] 关闭菜单不会停止广播；重新打开 DJ 界面点同一按钮即可停止");

        globalPlayThread = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                for (int t = 0; t <= fMax && globalPlaying; t++) {
                    java.util.List<int[]> at = byTick.get(t);
                    if (at != null) {
                        int sent = 0;
                        int transpose = Main.SONG_PLAYER.transpose;   // ★ 实时跟随界面变调
                        for (int[] n : at) {
                            if (sent++ >= MAX_GLOBAL_SOUNDS_PER_TICK) break;
                            int noteId = n[1] + transpose;
                            feedSpectrum(n[0], noteId);         // ★ 走官方频谱（与 Previewer 同款）
                            runGlobalCommand(buildPlaySound(n[0], noteId));
                        }
                    }
                    long want = t * tickMs;
                    long now  = System.currentTimeMillis() - t0;
                    if (want > now) Thread.sleep(Math.min(1000L, want - now));
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable t) {
                Main.LOGGER.error("[DJ] 全局播放异常", t);
            } finally {
                if (globalPlaying) {
                    Minecraft mc0 = Minecraft.getInstance();
                    if (mc0 != null) mc0.execute(DiscJockeyScreen::stopGlobalPlay);
                    else stopGlobalPlay();
                }
            }
        }, "DJ-GlobalPlay");
        globalPlayThread.start();
    }

    private static void stopGlobalPlay() {
        globalPlaying = false;
        deactivateGlobalSpectrum();     // ★ 关闭官方频谱链路并清零
        if (globalPlayThread != null) {
            globalPlayThread.interrupt();
            globalPlayThread = null;
        }
        globalPlaySongName = null;
        resetGlobalLevels();          // ★ 停止时清空频谱，避免残留柱子
        restoreCommandFeedback();     // ★ 恢复服务器命令反馈
        globalChat(2, "§7[DiscJockey] 已停止全局广播");
    }
    // ---------------- 频谱驱动（全局播放专用） ----------------

    /**
     * ★ 互斥第一步：启动全局广播前，先停掉本地预览与本地播放。
     *
     * 三套发声（Previewer 本地播音 / SongPlayer 敲音符盒 / 全局 playsound）
     * 原本互不相干、可以同时跑 —— 声音叠成一团，频谱也互相打架。
     * 官方 Previewer.start() 只停了 SongPlayer，SongPlayer.start() 只停了
     * Previewer，谁都不认识全局播放，所以必须在这里补上。
     */
    private static void stopLocalPlaybackForGlobal() {
        try { Main.PREVIEWER.stop(); } catch (Throwable ignored) {}
        try { Main.SONG_PLAYER.stop(); } catch (Throwable ignored) {}
    }

    /**
     * ★ 互斥第二步：本地播放/预览启动后，全局广播自动让位（tick() 里每帧检测）。
     *
     * 注意不能只看 Previewer.running —— 我们自己就把它置成了 true
     * （用于激活频谱闸门）。必须同时判断 song != null，才算"真的在预览"。
     */
    private static void checkGlobalYieldToLocal() {
        if (!globalPlaying) return;
        try {
            boolean localPreview = Previewer.running
                    && songNoteCount(Previewer.getInstance().getSong()) > 0
                    && !globalSpectrumActive;
            boolean localSong = Main.SONG_PLAYER != null && Main.SONG_PLAYER.running;
            if (localPreview || localSong) {
                globalChat(2, "\u00a77[DiscJockey] 检测到本地播放，已停止全局广播");
                stopGlobalPlay();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * ★ 激活官方频谱链路（这是「始终显示」与「柱高」两个问题的共同钥匙）。
     *
     * 官方两处闸门（源码已确认，判据完全相同）：
     *   SpectrumVisualizer.tick()  : if (!songRunning && !previewRunning) return;
     *   SpectrumDataSmoother.tick(): isPlaying = songRunning || previewRunning;
     *                                if (!isPlaying) { smoothed[i] -= 0.15; ... return; }
     * 其中
     *   previewRunning = Previewer.running
     *                 && Previewer.getInstance() != null
     *                 && Previewer.getInstance().getSong() != null;   ← 关键
     *
     * 全局播放既不是本地播放也不是预览 → isPlaying=false →
     * 平滑器每 tick 从 smoothed 里扣 0.15，三秒归零。而 HUD「始终显示」
     * 的唯一数据源就是 Main.SMOOTHER.getSmoothedLevels()，于是：
     *   不始终显示 = 被扣到 0；柱高太矮 = 被反复往下压。
     *
     * 破解：给 Previewer 塞一个【0 音符的空 Song】。
     *   - getSong() != null  → 闸门通过，走官方完整链路（peakHold/ATTACK/DECAY/OUTPUT_BOOST）
     *   - 0 音符             → 无论 Previewer 内部怎么检查，都不可能有任何声音
     * 所以既打开了频谱，又不会和全局广播重声。
     *
     * 注：只设 running=true 是不够的（stop() 已把 song 置 null），这正是上一版失败的原因。
     */
    private static void activateGlobalSpectrum() {
        globalSpectrumActive = false;
        try {
            Main.PREVIEWER.stop();                 // 清掉真实预览的歌
            Main.SONG_PLAYER.stop();
        } catch (Throwable ignored) {}

        try {
            Object previewer = Previewer.getInstance();
            if (previewer == null) return;

            Object emptySong = createEmptySong();
            if (emptySong == null) return;

            java.lang.reflect.Field sf = findSongField(previewer.getClass());
            if (sf == null) return;
            sf.setAccessible(true);
            sf.set(previewer, emptySong);           // getSong() != null → 闸门通过

            Previewer.running = true;
            globalSpectrumActive = true;
            if (GLOBAL_SPECTRUM_DEBUG) System.out.println("[DJ] 频谱链路激活成功（空 Song 已注入）");
            globalLastTickNanos = 0L;
            startGlobalSpectrumThread();
        } catch (Throwable ignored) {
            globalSpectrumActive = false;
            if (GLOBAL_SPECTRUM_DEBUG) System.out.println("[DJ] 频谱链路激活失败，退回自算（仅 GUI 内可见）");
        }
    }

    /** 关闭频谱链路：撤掉假预览状态并清零（不留残留柱子） */
    private static void deactivateGlobalSpectrum() {
        globalSpectrumActive = false;
        try {
            Previewer.running = false;
            Object previewer = Previewer.getInstance();
            if (previewer != null) {
                java.lang.reflect.Field sf = findSongField(previewer.getClass());
                if (sf != null) { sf.setAccessible(true); sf.set(previewer, null); }
            }
        } catch (Throwable ignored) {}
        try {
            if (globalSpectrumThread != null) { globalSpectrumThread.interrupt(); globalSpectrumThread = null; }
        } catch (Throwable ignored) {}
        resetGlobalLevels();
    }

    /** 在 Previewer 实例里找 Song 类型的字段（优先字段名 song） */
    private static java.lang.reflect.Field findSongField(Class<?> c0) {
        Class<?> c = c0;
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType().getName().endsWith(".Song")) return f;
            }
            c = c.getSuperclass();
        }
        c = c0;
        while (c != null && c != Object.class) {
            try { return c.getDeclaredField("song"); } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 造一个「0 音符」的空 Song：足以让 getSong() 返回非 null 打开频谱闸门，
     * 但没有任何音符可播，所以绝对不会发出声音。
     */
    private static Object createEmptySong() {
        try {
            Class<?> sc = Class.forName("semmiedev.disc_jockey.Song");
            Object s = sc.getDeclaredConstructor().newInstance();
            setIfPresent(sc, s, "notes", new long[0]);
            setIfPresent(sc, s, "foldedNotes", null);
            setIfPresent(sc, s, "length", (short) 0);
            setIfPresent(sc, s, "height", (short) 0);
            setIfPresent(sc, s, "tempo", (short) 1000);
            setIfPresent(sc, s, "loopStartTick", (short) 0);
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setIfPresent(Class<?> c, Object obj, String name, Object val) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, val);
                return;
            } catch (Throwable ignored) { k = k.getSuperclass(); }
        }
    }

    /** 歌曲实际音符数（用于区分「真的在预览」与「我们塞的空壳」） */
    private static int songNoteCount(Object song) {
        if (song == null) return 0;
        Class<?> c = song.getClass();
        while (c != null && c != Object.class) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("notes");
                f.setAccessible(true);
                Object v = f.get(song);
                if (v instanceof long[] la) return la.length;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return 0;
    }

    /** 取官方 SpectrumVisualizer 实例（Main.SPECTRUM），失败则节流重试 */
    private static Object getSpectrumInstance() {
        if (cachedSpectrum != null) return cachedSpectrum;
        long now = System.currentTimeMillis();
        if (now - lastSpectrumLookupMs < SPECTRUM_RETRY_MS) return null;
        lastSpectrumLookupMs = now;
        for (String fn : new String[]{"SPECTRUM", "SPECTRUM_VISUALIZER", "spectrum",
                                      "spectrumVisualizer", "SPECTRUM_INSTANCE"}) {
            try {
                java.lang.reflect.Field f = Main.class.getDeclaredField(fn);
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) { cachedSpectrum = v; return v; }
            } catch (Throwable ignored) {}
        }
        try {
            java.lang.reflect.Method m = Main.class.getMethod("getSpectrum");
            Object v = m.invoke(null);
            if (v != null) { cachedSpectrum = v; return v; }
        } catch (Throwable ignored) {}

        // ★ 兜底：遍历 Main 所有静态字段，找「含长度=柱数 float[]」的对象。
        //   频谱类的特征就是持有 float[16]，字段名完全猜错也能命中。
        for (java.lang.reflect.Field f : Main.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v == null) continue;
                if (hasSpectrumArray(v)) {
                    cachedSpectrum = v;
                    if (GLOBAL_SPECTRUM_DEBUG) System.out.println("[DJ] 频谱实例命中(特征匹配): " + f.getName());
                    return v;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 该对象是否持有长度 = SPECTRUM_BINS 的 float[] 字段（频谱类的特征） */
    private static boolean hasSpectrumArray(Object o) {
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != float[].class) continue;
                try {
                    f.setAccessible(true);
                    float[] a = (float[]) f.get(o);
                    if (a != null && a.length == SPECTRUM_BINS) return true;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /**
     * ★ 音符注入（自算，不依赖官方方法签名）。
     * 主 band 取 max，邻带按比例泄漏，让柱子有宽度。
     */
    private static void feedSpectrum(int instrumentId, int noteId) {
        if (!globalPlaying) return;
        int band = bandOfNote(noteId);
        if (band < 0 || band >= SPECTRUM_BINS) return;

        // 力度带来的高度起伏：不同音高给不同能量，柱子不再齐平
        float vel = 0.75F + 0.25F * ((Math.abs(noteId * 37) % 100) / 100.0F);
        float energy = GLOBAL_PEAK_ENERGY * (1.0F - GLOBAL_VELOCITY_VAR + GLOBAL_VELOCITY_VAR * vel);
        if (energy > 1.0F) energy = 1.0F;

        // ① 主路：直接写官方 previewPeaks（闸门已开，走官方 tick + 平滑器）
        if (globalSpectrumActive) {
            float[] peaks = getPreviewPeaks();
            if (peaks != null) {
                synchronized (LEVEL_LOCK) {
                    if (energy > peaks[band]) peaks[band] = energy;
                    if (band - 1 >= 0)                bumpPeak(peaks, band - 1, energy * GLOBAL_SPILL_1);
                    if (band + 1 < SPECTRUM_BINS)     bumpPeak(peaks, band + 1, energy * GLOBAL_SPILL_1);
                    if (band - 2 >= 0)                bumpPeak(peaks, band - 2, energy * GLOBAL_SPILL_2);
                    if (band + 2 < SPECTRUM_BINS)     bumpPeak(peaks, band + 2, energy * GLOBAL_SPILL_2);
                }
            }
        }

        // ② 兜路：自算数组（官方链路没打通时 GUI 内至少还能看到）
        synchronized (LEVEL_LOCK) {
            bumpBand(band, energy);
            if (band - 1 >= 0)                  bumpBand(band - 1, energy * GLOBAL_SPILL_1);
            if (band + 1 < SPECTRUM_BINS)       bumpBand(band + 1, energy * GLOBAL_SPILL_1);
        }
    }

    private static void bumpPeak(float[] peaks, int i, float v) {
        if (i < 0 || i >= peaks.length) return;
        if (v > peaks[i]) peaks[i] = v;
    }

    /**
     * 取官方 SpectrumVisualizer 的 previewPeaks（private float[16]）。
     * 必须按字段名精确取 —— 类里还有 currentLevels / prevLevels 两个
     * 同长度数组，写错了就完全没效果。
     */
    private static float[] getPreviewPeaks() {
        Object spec = getSpectrumInstance();
        if (spec == null) return null;
        Class<?> c = spec.getClass();
        while (c != null && c != Object.class) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("previewPeaks");
                f.setAccessible(true);
                Object v = f.get(spec);
                if (v instanceof float[] fa && fa.length == SPECTRUM_BINS) return fa;
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static void bumpBand(int i, float v) {
        if (i < 0 || i >= SPECTRUM_BINS) return;
        if (v > globalTarget[i]) globalTarget[i] = v;   // 只改目标值，显示值由 attack 平滑追上去
    }

    /** band 映射：默认与官方 Preview 一致（取模），可选对数频率 */
    private static int bandOfNote(int noteId) {
        if (GLOBAL_BAND_MODE == 0) {
            return Math.abs(noteId) % SPECTRUM_BINS;
        }
        int midi = noteId + 54;
        double freq = 440.0D * Math.pow(2.0D, (midi - 69.0D) / 12.0D);
        double t = Math.log(freq / 27.5D) / Math.log(4186.0D / 27.5D);   // A0..C8
        t = Math.max(0.0D, Math.min(1.0D, t));
        return (int) Math.round(t * (SPECTRUM_BINS - 1));
    }

    /**
     * ★ 每帧推进频谱：目标值匀速衰减，显示值 attack 上升、release 下降。
     * 全程按真实时间归一化，30/60/120fps 观感一致。
     */
    private static void tickGlobalLevels() {
        if (!globalPlaying) return;
        long now = System.nanoTime();
        if (globalLastTickNanos == 0L) { globalLastTickNanos = now; return; }
        double dt = (now - globalLastTickNanos) / 1_000_000_000.0D;
        globalLastTickNanos = now;
        if (dt <= 0D) return;
        if (dt > 0.25D) dt = 0.25D;          // 卡顿/切后台不要一次性掉光

        double decay  = Math.pow(GLOBAL_DECAY_PER_SEC, dt * GLOBAL_FALL_SPEED);
        float  attack = (float) (1.0D - Math.pow(1.0D - GLOBAL_ATTACK, Math.max(1.0D, dt * 60.0D)));

        synchronized (LEVEL_LOCK) {
            for (int i = 0; i < SPECTRUM_BINS; i++) {
                float tgt = globalTarget[i];
                if (tgt > globalLevels[i]) {
                    globalLevels[i] += (tgt - globalLevels[i]) * attack;
                } else {
                    globalLevels[i] *= (float) decay;
                }
                globalTarget[i] = (float) (tgt * decay);
                if (globalLevels[i] < 0.001F) globalLevels[i] = 0F;
                if (globalTarget[i] < 0.001F) globalTarget[i] = 0F;
            }
        }
        pushLevelsToOfficial();
    }

    /**
     * ★ 把自算结果写进官方实例的 float[16] 字段，驱动 HUD「始终显示」。
     * 不写死字段名（各版本命名不同），凡是长度等于柱数的 float[] 都写。
     */
    private static void pushLevelsToOfficial() {
        if (!GLOBAL_FEED_HUD) return;
        // ★ 听官方配置：Config.spectrumAlwaysVisible 关掉时就不驱动 HUD
        if (!isSpectrumAlwaysVisible()) return;

        float[] src = new float[SPECTRUM_BINS];
        synchronized (LEVEL_LOCK) { System.arraycopy(globalLevels, 0, src, 0, SPECTRUM_BINS); }

        // ★ 实例找不到也要继续往下写平滑器 —— HUD 多半直接读 Main.SMOOTHER，
        //   这里 return 掉会导致 HUD 完全不动（上一版 HUD 不显示就是这么来的）。
        Object spec = getSpectrumInstance();
        if (spec != null) {
        Class<?> c = spec.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != float[].class) continue;
                try {
                    f.setAccessible(true);
                    float[] arr = (float[]) f.get(spec);
                    if (arr == null || arr.length != SPECTRUM_BINS) continue;
                    System.arraycopy(src, 0, arr, 0, SPECTRUM_BINS);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        }   // 闭合 if (spec != null)

        // 平滑器双份缓冲都写，防止只读其中一份
        try {
            Object sm = Main.SMOOTHER;
            if (sm != null) {
                Class<?> sc = sm.getClass();
                while (sc != null && sc != Object.class) {
                    for (java.lang.reflect.Field f : sc.getDeclaredFields()) {
                        if (f.getType() != float[].class) continue;
                        try {
                            f.setAccessible(true);
                            float[] arr = (float[]) f.get(sm);
                            if (arr == null || arr.length != SPECTRUM_BINS) continue;
                            System.arraycopy(src, 0, arr, 0, SPECTRUM_BINS);
                        } catch (Throwable ignored) {}
                    }
                    sc = sc.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * ★ 读官方配置的 spectrumAlwaysVisible（Fabric: Main.config / NeoForge: Config.INSTANCE）。
     * 全局播放的 HUD 频谱必须听这个开关：用户关了就不驱动 HUD，尊重设置。
     * 两份 Config 都是 public boolean 实例字段，反射兼容两个平台；读不到按开启处理。
     */
    private static boolean isSpectrumAlwaysVisible() {
        try {
            Object cfg = null;
            try {
                java.lang.reflect.Field f = Main.class.getDeclaredField("config");
                f.setAccessible(true);
                cfg = f.get(null);
            } catch (Throwable ignored) {}
            if (cfg == null) {
                try {
                    Class<?> cc = Class.forName("semmiedev.disc_jockey.Config");
                    java.lang.reflect.Field inst = cc.getDeclaredField("INSTANCE");
                    inst.setAccessible(true);
                    cfg = inst.get(null);
                } catch (Throwable ignored) {}
            }
            if (cfg != null) {
                Class<?> c = cfg.getClass();
                while (c != null && c != Object.class) {
                    try {
                        java.lang.reflect.Field f = c.getDeclaredField("spectrumAlwaysVisible");
                        f.setAccessible(true);
                        Object v = f.get(cfg);
                        if (v instanceof Boolean b) return b;
                    } catch (Throwable ignored) {}
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    /**
     * 直接把频谱实例里表示「正在播放」的 boolean 字段置 true，打开官方闸门。
     * 这样即使 Previewer 空 Song 注入失败，官方 tick()/平滑器也会走完整链路。
     */
    private static void forceSpectrumGateOpen() {
        Object spec = getSpectrumInstance();
        if (spec == null) return;
        Class<?> c = spec.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() != boolean.class) continue;
                String n = f.getName().toLowerCase();
                if (!(n.contains("running") || n.contains("playing")
                   || n.contains("active")  || n.contains("enabled"))) continue;
                try {
                    f.setAccessible(true);
                    f.setBoolean(spec, true);
                    if (GLOBAL_SPECTRUM_DEBUG) System.out.println("[DJ] 闸门字段置位: " + f.getName());
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    /**
     * ★ 闸门自愈（根治「第一次播放不显示、第二次才显示」）。
     *
     * 根因：Main.PREVIEWER.stop() 是异步的。它的后台线程可能在【我们注入空 Song
     * 之后】才执行收尾清理（song=null / running=false），把刚打开的闸门重新关上。
     * 第一次播放时 Previewer 线程正活跃，注入必被覆盖；第二次播放时该线程早已
     * 退出、不再覆盖 —— 于是表现为「第一次不显示、第二次才显示」。
     *
     * 对策：广播期间周期性复查，发现 song 被清掉就立刻重新注入空 Song。
     * 只在 song == null 时才重写（真实歌曲不碰，避免破坏用户自己的预览）。
     */
    private static void ensureSpectrumGate() {
        if (!globalPlaying) return;
        try {
            Object previewer = Previewer.getInstance();
            if (previewer == null) { globalSpectrumActive = false; return; }

            java.lang.reflect.Field sf = findSongField(previewer.getClass());
            if (sf == null) { globalSpectrumActive = false; return; }
            sf.setAccessible(true);

            Object cur = sf.get(previewer);
            if (cur == null) {
                Object empty = createEmptySong();
                if (empty == null) { globalSpectrumActive = false; return; }
                sf.set(previewer, empty);
                if (GLOBAL_SPECTRUM_DEBUG) {
                    System.out.println("[DJ] 闸门自愈：空 Song 已重新注入");
                }
            }

            Previewer.running = true;
            globalSpectrumActive = true;
            forceSpectrumGateOpen();
        } catch (Throwable ignored) {
            globalSpectrumActive = false;
        }
    }

    /** 启动频谱守护线程：界面关掉后 HUD 仍要动 */
    private static void startGlobalSpectrumThread() {
        if (globalSpectrumThread != null && globalSpectrumThread.isAlive()) return;
        globalLastTickNanos = 0L;
        globalSpectrumThread = new Thread(() -> {
            int loop = 0;
            while (globalPlaying) {
                try {
                    // ★ 闸门自愈：头 12 次（约 200ms）每轮都查，之后每 ~256ms 查一次
                    if (loop < 12 || loop % 16 == 0) ensureSpectrumGate();
                    loop++;
                    tickGlobalLevels();
                    Thread.sleep(16L);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        }, "DJ-GlobalSpectrum");
        globalSpectrumThread.setDaemon(true);
        globalSpectrumThread.start();
    }

    /** 停止广播时清零：自算数组 + 官方实例，两边都归零 */
    private static void resetGlobalLevels() {
        synchronized (LEVEL_LOCK) {
            java.util.Arrays.fill(globalLevels, 0F);
            java.util.Arrays.fill(globalTarget, 0F);
        }
        globalLastTickNanos = 0L;
        pushLevelsToOfficial();
        try { Main.SMOOTHER.reset(); } catch (Throwable ignored) {}
    }



    /** 构造命令：让每个玩家在自己位置听到 */
    private static String buildPlaySound(int instrument, int noteId) {
        int i = (instrument >= 0 && instrument < NOTE_SOUND.length) ? instrument : 0;
        int k = noteId;
        while (k > 24) k -= 12;      // 八度折叠到 MC 音符盒可播区 0..24
        while (k < 0)  k += 12;
        float pitch = (float) Math.pow(2.0D, (k - 12) / 12.0D);
        String ps = String.format(java.util.Locale.US, "%.4f", pitch);
        String vs = String.format(java.util.Locale.US, "%.2f", GLOBAL_VOLUME);
        return "execute as @a at @s run playsound " + NOTE_SOUND[i]
                + " master @s ~ ~ ~ " + vs + " " + ps;
    }

    /** 取客户端连接对象（反射兼容 26.3 方法名差异） */
    private static Object getConnection() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        try {
            Method m = mc.getClass().getMethod("getConnection");
            return m.invoke(mc);
        } catch (Throwable ignored) {}
        Class<?> c = mc.getClass();
        while (c != null) {
            for (String fn : new String[]{"connection", "packetListener", "clientPacketListener"}) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(fn);
                    f.setAccessible(true);
                    return f.get(mc);
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 按 GLOBAL_CHAT_LEVEL 决定是否显示提示（0 = 完全不显示） */
    private static void globalChat(int minLevel, String text) {
        if (GLOBAL_CHAT_LEVEL >= minLevel) showMsg(text);
    }

    /** 关闭服务器命令反馈，避免每条 playsound 都往聊天栏回显 */
    private static void muteCommandFeedback() {
        if (!GLOBAL_MUTE_FEEDBACK) return;
        runGlobalCommand("gamerule send_command_feedback false");
        globalFeedbackMuted = true;
    }

    /** 恢复服务器命令反馈（在 stopGlobalPlay 里调用） */
    private static void restoreCommandFeedback() {
        if (!GLOBAL_MUTE_FEEDBACK || !globalFeedbackMuted) return;
        globalFeedbackMuted = false;
        final String cmd = "gamerule send_command_feedback "
                + (GLOBAL_FEEDBACK_RESTORE ? "true" : "false");
        runGlobalCommand(cmd);
        // ★ 补发一次：停止瞬间可能仍有排队的命令，或首条被限流丢弃
        new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) { return; }
            if (!globalPlaying) runGlobalCommand(cmd);
        }, "DJ-FeedbackRestore").start();
    }

    /** 发送命令（反射兼容 26.3 方法名差异；绝不使用 sendChat —— 那是聊天通道） */
    private static void runGlobalCommand(String cmd) {
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        try {
            Object conn = getConnection();
            if (conn != null) {
                for (String mn : new String[]{"sendCommand", "sendUnsignedCommand",
                                              "sendChatCommand", "sendCommandUnsigned"}) {
                    try {
                        Method m = conn.getClass().getMethod(mn, String.class);
                        m.invoke(conn, c);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // 兜底：本地玩家通道（connection 反射未命中时才走；command 优先于 chat）
        try {
            Minecraft mcx = Minecraft.getInstance();
            net.minecraft.client.player.LocalPlayer p = (mcx == null) ? null : mcx.player;
            if (p != null) {
                // command() 才是命令通道；chat() 会把命令文本当聊天发到聊天栏，只做最后兜底
                for (String mn : new String[]{"command", "sendCommand",
                                              "chat", "sendChatMessage"}) {
                    try {
                        Method m = p.getClass().getMethod(mn, String.class);
                        m.invoke(p, c);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    
    private static Float getCurrentSpeed() {
        try {
            // 预览/联播运行时，优先读 PREVIEW_SPEED
            if (Main.PREVIEWER.running) {
                return Main.PREVIEW_SPEED;
            }
            
            return Main.SONG_PLAYER.speed;
        } catch (Throwable t) {
            
            try {
                java.lang.reflect.Field f = Main.SONG_PLAYER.getClass().getDeclaredField("speed");
                f.setAccessible(true);
                return ((Number) f.get(Main.SONG_PLAYER)).floatValue();
            } catch (Throwable t2) {
                return 1.0F;
            }
        }
    }

    private static Component getPlaybackStateText() {
        if (!Main.SONG_PLAYER.running) {
            if (Main.SONG_PLAYER.didSongReachEnd) return SONGSTATE_FINISHED;
            if (Main.SONG_PLAYER.getSongElapsedSeconds() == 0.0) return SONGSTATE_STOPPED;
            return SONGSTATE_PAUSED;
        }
        return Main.SONG_PLAYER.tuner.isTuned() ? SONGSTATE_PLAYING : SONGSTATE_TUNING;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        BlitCompat.blit(
                context,
                AbstractSelectionList.INWORLD_MENU_LIST_BACKGROUND,
                5,
                32,
                width / 2,
                32 + 20 + 20 + 30 + 5 + 20 + 5,
                this.width / 2 - 10,
                20 + 20 + 30 + 5 + 20 + 5,
                32,
                32
        );
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        context.text(font, DROP_HINT, width / 2, 5, 0xFFFFFF);
        context.text(font, SELECT_SONG, (width / 4 * 3), 20, 0xFFFFFF);

        SongListWidget.SongEntry selected = songListWidget.getSelected();
        if (selected != null) {
            int cardX = 10, cardY = 32, cardW = width / 2 - 20, cardH = 36;
            context.fill(cardX + 2, cardY + 2, cardX + cardW, cardY + cardH, 0x55000000);
            context.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xFF2A2A2A);
            context.fill(cardX, cardY, cardX + 4, cardY + cardH, 0xFF00FFAA);
            context.text(
                    font,
                    Component.literal("♪ " + selected.song.displayName),
                    cardX + 8,
                    cardY + 6,
                    0xFFFFFFFF
            );
            context.text(
                    font,
                    Component.literal("by " + selected.song.author),
                    cardX + 8,
                    cardY + 20,
                    0xFFAAAAAA
            );
        }

        // 频谱由守护线程推进（tickGlobalLevels），界面开着读自算值，
        // 界面关掉后同一份数据仍会被推给官方实例，HUD 继续显示
        renderSpectrum(context, this.width, this.height);

        // 广播中：在频谱上方标一行状态，退出菜单重开也能看到
        if (globalPlaying) {
            String tip = "§6● 全局广播中" + (globalPlaySongName != null ? ("：" + globalPlaySongName) : "");
            context.text(font, Component.literal(tip), 12, height - 90, 0xFFFFAA00);
        }
    }

    @Override
    public void tick() {
        super.tick();
        refreshOffsetDisplay();
        songState.setMessage(getPlaybackStateText());
        timeBar.update();
        playPauseButton.setValue(Main.SONG_PLAYER.running);
        Song song = null;
        if (Main.SONG_PLAYER.running) {
            song = Main.SONG_PLAYER.getSong();
        } else if (Main.PREVIEWER.isRunning()) {
            song = Main.PREVIEWER.getInstance().getSong();
        }
        songTitle.setMessage(
                song != null ? Component.literal(song.displayName) : PLEASE_SELECT_SONG
        );
            if (song != null) {
            if (song.keySignature == null) song.detectKeySignature();
            if (song.keySignature != null) {
                String k = song.keySignature;
                if (!k.equals(this.lastShownKey)) {
                    this.lastShownKey = k;
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    mc.gui.hud.getChat().addClientSystemMessage(
                        net.minecraft.network.chat.Component.literal("§e[Disc Jockey] §fProbable Key Signature Recognition(可能的调号): §a" + k)
                    );
                }
            }
        } else {
            this.lastShownKey = null;
        }

        // ★ 不能只看 running：全局播放会把它置 true 以打开频谱闸门，
        //   必须同时判断 song != null，才算"真的在预览"。
        boolean previewActive = Main.PREVIEWER.running
                && songNoteCount(Main.PREVIEWER.getInstance().getSong()) > 0
                && !globalPlaying;
        previewButton.setMessage(previewActive ? PREVIEW_STOP : PREVIEW);
        playButton.setMessage(Main.SONG_PLAYER.running ? PLAY_STOP : PLAY);
        // ★ 互斥：本地播放/预览一旦启动，全局广播自动让位
        checkGlobalYieldToLocal();


        if (speedButton != null) {
            Float cur = getCurrentSpeed();
            if (!cur.equals(speedButton.getValue())) {
                speedButton.setValue(cur);
            }
        }
        if (transposeButton != null) {
            Integer tv = Main.SONG_PLAYER.transpose;
            if (!tv.equals(transposeButton.getValue())) {
                transposeButton.setValue(tv);
            }
        }
        if (globalPlayButton != null) {
            globalPlayButton.setMessage(globalPlaying
                    ? Component.translatable(Main.MOD_ID + ".screen.global_play.stop")
                    : Component.translatable(Main.MOD_ID + ".screen.global_play"));
        }

        if (shouldFilter) {
            shouldFilter = false;
            songListWidget.setScrollAmount(0);
            List<SongListWidget.SongEntry> entries = new java.util.ArrayList<>();
            boolean empty = query.isEmpty();
            int fav = 0;
            for (Song s : SongLoader.SONGS) {
                if (empty || s.searchableFileName.contains(query) || s.searchableName.contains(query)) {
                    if (s.entry != null) {
                        s.entry.songListWidget = songListWidget;
                        if (s.entry.favorite) entries.add(fav++, s.entry);
                        else entries.add(s.entry);
                    }
                }
            }
            songListWidget.safeReplaceEntries(entries);
        }
    }

    
    private void renderSpectrum(GuiGraphicsExtractor context, int screenWidth, int screenHeight) {
        // 官方链路打通后，GUI 与 HUD 读同一份数据（Main.SMOOTHER.getSmoothedLevels），
        // 观感与预览完全一致；没打通时才退回自算数组，保证 GUI 内至少看得见。
        float[] levels;
        if (globalPlaying) {
            levels = new float[SPECTRUM_BINS];
            synchronized (LEVEL_LOCK) { System.arraycopy(globalLevels, 0, levels, 0, SPECTRUM_BINS); }
        } else {
            levels = Main.SMOOTHER.getSmoothedLevels();
        }
        SpectrumRendererManager.getCurrent().render(
                context,
                screenWidth,
                screenHeight,
                levels,
                15,
                screenHeight - 75
        );
    }

    
    private int lerpColor(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ai = (a >> 24) & 0xFF;
        int ri = (a >> 16) & 0xFF;
        int gi = (a >> 8) & 0xFF;
        int bi = a & 0xFF;
        int ar = (b >> 24) & 0xFF;
        int rr = (b >> 16) & 0xFF;
        int gr = (b >> 8) & 0xFF;
        int br = b & 0xFF;
        int r = (int) (ri + (rr - ri) * t);
        int g = (int) (gi + (gr - gi) * t);
        int bb = (int) (bi + (br - bi) * t);
        int aa = (int) (ai + (ar - ai) * t);
        return (aa << 24) | (r << 16) | (g << 8) | bb;
    }

    /* ❌ 已删除 mouseButtonPressed(double, double, int)
       原因：26.3 下该处理器会对每一次点击都触发，导致点「播放 / 预览 / 检测音符盒」
             都被拦截并弹出 SongDetailScreen，按钮自身逻辑根本没执行（问题 2/3/4 根因）。
       右键查看详情待探测到 26.3 真实按键常量后再装回，见同目录 修改说明.txt。 */

    @Override
    public void onFilesDrop(List<Path> paths) {
        String str = paths.stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.joining(", "));
        if (str.length() > 300) str = str.substring(0, 300) + "...";

        
        Main.setScreenCompatStatic(minecraft, new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        paths.forEach(p -> {
                            try {
                                File f = p.toFile();
                                if (SongLoader.SONGS.stream()
                                        .anyMatch(s -> s.fileName.equalsIgnoreCase(f.getName()))) {
                                    return;
                                }
                                Song s = SongLoader.loadSong(f);
                                if (s != null) {
                                    try {
                                        Files.copy(p, Main.songsFolder.toPath().resolve(f.getName()));
                                    } catch (IOException ignored) {}
                                    SongLoader.SONGS.add(s);
                                }
                            } catch (Exception e) {
                                Main.LOGGER.warn("Failed to copy song file", e);
                            }
                        });
                        SongLoader.sort();
                    }
                    Main.setScreenCompatStatic(minecraft, this);
                },
                Component.translatable(Main.MOD_ID + ".screen.drop_confirm"),
                Component.literal(str)
        ));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    
    @Override
    public void onClose() {
        // ★ 不再停止全局广播：退出菜单后继续播，重新打开界面点同一按钮可停止
        if (globalPlaying) {
            globalChat(3, "§6[DiscJockey] 全局广播仍在后台进行，重新打开 DJ 界面可停止");
        }
        new Thread(() -> Main.configHolder.save()).start();
        if (parent != null) {
            Main.setScreenCompatStatic(Minecraft.getInstance(), parent);
        } else {
            super.onClose();
        }
    }
    
    public void markSongsDirty() {
        this.shouldFilter = true;
    }
    /** 26.3 已移除 Gui.getChat() 与 player.displayClientMessage()，改全反射（Fabric / NeoForge 通用） */
    private static void showMsg(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Component c = Component.literal(text);
        try {
            Object gui = mc.gui;
            if (gui != null) {
                for (Method m : gui.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && m.getName().equals("chatListener")) {
                        Object cl = m.invoke(gui);
                        if (cl == null) break;
                        for (Method hm : cl.getClass().getMethods()) {
                            if (hm.getName().equals("handleSystemMessage")
                                    && hm.getParameterCount() == 2
                                    && hm.getParameterTypes()[0].isAssignableFrom(Component.class)) {
                                hm.invoke(cl, c, false);
                                return;
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        Main.LOGGER.info("[DJ] {}", text);
    }
}
