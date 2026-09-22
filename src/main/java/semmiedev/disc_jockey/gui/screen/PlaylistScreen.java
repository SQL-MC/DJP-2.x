package semmiedev.disc_jockey.gui.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Config;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.PlaylistManager;
import semmiedev.disc_jockey.Song;
import semmiedev.disc_jockey.gui.PlaylistWidget;
import semmiedev.disc_jockey.gui.SongListWidget;

public class PlaylistScreen extends Screen {

    private final Screen parent;
    private PlaylistWidget playlistWidget;

    public PlaylistScreen(Screen parent) {
        super(Component.translatable(Main.MOD_ID + ".screen.playlist"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int top = 40;
        int bottom = height - 30;

        playlistWidget = new PlaylistWidget(
                minecraft, width - 20, bottom - top, top, 20);
        playlistWidget.setX(10);
        addRenderableWidget(playlistWidget);
        playlistWidget.refresh();

        int barY = height - 25;
        int bH = 20;

        // ＋ 加入列表
        Button addBtn = Button.builder(
                Component.literal("＋ ")
                        .append(Component.translatable(Main.MOD_ID + ".screen.playlist.add")),
                b -> {
                    Song sel = null;
                    if (parent instanceof DiscJockeyScreen djs) {
                        SongListWidget.SongEntry e = djs.getSongListWidget().getSelected();
                        if (e != null) sel = e.song;
                    }
                    if (sel != null && PlaylistManager.add(sel)) {
                        playlistWidget.refresh();
                        saveConfig();
                    }
                })
                .bounds(10, barY, 90, bH)
                .tooltip(Tooltip.create(
                        Component.translatable(Main.MOD_ID + ".screen.playlist.add.tooltip")))
                .build();
        addRenderableWidget(addBtn);

        // ⏮ 上一首
        addRenderableWidget(Button.builder(Component.literal("⏮"),
                b -> { PlaylistManager.skip(-1); playlistWidget.refresh(); })
                .bounds(105, barY, 30, bH).build());

        // ⏭ 下一首
        addRenderableWidget(Button.builder(Component.literal("⏭"),
                b -> { PlaylistManager.skip(1); playlistWidget.refresh(); })
                .bounds(140, barY, 30, bH).build());

        // 🔀 随机
        CycleButton<Boolean> shuffleBtn = CycleButton.onOffBuilder(PlaylistManager.shuffle())
                .withValues(true, false)
                .displayOnlyValue()
                .create(180, barY, 50, bH,
                        Component.translatable(Main.MOD_ID + ".screen.playlist.shuffle"),
                        (btn, value) -> { PlaylistManager.setShuffle(value); saveConfig(); });
        shuffleBtn.setTooltip(Tooltip.create(
                Component.translatable(Main.MOD_ID + ".screen.playlist.shuffle.tooltip")));
        addRenderableWidget(shuffleBtn);

        // 🔁 循环模式
        CycleButton<Config.RepeatMode> repeatBtn = CycleButton.<Config.RepeatMode>builder(
                mode -> Component.translatable(
                        Main.MOD_ID + ".screen.playlist.mode." + mode.name().toLowerCase()),
                PlaylistManager.mode())
                .withValues(Config.RepeatMode.values())
                .create(235, barY, 110, bH,
                        Component.translatable(Main.MOD_ID + ".screen.playlist.repeat"),
                        (btn, value) -> { PlaylistManager.setMode(value); saveConfig(); });
        repeatBtn.setTooltip(Tooltip.create(
                Component.translatable(Main.MOD_ID + ".screen.playlist.repeat.tooltip")));
        addRenderableWidget(repeatBtn);

        // 🗑 清空
        Button clearBtn = Button.builder(
                Component.translatable(Main.MOD_ID + ".screen.playlist.clear"),
                b -> { PlaylistManager.clear(); playlistWidget.refresh(); saveConfig(); })
                .bounds(350, barY, 70, bH)
                .tooltip(Tooltip.create(
                        Component.translatable(Main.MOD_ID + ".screen.playlist.clear.tooltip")))
                .build();
        addRenderableWidget(clearBtn);

        // ← 返回
        addRenderableWidget(Button.builder(Component.literal("← "),
                b -> Main.openScreenOnNextTick(parent))
                .bounds(width - 30, 5, 25, 20).build());
    }

    private void saveConfig() {
        try {
            Main.configHolder.save();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        // 标题 + 计数（26.3 用 centeredText，直接传 Component）
        context.centeredText(font,
                Component.translatable(Main.MOD_ID + ".screen.playlist")
                        .append(Component.literal("  (" + PlaylistManager.size() + ")")),
                width / 2, 10, 0xFFFFFF);
    }
}