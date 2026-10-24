package semmiedev.disc_jockey.gui.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Song;

import java.lang.reflect.Method;

public class SongDetailScreen extends Screen {
    private final Screen parent;
    private final Song song;

    // ---------------- 面板 ----------------
    private static final int PANEL_W      = 340;
    private static final int PANEL_H      = 196;
    /** 面板底色：需要更亮可改 0xE81A1A1E / 0xD0252528 */
    private static final int PANEL_COLOR  = 0xF0101010;
    private static final int ACCENT_COLOR = 0xFF55FFAA;   // 边框（与 DJ 界面歌曲卡片同色系）

    // ---------------- 文字 ----------------
    private static final int TITLE_COLOR  = 0xFFFFFFFF;
    private static final int LABEL_COLOR  = 0xFFAAAAAA;
    private static final int VALUE_COLOR  = 0xFFDDDDDD;
    private static final int LABEL_W      = 72;

    /** 背景压暗：保持 0（不压暗）。暗化已由「不调 super.extractBackground」根治 */
    private static final int OVERLAY_COLOR = 0x00000000;

    private String titleText    = "";
    private String authorText   = "";
    private String originalText = "";
    private String descText     = "";
    private String lengthText   = "";
    private String notesText    = "";

    public SongDetailScreen(Song song) {
        this(null, song);
    }

    public SongDetailScreen(Screen parent, Song song) {
        super(Component.literal("Song Details"));
        this.parent = parent;
        this.song = song;
    }

    /**
     * ★★★ 这是「还是黑的」的真正根因 ★★★
     *
     * Screen 父类的 extractBackground 默认实现会铺一层模糊 + 暗色覆盖，
     * 那就是截图里「昏暗的自然景观」。SongDetailScreen 之前没有覆写它，
     * 所以每次都被父类压暗 —— 这也是为什么 OVERLAY_COLOR 设成 0、
     * Button 黑底也去掉之后，界面依然暗：暗的源头根本不在我们写的代码里。
     *
     * 这里覆写后【不调用 super】，背景保持游戏世界原样。
     * （DiscJockeyScreen 之所以看起来正常，就是因为它覆写了这个方法。）
     *
     * 如果想保留一点点压暗，把下面那行取消注释即可。
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // context.fill(0, 0, width, height, 0x66000000);   // 需要压暗就打开这行
    }

    @Override
    protected void init() {
        String displayName = (song.displayName != null) ? song.displayName : "(unknown)";
        titleText = "♪ " + displayName;
        authorText = (song.author != null) ? song.author : "Unknown";
        originalText = (song.originalAuthor != null) ? song.originalAuthor : "Unknown";

        String d = (song.description != null) ? song.description : "None";
        if (d.length() > 58) d = d.substring(0, 58) + "...";
        descText = d;

        double sec = 0d;
        try {
            sec = song.getLengthInSeconds();
        } catch (Throwable ignored) {}
        lengthText = String.format("%.1f s", sec);
        notesText = String.valueOf((song.notes != null) ? song.notes.length : 0);

        int px = (width - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> goBack())
                .bounds(px + (PANEL_W - 100) / 2, py + PANEL_H - 32, 100, 20)
                .build());
    }

    /** 全反射派发：不编译期绑定 Main / Minecraft 的任何具体方法名 */
    private void goBack() {
        String[] preferred = {
                "setScreenAndShow", "openScreen", "setScreen", "showScreen",
                "displayScreen", "setGui", "openGui", "setCurrentScreen", "pushScreen"
        };
        for (String want : preferred) {
            for (Method m : minecraft.getClass().getMethods()) {
                if (!m.getName().equals(want)) continue;
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isAssignableFrom(Screen.class)) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(minecraft, parent);
                    return;
                } catch (Throwable ignored) {}
            }
        }
        try {
            Class<?> mainCls = Class.forName("semmiedev.disc_jockey.Main");
            Method m = mainCls.getMethod("setScreenCompatStatic", Minecraft.class, Screen.class);
            m.invoke(null, minecraft, parent);
            return;
        } catch (Throwable ignored) {}
        try {
            Object gui = minecraft.getClass().getMethod("getGui").invoke(minecraft);
            if (gui == null) {
                java.lang.reflect.Field f = minecraft.getClass().getDeclaredField("gui");
                f.setAccessible(true);
                gui = f.get(minecraft);
            }
            if (gui != null) {
                Method m = gui.getClass().getMethod("setScreen", Screen.class);
                m.invoke(gui, parent);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 信息项全部手绘文字（4 参，与 NeoForge 26.3 的 DiscJockeyScreen 一致），
     * 不用 Button —— 原生 Button 自带半透明黑底，六个叠一起整屏发黑且改不掉。
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, OVERLAY_COLOR);

        int px = (width - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;

        context.fill(px, py, px + PANEL_W, py + PANEL_H, PANEL_COLOR);
        context.fill(px, py, px + PANEL_W, py + 2, ACCENT_COLOR);
        context.fill(px, py, px + 2, py + PANEL_H, ACCENT_COLOR);
        context.fill(px + PANEL_W - 2, py, px + PANEL_W, py + PANEL_H, ACCENT_COLOR);
        context.fill(px, py + PANEL_H - 2, px + PANEL_W, py + PANEL_H, ACCENT_COLOR);

        int tx = px + 14;
        int vx = tx + LABEL_W;
        context.text(font, Component.literal(titleText), tx, py + 14, TITLE_COLOR);
        row(context, "Author: ",   authorText,   tx, vx, py + 46);
        row(context, "Original: ", originalText, tx, vx, py + 64);
        row(context, "Desc: ",     descText,     tx, vx, py + 82);
        row(context, "Length: ",   lengthText,   tx, vx, py + 100);
        row(context, "Notes: ",    notesText,    tx, vx, py + 118);

        super.extractRenderState(context, mouseX, mouseY, delta);   // Back 按钮
    }

    private void row(GuiGraphicsExtractor context, String label, String value,
                     int lx, int vx, int y) {
        context.text(font, Component.literal(label), lx, y, LABEL_COLOR);
        context.text(font, Component.literal(value), vx, y, VALUE_COLOR);
    }
}