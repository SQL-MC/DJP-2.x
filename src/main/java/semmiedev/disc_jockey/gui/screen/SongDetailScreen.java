package semmiedev.disc_jockey.gui.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Song;

public class SongDetailScreen extends Screen {
    private final Song song;

    public SongDetailScreen(Song song) {
        super(Component.literal("Song Details"));
        this.song = song;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int startY = height / 2 - 80;

        String displayName = (song.displayName != null) ? song.displayName : "(unknown)";
        String author = (song.author != null) ? song.author : "Unknown";
        String originalAuthor = (song.originalAuthor != null) ? song.originalAuthor : "Unknown";
        String desc = (song.description != null) ? song.description : "None";
        if (desc.length() > 120) desc = desc.substring(0, 120) + "...";

        addRenderableWidget(Button.builder(Component.literal("♪ " + displayName), b -> {})
                .bounds(centerX - 150, startY, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Author: " + author), b -> {})
                .bounds(centerX - 150, startY + 25, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Original: " + originalAuthor), b -> {})
                .bounds(centerX - 150, startY + 50, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Desc: " + desc), b -> {})
                .bounds(centerX - 150, startY + 75, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Length: " + String.format("%.1f s", song.getLengthInSeconds())), b -> {})
                .bounds(centerX - 150, startY + 100, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Notes: " + song.notes.length), b -> {})
                .bounds(centerX - 150, startY + 125, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Back"), b -> {
                    // 26.4 下 Gui.setScreen 存在失效记录（日志：setScreen 全部失败：null），
                    // 优先走 Main 的兼容派发；全部失败也要保证能退出，绝不卡死在详情页。
                    try {
                        Main.setScreenCompatStatic(minecraft, null);
                        return;
                    } catch (Throwable ignored) {}
                    try {
                        minecraft.gui.setScreen(null);
                    } catch (Throwable ignored) {}
                }
        ).bounds(centerX - 50, startY + 160, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context) {
        super.extractRenderState(context);
        context.fill(0, 0, width, height, 0xCC000000);
    }
}
