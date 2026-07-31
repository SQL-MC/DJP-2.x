package semmiedev.disc_jockey.gui.screen;

import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager;
import me.shedaniel.autoconfig.AutoConfigClient;
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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import semmiedev.disc_jockey.*;
import semmiedev.disc_jockey.gui.SongListWidget;
import semmiedev.disc_jockey.gui.SongTimeSliderWidget;
import semmiedev.disc_jockey.gui.hud.BlocksOverlay;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class DiscJockeyScreen extends Screen {

    /* =========================================================
       ✅ 频谱样式（不拆文件，全部内聚）【OLD】
       ========================================================= */
    /*
    public enum SpectrumStyle {
        BAR("条形"),
        WAVE("波形"),
        RING("圆环"),
        MIRROR("镜像对称"),
        PARTICLE("粒子");

        public final String displayName;

        SpectrumStyle(String displayName) {
            this.displayName = displayName;
        }
    }

    private static SpectrumStyle spectrumStyle = SpectrumStyle.BAR;

    public static void cycleSpectrumStyle() {
        SpectrumStyle[] values = SpectrumStyle.values();
        spectrumStyle = values[(spectrumStyle.ordinal() + 1) % values.length];
    }

    public static SpectrumStyle getSpectrumStyle() {
        return spectrumStyle;
    }
    */

    /* =========================================================
       ✅ 原有常量（一字未动，仅修正 API 拼写）
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
       ✅ 新增：频谱样式切换按钮（完整保留，不删不减）
       ========================================================= */
    private CycleButton<SpectrumRendererManager.Style> spectrumStyleButton;

    private SongListWidget songListWidget;
    private Button playButton, previewButton;
    private boolean shouldFilter;
    private String query = "";

    /* =========================================================
       ✅ 频谱平滑缓存（解决最高处卡一下/生硬）
       ========================================================= */
    private float[] smoothedLevels = new float[16];

    public DiscJockeyScreen() {
        super(Main.NAME);
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

        songState = new StringWidget(10, 32, width / 2 - 20, 20, Component.empty(), getFont());
        addRenderableWidget(songState);

        songTitle = new StringWidget(10, 32 + 20, width / 2 - 20, 20, Component.empty(), getFont());
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

        configButton = Button.builder(CONFIG, b ->
                minecraft.setScreenAndShow(AutoConfigClient.getConfigScreen(Config.class, this).get())
        ).pos(10, height - 30).size(100, 20).build();
        addRenderableWidget(configButton);

        /* =========================================================
           ✅【新增】MIDI 导出按钮（单行选中导出 · 多语言 · 带路径提示）
           ✅ 位置：configButton 右侧，spectrumStyleButton 左侧
           ✅ 尺寸与其他按钮完全一致（100×20）
           ✅ 导出成功后聊天栏显示完整绝对路径
           ✅【新增】导出后自动打开 midi 文件夹
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

                        /* =========================================================
                           ✅【新增】导出成功后一键打开 midi 文件夹
                           ========================================================= */
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
        /* ========================================================= */

        /* =========================================================
           ✅【26.2 强制兼容】CycleButton 仅存签名：builder(Function, T)
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
           ⚠️【原 AWT Import MIDI 按钮 · 完整保留 · 仅注释】
           ⚠️ Windows / JDK 25 / Fabric 环境下静默失败
           ⚠️ 不做删除，仅注释，方便回溯
           ========================================================= */
        /*
        Button importMidiButton = Button.builder(
                Component.translatable("disc_jockey.screen.import_midi"),
                btn -> {
                    File midiDir = new File(Main.songsFolder.getParentFile(), "midi");
                    if (!midiDir.exists() && !midiDir.mkdirs()) {
                        minecraft.gui.chatListener().handleSystemMessage(
                                Component.translatable("disc_jockey.import.no_midi_folder"),
                                false
                        );
                        return;
                    }

                    // ✅ 关键：切到 AWT 事件队列，否则 Windows 不弹窗
                    EventQueue.invokeLater(() -> {
                        FileDialog dialog = new FileDialog(
                                (Frame) null,
                                "Select MIDI File",
                                FileDialog.LOAD
                        );
                        dialog.setDirectory(midiDir.getAbsolutePath());
                        dialog.setFile("*.mid;*.midi");
                        dialog.setVisible(true);

                        String file = dialog.getFile();
                        if (file == null) {
                            return; // 用户取消
                        }

                        File midiFile = new File(dialog.getDirectory(), file);

                        // ✅ 切回 MC 主线程处理结果
                        minecraft.execute(() -> {
                            try {
                                Song song = MidiToNbsImporter.importMidi(midiFile);
                                if (song == null) {
                                    minecraft.gui.chatListener().handleSystemMessage(
                                            Component.translatable("disc_jockey.import.empty_or_invalid"),
                                            false
                                    );
                                    return;
                                }

                                SongLoader.SONGS.add(song);
                                SongLoader.sort();
                                shouldFilter = true;

                                minecraft.gui.chatListener().handleSystemMessage(
                                        Component.translatable(
                                                "disc_jockey.import.success",
                                                song.displayName
                                        ),
                                        false
                                );
                            } catch (Exception e) {
                                Main.LOGGER.error("MIDI import failed", e);
                                minecraft.gui.chatListener().handleSystemMessage(
                                        Component.translatable("disc_jockey.import.error"),
                                        false
                                );
                            }
                        });
                    });
                }
        ).pos(325, height - 30).size(100, 20).build();
        addRenderableWidget(importMidiButton);
        */
        /* ========================================================= */

        /* =========================================================
           ✅【当前生效】Fabric 原生 Import MIDI 按钮
           ✅ 100% 兼容 Windows / JDK 25 / Fabric
           ✅ 不依赖 AWT / 不弹系统窗口
           ✅ 游戏内文件列表，日志可控
           ✅【26.2 专用】位于屏幕最顶上右上角
           ========================================================= */
        Button importMidiButton = Button.builder(
                Component.translatable("disc_jockey.screen.import_midi"),
                btn -> {
                    try {
                        Minecraft.getInstance().gui.setScreen(new MidiFileSelectScreen(this));
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
           ✅【修正】Import MIDI 路径提示（悬浮完整路径，不被截断）
           ✅ 显示文本：省略
           ✅ Tooltip：完整绝对路径
           ✅ 26.2 原生 Tooltip（无 withMaxWidth，防编译失败）
           ========================================================= */
        File midiDir = new File(Main.songsFolder.getParentFile(), "midi");
        String fullPath = midiDir.getAbsolutePath();
        String displayPath = fullPath;
        if (displayPath.length() > 26) {
            displayPath = "..." + displayPath.substring(displayPath.length() - 23);
        }

        StringWidget midiPathHint = new StringWidget(
                width - 110,
                5 + 20 + 2,
                100,
                9,
                Component.literal(displayPath)
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
                getFont()
        );
        midiPathHint.active = false;
        midiPathHint.visible = true;

        // ✅ 26.2 Fabric：Tooltip.create 即可，自动换行，无需 withMaxWidth
        midiPathHint.setTooltip(Tooltip.create(Component.literal(fullPath)));

        addRenderableWidget(midiPathHint);
        /* ========================================================= */
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
        context.blit(
                RenderPipelines.GUI_TEXTURED,
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
       ✅ 新频谱渲染入口（带上升平滑，解决最高处卡一下）
       ========================================================= */
    private void renderSpectrum(GuiGraphicsExtractor context, int screenWidth, int screenHeight) {
        float[] raw = Main.SPECTRUM.currentLevels;

        final float attack = 0.2F;
        final float decay = 0.45F;

        for (int i = 0; i < smoothedLevels.length && i < raw.length; i++) {
            float target = raw[i];
            float current = smoothedLevels[i];

            if (target > current) {
                smoothedLevels[i] += (target - current) * attack;
            } else {
                smoothedLevels[i] += (target - current) * decay;
            }

            if (Math.abs(smoothedLevels[i] - target) < 0.001F) {
                smoothedLevels[i] = target;
            }
        }

        SpectrumRendererManager.getCurrent().render(
                context,
                screenWidth,
                screenHeight,
                smoothedLevels,
                15,
                screenHeight - 75
        );
    }

    /* =========================================================
       ✅ 旧频谱渲染逻辑（全部保留，仅注释）【OLD】
       ========================================================= */
    /*
    private void renderSpectrum(GuiGraphicsExtractor context, int screenWidth, int screenHeight) {
        SpectrumVisualizer visualizer = Main.SPECTRUM;
        float[] levels = visualizer.currentLevels;

        int barCount = 16;
        int barWidth = 5;
        int barGap = 2;
        int totalWidth = barCount * (barWidth + barGap) - barGap;
        int maxHeight = 64;
        int marginBottom = 15;
        int marginLeft = 15;

        int startX = marginLeft;
        int baseY = screenHeight - marginBottom;

        switch (spectrumStyle) {
            case BAR -> renderSpectrumBar(context, levels, startX, baseY, barCount, barWidth, barGap, maxHeight);
            case WAVE -> renderSpectrumWave(context, levels, startX, baseY, barCount, maxHeight);
            case RING -> renderSpectrumRing(context, levels, startX, baseY, barCount, maxHeight);
            case MIRROR -> renderSpectrumMirror(context, startX, baseY, barCount, barWidth, barGap, maxHeight);
            case PARTICLE -> renderSpectrumParticle(context, levels, startX, baseY, barCount, maxHeight);
        }
    }

    private void renderSpectrumBar(GuiGraphicsExtractor context, float[] levels, int startX, int baseY,
                                   int barCount, int barWidth, int barGap, int maxHeight) {
        int totalWidth = barCount * (barWidth + barGap) - barGap;

        for (int i = 0; i < barCount; i++) {
            float level = levels[i];
            int barHeight = Math.max(2, (int) (level * maxHeight));

            int x = startX + i * (barWidth + barGap);
            int topY = baseY - barHeight;

            int color;
            if (level < 0.33F) {
                color = lerpColor(0xFF00AA00, 0xFFFFDD00, level / 0.33F);
            } else if (level < 0.66F) {
                color = lerpColor(0xFFFFDD00, 0xFFFF6600, (level - 0.33F) / 0.33F);
            } else {
                color = lerpColor(0xFFFF6600, 0xFFFF2200, (level - 0.66F) / 0.34F);
            }

            context.fill(x, topY, x + barWidth, baseY, color);

            if (barHeight > 4) {
                context.fill(x, topY, x + barWidth, topY + 2, 0x88FFFFFF);
            }
        }

        context.fill(startX - 2, baseY, startX + totalWidth + 2, baseY + 1, 0x66FFFFFF);
    }

    private void renderSpectrumWave(GuiGraphicsExtractor context, float[] levels, int startX, int baseY,
                                    int barCount, int maxHeight) {
        int spacing = 4;
        for (int i = 0; i < barCount - 1; i++) {
            float l1 = levels[i];
            float l2 = levels[i + 1];
            int x1 = startX + i * spacing;
            int y1 = baseY - (int) (l1 * maxHeight);
            int x2 = startX + (i + 1) * spacing;
            int y2 = baseY - (int) (l2 * maxHeight);
            int color = lerpColor((l1 + l2) * 0.5F);
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            context.fill(minX, minY, maxX + 1, maxY + 1, color);
        }
        for (int i = 0; i < barCount; i++) {
            int x = startX + i * spacing;
            int y = baseY - (int) (levels[i] * maxHeight);
            context.fill(x - 1, y - 1, x + 1, y + 1, 0xFFFFFFFF);
        }
    }

    private void renderSpectrumRing(GuiGraphicsExtractor context, float[] levels, int startX, int baseY,
                                    int barCount, int maxHeight) {
        int centerX = startX + 40;
        int centerY = baseY - 10;
        int maxRadius = 50;
        for (int i = 0; i < barCount; i++) {
            float level = levels[i];
            int radius = 10 + (int) (level * maxRadius);
            double angle = 2 * Math.PI * i / barCount - Math.PI / 2;
            int x = centerX + (int) (Math.cos(angle) * radius);
            int y = centerY + (int) (Math.sin(angle) * radius);
            int innerX = centerX + (int) (Math.cos(angle) * 10);
            int innerY = centerY + (int) (Math.sin(angle) * 10);
            int color = lerpColor(level);
            int minX = Math.min(innerX, x);
            int minY = Math.min(innerY, y);
            int maxX = Math.max(innerX, x);
            int maxY = Math.max(innerY, y);
            context.fill(minX, minY, maxX + 1, maxY + 1, color);
        }
    }

    private void renderSpectrumMirror(GuiGraphicsExtractor context, float[] levels, int startX, int baseY,
                                      int barCount, int barWidth, int barGap, int maxHeight) {
        int half = maxHeight / 2;
        for (int i = 0; i < barCount; i++) {
            float level = levels[i];
            int barHeight = Math.max(1, (int) (level * half));
            int x = startX + i * (barWidth + barGap);
            int topY = baseY - barHeight;
            int color = lerpColor(level);
            context.fill(x, topY, x + barWidth, baseY, color);
            context.fill(x, baseY, x + barWidth, baseY + barHeight, color);
            context.fill(x, baseY, x + barWidth, baseY + 1, 0x88FFFFFF);
        }
    }

    private void renderSpectrumParticle(GuiGraphicsExtractor context, float[] levels, int startX, int baseY,
                                        int barCount, int maxHeight) {
        int dotSize = 3;
        int spacing = 6;
        for (int i = 0; i < barCount; i++) {
            float level = levels[i];
            int dotCount = Math.max(1, (int) (level * 10));
            int sx = startX + i * spacing;
            for (int j = 0; j < dotCount; j++) {
                int y = baseY - j * dotSize - dotSize;
                int alpha = (int) (level * 200) + 55;
                alpha = Math.min(255, alpha);
                int color = (alpha << 24) | 0x00FFAA;
                context.fill(sx, y, sx + dotSize, y + dotSize, color);
            }
        }
    }

    private int lerpColor(float t) {
        t = Math.max(0f, Math.min(1f, t));
        if (t < 0.33F) {
            return lerpColor(0xFF00AA00, 0xFFFFDD00, t / 0.33F);
        } else if (t < 0.66F) {
            return lerpColor(0xFFFFDD00, 0xFFFF6600, (t - 0.33F) / 0.33F);
        } else {
            return lerpColor(0xFFFF6600, 0xFFFF2200, (t - 0.66F) / 0.34F);
        }
    }
    */

    /* =========================================================
       ✅ lerpColor：原版完整保留（仍在使用）
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
                minecraft.setScreenAndShow(new SongDetailScreen(entry.song));
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

        minecraft.setScreenAndShow(new ConfirmScreen(
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
                                    Files.copy(p, Main.songsFolder.toPath().resolve(f.getName()));
                                    SongLoader.SONGS.add(s);
                                }
                            } catch (IOException e) {
                                Main.LOGGER.warn("Failed to copy song file", e);
                            }
                        });
                        SongLoader.sort();
                    }
                    minecraft.setScreenAndShow(this);
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
        super.onClose();
        new Thread(() -> Main.configHolder.save()).start();
    }
}