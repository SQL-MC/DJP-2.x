package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * NeoForge 26.3 配置界面。
 *
 * 替代 Fabric 的 AutoConfigClient.getConfigScreen(...)：
 * 原版是通过设置按钮打开配置屏，这里保留完全相同的入口与可改项，
 * 只是底层从 AutoConfig 换成 ModConfigSpec（Config.SPEC）。
 *
 * 只依赖已确认存在的 26.3 API：
 *   - Screen(Component)             构造
 *   - init() / clearWidgets() / addRenderableWidget()
 *   - GuiGraphicsExtractor.fill(int,int,int,int,int)
 *   - Button.builder(Component, Button.OnPress).bounds(x,y,w,h).build()
 *   - Button.setMessage(Component)
 */
public class ConfigScreen extends Screen {

    private final Screen parent;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("screen.disc_jockey.config.title"));
        this.parent = parent;
    }

    // ==================== 布局 ====================

    @Override
    protected void init() {
        clearWidgets();

        int colW = Math.max(150, (this.width - 60) / 2);
        int leftX = 20;
        int rightX = 20 + colW + 20;
        int rowH = 22;
        int y = 40;

        Config c = Config.INSTANCE;

        // ---------- 左列：开关 + 枚举 ----------
        addToggle(leftX, y, "disableAsyncPlayback", c.disableAsyncPlayback, v -> c.disableAsyncPlayback = v);
        y += rowH;
        addToggle(leftX, y, "hideWarning", c.hideWarning, v -> c.hideWarning = v);
        y += rowH;
        addToggle(leftX, y, "omnidirectionalNoteBlockSounds", c.omnidirectionalNoteBlockSounds,
                v -> c.omnidirectionalNoteBlockSounds = v);
        y += rowH;
        addToggle(leftX, y, "spectrumAlwaysVisible", c.spectrumAlwaysVisible,
                v -> c.spectrumAlwaysVisible = v);
        y += rowH;
        addToggle(leftX, y, "instrumentDetectionWorkaround", c.instrumentDetectionWorkaround,
                v -> c.instrumentDetectionWorkaround = v);
        y += rowH;
        addToggle(leftX, y, "lyricsChatOutput", c.lyricsChatOutput, v -> c.lyricsChatOutput = v);
        y += rowH;
        addToggle(leftX, y, "lyricsOutputToPublic", c.lyricsOutputToPublic,
                v -> c.lyricsOutputToPublic = v);
        y += rowH;
        addToggle(leftX, y, "lyricsUseSelector", c.lyricsUseSelector, v -> c.lyricsUseSelector = v);
        y += rowH;

        addEnumButton(leftX, y, colW, "expectedServerVersion", c.expectedServerVersion,
                Config.ExpectedServerVersion.values(), v -> c.expectedServerVersion = v);
        y += rowH;
        addEnumButton(leftX, y, colW, "tuningSpeed", c.tuningSpeed,
                Config.TuningSpeed.values(), v -> c.tuningSpeed = v);
        y += rowH;
        addEnumButton(leftX, y, colW, "playbackPacketRatelimit", c.playbackPacketRatelimit,
                Config.PlaybackPacketRatelimit.values(), v -> c.playbackPacketRatelimit = v);
        y += rowH;
        addEnumButton(leftX, y, colW, "repeatMode", c.repeatMode,
                Config.RepeatMode.values(), v -> c.repeatMode = v);

        // ---------- 右列：数值 ----------
        int ry = 40;
        addStepButton(rightX, ry, colW, "delayPlaybackStartBySecs",
                c.delayPlaybackStartBySecs, 0.5f, 0.0f, 10.0f, v -> c.delayPlaybackStartBySecs = v);
        ry += rowH;
        addLongStepButton(rightX, ry, colW, "lyricsMinIntervalMs",
                c.lyricsMinIntervalMs, 50L, 0L, 5000L, v -> c.lyricsMinIntervalMs = v);
        ry += rowH;
        addIntStepButton(rightX, ry, colW, "lyricsDmBurst",
                c.lyricsDmBurst, 1, 1, 50, v -> c.lyricsDmBurst = v);
        ry += rowH;
        addIntStepButton(rightX, ry, colW, "lyricsDmMaxTargets",
                c.lyricsDmMaxTargets, 1, 1, 100, v -> c.lyricsDmMaxTargets = v);
        ry += rowH;
        addIntStepButton(rightX, ry, colW, "lyricsDmRadius",
                c.lyricsDmRadius, 5, 1, 200, v -> c.lyricsDmRadius = v);
        ry += rowH;
        addStringCycleButton(rightX, ry, colW, "lyricsCommand", c.lyricsCommand,
                new String[] { "msg", "tell", "w", "whisper" }, v -> c.lyricsCommand = v);

        // ---------- 底部：完成 ----------
        int doneW = 200;
        int doneX = (this.width - doneW) / 2;
        int doneY = Math.min(this.height - 30, 40 + 12 * rowH + 10);
        addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                b -> onClose())
                .bounds(doneX, doneY, doneW, 20)
                .build());
    }

    // ==================== 控件工厂 ====================

    private void addToggle(int x, int y, String name, boolean value, java.util.function.Consumer<Boolean> setter) {
        addRenderableWidget(Button.builder(
                label(name, String.valueOf(value)),
                b -> {
                    setter.accept(!value);
                    Config.save();
                    init();
                })
                .bounds(x, y, Math.max(150, (this.width - 60) / 2), 20)
                .build());
    }

    private <T> void addEnumButton(int x, int y, int w, String name, T value, T[] values,
                                   java.util.function.Consumer<T> setter) {
        addRenderableWidget(Button.builder(
                label(name, value == null ? "null" : value.toString()),
                b -> {
                    int idx = 0;
                    for (int i = 0; i < values.length; i++) if (values[i] == value) idx = i;
                    setter.accept(values[(idx + 1) % values.length]);
                    Config.save();
                    init();
                })
                .bounds(x, y, w, 20)
                .build());
    }

    private void addStepButton(int x, int y, int w, String name, float value,
                               float step, float min, float max,
                               java.util.function.Consumer<Float> setter) {
        addRenderableWidget(Button.builder(
                label(name, String.format(java.util.Locale.ROOT, "%.2f", value)),
                b -> {
                    float v = value + step;
                    if (v > max) v = min;
                    setter.accept(v);
                    Config.save();
                    init();
                })
                .bounds(x, y, w, 20)
                .build());
    }

    private void addIntStepButton(int x, int y, int w, String name, int value,
                                  int step, int min, int max,
                                  java.util.function.Consumer<Integer> setter) {
        addRenderableWidget(Button.builder(
                label(name, String.valueOf(value)),
                b -> {
                    int v = value + step;
                    if (v > max) v = min;
                    setter.accept(v);
                    Config.save();
                    init();
                })
                .bounds(x, y, w, 20)
                .build());
    }

    private void addLongStepButton(int x, int y, int w, String name, long value,
                                   long step, long min, long max,
                                   java.util.function.Consumer<Long> setter) {
        addRenderableWidget(Button.builder(
                label(name, String.valueOf(value)),
                b -> {
                    long v = value + step;
                    if (v > max) v = min;
                    setter.accept(v);
                    Config.save();
                    init();
                })
                .bounds(x, y, w, 20)
                .build());
    }

    private void addStringCycleButton(int x, int y, int w, String name, String value,
                                      String[] options, java.util.function.Consumer<String> setter) {
        addRenderableWidget(Button.builder(
                label(name, value),
                b -> {
                    int idx = 0;
                    for (int i = 0; i < options.length; i++)
                        if (options[i].equals(value)) idx = i;
                    setter.accept(options[(idx + 1) % options.length]);
                    Config.save();
                    init();
                })
                .bounds(x, y, w, 20)
                .build());
    }

    private static Component label(String name, String value) {
        return Component.literal(name + ": " + value);
    }


    // ==================== 关闭 ====================

    @Override
    public void onClose() {
        Config.save();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.setScreenAndShow(parent);
        else super.onClose();
    }
}
