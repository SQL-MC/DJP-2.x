package semmiedev.disc_jockey.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;

public class SongTimeSliderWidget extends AbstractSliderButton {

    public SongTimeSliderWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(), 0);
    }

    private static String padZeroes(int number, int length) {
        StringBuilder builder = new StringBuilder("" + number);
        while (builder.length() < length) {
            builder.insert(0, '0');
        } return builder.toString();
    }

    private static String formatTimestamp(int seconds) {
        return padZeroes(seconds / 60, 2) + ":" + padZeroes(seconds % 60, 2);
    }

    @Override
    protected void updateMessage() {
        if (Main.SONG_PLAYER.song == null) {
            setMessage(Component.empty());
        } else {
            setMessage(Component.literal(formatTimestamp((int) Main.SONG_PLAYER.getSongElapsedSeconds()) + " / " + formatTimestamp((int) Main.SONG_PLAYER.song.getLengthInSeconds())));
        }
    }

    @Override
    protected void applyValue() {
        if(Main.SONG_PLAYER.song == null) return;
        double total = Main.SONG_PLAYER.song.getLengthInSeconds();
        double seconds = value * total;
        Main.SONG_PLAYER.setSongElapsedSeconds(seconds);
    }

    public void update() {
        if (Main.SONG_PLAYER.song == null) return;
        double elapsed = Main.SONG_PLAYER.getSongElapsedSeconds();
        double total = Main.SONG_PLAYER.song == null ? 1 : Main.SONG_PLAYER.song.getLengthInSeconds();
        value = elapsed / total;
        updateMessage();
    }

    /* ========== ✅ 26.2 修复：实现 AbstractWidget 要求的 public 抽象方法 ==========
       ✅ 修复 AbstractMethodError（Narrator 触发 runNarration → updateNarration 崩溃）
       ✅ 方法签名：public void updateWidgetNarration(NarrationElementOutput)
       ✅ 仅 output.add(...) 提供朗读内容；不调用不存在的 defaultNarrationText */
    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        if (Main.SONG_PLAYER.song != null) {
            int cur = (int) Main.SONG_PLAYER.getSongElapsedSeconds();
            int len = (int) Main.SONG_PLAYER.song.getLengthInSeconds();
            output.add(NarratedElementType.TITLE, Component.literal("播放进度"));
            output.add(NarratedElementType.HINT, Component.literal(formatTimestamp(cur) + " / " + formatTimestamp(len)));
        } else {
            output.add(NarratedElementType.TITLE, Component.literal("暂无正在播放的歌曲"));
        }
    }
}
