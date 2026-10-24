package semmiedev.disc_jockey.gui.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Song;

import java.lang.reflect.Method;

public class SongDetailScreen extends Screen {
    private final Screen parent;
    private final Song song;

    /**
     * 背景压暗程度。
     *   0x00000000 = 完全不压暗（当前值）
     *   0x66000000 = 40% ｜ 0x99000000 = 60% ｜ 0xCC000000 = 80%（原值）
     *
     * ★ 为什么默认改成不压暗：MC 原生 Button 本身就带半透明黑底，
     *   再叠一层全屏背景遮罩会让整个界面糊成一片黑（截图里那种效果）。
     *   深色对比交给下面的面板（PANEL_COLOR）就够了，
     *   背景保留游戏世界原样。想压暗背景就改这个值。
     */
    private static final int OVERLAY_COLOR = 0x00000000;
    /** 面板底色（衬托按钮，避免文字悬空在纯背景上） */
    private static final int PANEL_COLOR   = 0xF0101010;
    /** 边框高亮（与 DJ 界面歌曲卡片的 0xFF00FFAA 同色系） */
    private static final int ACCENT_COLOR  = 0xFF55FFAA;

    /** 兼容旧调用点（右键入口 / 26.4 分支走单参） */
    public SongDetailScreen(Song song) {
        this(null, song);
    }

    public SongDetailScreen(Screen parent, Song song) {
        super(Component.literal("Song Details"));
        this.parent = parent;
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

        int noteCount = (song.notes != null) ? song.notes.length : 0;
        double seconds;
        try {
            seconds = song.getLengthInSeconds();
        } catch (Throwable t) {
            seconds = 0d;
        }

        addRenderableWidget(Button.builder(Component.literal("♪ " + displayName), b -> {})
                .bounds(centerX - 150, startY, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Author: " + author), b -> {})
                .bounds(centerX - 150, startY + 25, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Original: " + originalAuthor), b -> {})
                .bounds(centerX - 150, startY + 50, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Desc: " + desc), b -> {})
                .bounds(centerX - 150, startY + 75, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Length: " + String.format("%.1f s", seconds)), b -> {})
                .bounds(centerX - 150, startY + 100, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Notes: " + noteCount), b -> {})
                .bounds(centerX - 150, startY + 125, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Back"), b -> goBack()
        ).bounds(centerX - 50, startY + 160, 100, 20).build());
    }

    /**
     * Fabric / NeoForge 通用：全反射，某个方法在不在都不影响编译。
     * 优先退回父界面（parent），没有父界面则退到 null（关掉界面）。
     */
    private void goBack() {
        try {
            Method m = minecraft.getClass().getMethod("setScreenAndShow", Screen.class);
            m.invoke(minecraft, parent);
            return;
        } catch (Throwable ignored) {}
        try {
            Method m = Main.class.getMethod("setScreenCompatStatic", Minecraft.class, Screen.class);
            m.invoke(null, minecraft, parent);
            return;
        } catch (Throwable ignored) {}
        try {
            Method m = minecraft.getClass().getMethod("setScreen", Screen.class);
            m.invoke(minecraft, parent);
            return;
        } catch (Throwable ignored) {}
        try {
            minecraft.gui.setScreen(parent);
        } catch (Throwable ignored) {}
    }

    /**
     * ★ 26.3 真实签名是 4 参（javap 实测），1 参是 26.4-s5 的 —— 用错会「不覆写」编译失败。
     *
     * ★ 顺序修正：遮罩必须画在 super【之前】。
     *   原写法是 super（画按钮）→ fill（盖 80% 黑），等于把遮罩糊在按钮上，
     *   这就是「界面很暗、按钮发灰」的根因。现在遮罩打底、按钮浮在上面。
     */
    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // ① 背景压暗（打底）
        context.fill(0, 0, width, height, OVERLAY_COLOR);

        // ② 居中面板：衬托按钮，尺寸按 init() 的实际布局（startY-10 .. startY+190）
        int panelW = 320;
        int panelH = 200;
        int px = (width - panelW) / 2;
        int py = (height - panelH) / 2;
        context.fill(px, py, px + panelW, py + panelH, PANEL_COLOR);
        context.fill(px, py, px + panelW, py + 2, ACCENT_COLOR);                       // 顶边
        context.fill(px, py, px + 2, py + panelH, ACCENT_COLOR);                       // 左边
        context.fill(px + panelW - 2, py, px + panelW, py + panelH, ACCENT_COLOR);      // 右边
        context.fill(px, py + panelH - 2, px + panelW, py + panelH, ACCENT_COLOR);      // 底边

        // ③ 最后画按钮（遮罩与面板都在它下面，不会被压暗）
        super.extractRenderState(context, mouseX, mouseY, delta);
    }
}
