package semmiedev.disc_jockey.gui.screen;

import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager;
import me.shedaniel.autoconfig.AutoConfigClient;
import java.nio.file.StandardCopyOption;   // ← 文件复制要
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;            // ← Files.walk 的 stream 要
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
import java.io.ObjectInputFilter.Config;
//import java.io.ObjectInputFilter.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class DiscJockeyScreen extends Screen {
    /* ========== ★ 一键同步本地 NBS 仓库 ==========
       ✅ 源路径硬编码 D:/songs/songs（你的本地 NBS 仓库）
       ✅ 目标用 Main.songsFolder（config/disc_jockey/songs），与 SongLoader 一致
       ✅ 子线程复制 → 不卡 UI；minecraft.execute 回主线程刷新
       ✅ 轮询 loadingSongs，避免读到半截数据 */
    private static final Path NBS_SOURCE = Paths.get("D:/songs/songs");

    /** 对 GitHub raw 的 path 做分段 URL 编码。
     *  空格→%20，中文→UTF-8 百分号编码，斜杠保留原样。 */
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
/* ========== ★ 从 GitHub 仓库 SQL-MC/Nbs 下载 NBS ==========
       ✅ 用 GitHub Tree API 拿 songs/ 目录文件列表
       ✅ 用 raw.githubusercontent.com 直链下载
       ✅ 子线程下载 → 不卡 UI
       ✅ 下载完回主线程重建歌单 */
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
                // ✅ 第1步：Tree API 拿完整文件清单
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

            // ✅ 第2步：逐个下载
            int ok = 0, fail = 0;
            for (String path : nbsFiles) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                try {
                    String url = RAW_BASE + encodePath(path);   // ✅ 加编码
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

    /** 解析 GitHub Tree API 的 JSON，筛出 songs/ 下的 .nbs 文件 */
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

    /** 简单 HTTP GET，带 UA 头（GitHub API 要求 UA） */
    private String httpGet(String urlStr) throws IOException {
        java.net.URL url = new java.net.URL(urlStr);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "DiscJockey/2.6.3");   // ← GitHub 强制要求
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " for " + urlStr);
        try (java.io.InputStream in = conn.getInputStream();
             java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    /** 轮询 loadingSongs，加载完再刷新列表 */
    private void awaitSongReload() {
        if (SongLoader.loadingSongs) {
            minecraft.execute(() -> awaitSongReload());
            return;
        }
        rebuildSongList();
    }

    /** 用 SongLoader.SONGS 重建 SongListWidget 条目 */
    private void rebuildSongList() {
        if (this.songListWidget == null) return;
        java.util.List<SongListWidget.SongEntry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < SongLoader.SONGS.size(); i++) {
            entries.add(new SongListWidget.SongEntry(SongLoader.SONGS.get(i), i));
        }
        songListWidget.safeReplaceEntries(entries);
    }
    /* =========================================================
       ✅ parent screen：从主菜单/暂停菜单打开时记录，关闭后返回
       ========================================================= */
    private Screen parent = null;

    public void setParent(Screen parent) { this.parent = parent; }
    /* =========================================================
       ✅ 原有常量（一字未动）
       ========================================================= */
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

    /* =========================================================
       ✅ 频谱样式切换按钮
       ========================================================= */
    private CycleButton<SpectrumRendererManager.Style> spectrumStyleButton;

    /* =========================================================
       ✅✅✅ 速度 + 移调按钮（DJP021700：频谱右侧，对齐 DiscjockeyCommand）
       ========================================================= */
    /** 速度预设（0.5 / 0.75 / 1.0 / 1.25 / 1.5），与 /discjockey speed 一致 */
    private static final Float[] SPEED_VALUES = { 0.5F, 0.75F, 1.0F, 1.25F, 1.5F };
    /** 移调预设（半音），与 /discjockey transpose 范围 -24~24 的子集 */
    private static final Integer[] TRANSPOSE_VALUES = { -12, -6, -3, 0, 3, 6, 12 };

    private CycleButton<Float> speedButton;
    private CycleButton<Integer> transposeButton;

    private SongListWidget songListWidget;
    private Button playButton, previewButton;
    private boolean shouldFilter;
    private String query = "";

    /* =========================================================
       ✅ 频谱平滑缓存
       ========================================================= */
    private float[] smoothedLevels = new float[16];

    /* =========================================================
       ✅ 构造器：无参（J键）+ parent版（菜单按钮）
       ========================================================= */
    /** 从 J 键 / 正常流程进来：无 parent */
    public DiscJockeyScreen() {
        super(Main.NAME);
        this.parent = null;
    }

    /** 从主菜单 / 暂停菜单按钮进来：带 parent，关闭时回退 */
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
            if (Main.SONG_PLAYER.running) Main.SONG_PLAYER.stop();
            else {
                SongListWidget.SongEntry e = songListWidget.getSelected();
                if (e != null) Main.SONG_PLAYER.start(e.song);
            }
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

        /* =========================================================
           ✅ Config 按钮（26.2 正确 API：走 Main.setScreenCompatStatic）
           ========================================================= */
        configButton = Button.builder(CONFIG, b ->
                Main.setScreenCompatStatic(minecraft,
                        AutoConfigClient.getConfigScreen(semmiedev.disc_jockey.Config.class, this).get())
        ).pos(10, height - 30).size(100, 20).build();
        addRenderableWidget(configButton);

        /* =========================================================
           ✅ MIDI 导出按钮（完整保留）
           ========================================================= */
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

/* =========================================================
           ★ 新增：从 GitHub 仓库 SQL-MC/Nbs 下载 NBS 到本地
           ✅ 源：GitHub raw + Tree API
           ✅ 目标：Main.songsFolder（= config/disc_jockey/songs）
           ✅ 网络请求在子线程跑，不卡 UI
           ✅ 下载完回主线程重建歌单
           ========================================================= */
        Button downloadNbsButton = Button.builder(
                Component.literal("下载NBS"),
                btn -> downloadNbsFromGithub()
        ).pos(10, height - 55)
         .size(100, 20)
         .tooltip(Tooltip.create(Component.literal("从 GitHub:SQL-MC/Nbs 下载 NBS 到本地")))
         .build();
        addRenderableWidget(downloadNbsButton);
        /* =========================================================
           ✅ WAV 导出按钮（方案C：纯 Java 合成真实音频，零依赖）
           ✅ 位置：export_midi 正上方，组成"导出组"
           ✅ 文案全部硬编码字面量，不走 lang key
           ✅ 合成较慢，放后台线程，避免卡 UI
           ========================================================= */
        Button exportWavButton = Button.builder(
                Component.literal("WAV OUT"),
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
                    btn.active = false;   // ★ 导出期间置灰，防重复点击
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
                            minecraft.execute(() -> btn.active = true);  // ★ 恢复
                        }
                    }, "DJ-WAV-Export").start();
                }
        ).pos(115, height - 55).size(100, 20).build();
        addRenderableWidget(exportWavButton);

        /* =========================================================
           ✅ 频谱样式按钮（26.2 正确签名）
           ========================================================= */
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

        /* =========================================================
           ✅✅✅ 移调按钮（DJP021700：IMPORT MIDI 左边最左侧，同一行 y=5）
           ========================================================= */
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

        /* =========================================================
           ✅✅✅ 速度按钮（DJP021700：IMPORT MIDI 左边，移调右边，同一行 y=5）
           ========================================================= */
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

        /* =========================================================
           ✅ Piano 按钮（26.2 正确 API：走 Main.setScreenCompatStatic）
           ========================================================= */
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

        /* =========================================================
           ✅ Fabric 原生 Import MIDI 按钮（26.2 正确 API）
           ✅ 位置：屏幕右上角（最右边）
           ✅ 走 Main.setScreenCompatStatic
           ========================================================= */
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

        /* =========================================================
           ✅ Import MIDI 路径提示（在 IMPORT MIDI 下方）
           ========================================================= */
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

    /* =========================================================
       ✅✅✅ 读取当前播放速度（DJP021700：兼容字段名差异）
       ========================================================= */
    private static Float getCurrentSpeed() {
        try {
            // 预览/联播运行时，优先读 PREVIEW_SPEED
            if (Main.PREVIEWER.running) {
                return Main.PREVIEW_SPEED;
            }
            // 普通播放，读 SONG_PLAYER.speed
            return Main.SONG_PLAYER.speed;
        } catch (Throwable t) {
            // 兜底：尝试反射（兼容旧版本字段名）
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

        renderSpectrum(context, this.width, this.height);
    }

    @Override
    public void tick() {
        songState.setMessage(getPlaybackStateText());
        timeBar.update();
        playPauseButton.setValue(Main.SONG_PLAYER.running);
        songTitle.setMessage(
                Main.SONG_PLAYER.song != null
                        ? Component.literal(Main.SONG_PLAYER.song.displayName)
                        : PLEASE_SELECT_SONG
        );
        previewButton.setMessage(Main.PREVIEWER.running ? PREVIEW_STOP : PREVIEW);
        playButton.setMessage(Main.SONG_PLAYER.running ? PLAY_STOP : PLAY);

        /* =========================================================
           ✅✅✅ 同步速度/移调按钮显示（DJP021700：值与 SONG_PLAYER 保持一致）
           ========================================================= */
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

    /* =========================================================
       ✅ 频谱渲染（读 SMOOTHER 缓冲）
       ========================================================= */
    private void renderSpectrum(GuiGraphicsExtractor context, int screenWidth, int screenHeight) {
        float[] levels = Main.SMOOTHER.getSmoothedLevels();
        SpectrumRendererManager.getCurrent().render(
                context,
                screenWidth,
                screenHeight,
                levels,
                15,
                screenHeight - 75
        );
    }

    /* =========================================================
       ✅ lerpColor（保留使用）
       ========================================================= */
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

    public boolean mouseButtonPressed(double mouseX, double mouseY, int button) {
        if (button == 1) {
            SongListWidget.SongEntry entry = songListWidget.getSelected();
            if (entry != null) {
                Screen detail = new SongDetailScreen(entry.song);
                Main.setScreenCompatStatic(minecraft, detail);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        String str = paths.stream()
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.joining(", "));
        if (str.length() > 300) str = str.substring(0, 300) + "...";

        // ✅ FIX：26.2 没有 minecraft.setScreen()，改用 Main.setScreenCompatStatic
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

    /* =========================================================
       ✅ onClose：有 parent 回退菜单，无 parent 走默认
       ✅ 走 Main.setScreenCompatStatic（26.2 兼容）
       ========================================================= */
    @Override
    public void onClose() {
        new Thread(() -> Main.configHolder.save()).start();
        if (parent != null) {
            Main.setScreenCompatStatic(Minecraft.getInstance(), parent);
        } else {
            super.onClose();
        }
    }
    /* =========================================================
   ✅ MIDI 导入后通知列表刷新（供 MidiFileSelectScreen 回调）
   ✅ 内部走 shouldFilter 机制（与 init() 里的导入按钮回调一致）
   ========================================================= */
    public void markSongsDirty() {
        this.shouldFilter = true;
    }
}