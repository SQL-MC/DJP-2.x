package semmiedev.disc_jockey.gui.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Song;

public class SongDetailScreen extends Screen {
    private final Screen parent;
    private final Song song;

    private static final int PANEL_W      = 340;
    private static final int PANEL_H      = 196;
    private static final int PANEL_COLOR  = 0xF0101010;
    private static final int ACCENT_COLOR = 0xFF55FFAA;

    private static final int TITLE_COLOR  = 0xFFFFFFFF;
    private static final int LABEL_COLOR  = 0xFFAAAAAA;
    private static final int VALUE_COLOR  = 0xFFDDDDDD;
    private static final int LABEL_W      = 72;

    private String titleText = "", authorText = "", originalText = "";
    private String descText = "", lengthText = "", notesText = "";

    public SongDetailScreen(Song song) { this(null, song); }
    public SongDetailScreen(Screen parent, Song song) {
        super(Component.literal("Song Details"));
        this.parent = parent;
        this.song = song;
    }

    @Override
    protected void init() {
        String dn = (song.displayName != null) ? song.displayName : "(unknown)";
        titleText = "♪ " + dn;
        authorText   = (song.author != null) ? song.author : "Unknown";
        originalText = (song.originalAuthor != null) ? song.originalAuthor : "Unknown";
        String d = (song.description != null) ? song.description : "None";
        if (d.length() > 58) d = d.substring(0, 58) + "...";
        descText = d;
        double sec = 0d;
        try { sec = song.getLengthInSeconds(); } catch (Throwable ignored) {}
        lengthText = String.format("%.1f s", sec);
        notesText = String.valueOf((song.notes != null) ? song.notes.length : 0);

        int px = (width - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> goBack())
                .bounds(px + (PANEL_W - 100) / 2, py + PANEL_H - 32, 100, 20).build());
    }

    private void goBack() {
        try { Main.setScreenCompatStatic(minecraft, parent); return; }
        catch (Throwable ignored) {}
        try { minecraft.gui.setScreen(parent); } catch (Throwable ignored) {}
    }

    /** ★ 26.4 根因：覆写 extractBackground 且不调 super，背景不再被压暗 */
    @Override
    public void extractBackground(GuiGraphicsExtractor context) {
        // 不调 super → 不铺模糊暗层
    }

    /** ★ 26.4 签名是 1 参 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor context) {
        int px = (width - PANEL_W) / 2;
        int py = (height - PANEL_H) / 2;

        context.fill(px, py, px + PANEL_W, py + PANEL_H, PANEL_COLOR);
        context.fill(px, py, px + PANEL_W, py + 2, ACCENT_COLOR);
        context.fill(px, py, px + 2, py + PANEL_H, ACCENT_COLOR);
        context.fill(px + PANEL_W - 2, py, px + PANEL_W, py + PANEL_H, ACCENT_COLOR);
        context.fill(px, py + PANEL_H - 2, px + PANEL_W, py + PANEL_H, ACCENT_COLOR);

        int tx = px + 14, vx = tx + LABEL_W;
        context.text(font, Component.literal(titleText), tx, py + 14, TITLE_COLOR, false);
        row(context, "Author: ",   authorText,   tx, vx, py + 46);
        row(context, "Original: ", originalText, tx, vx, py + 64);
        row(context, "Desc: ",     descText,     tx, vx, py + 82);
        row(context, "Length: ",   lengthText,   tx, vx, py + 100);
        row(context, "Notes: ",    notesText,    tx, vx, py + 118);

        super.extractRenderState(context);
    }

    private void row(GuiGraphicsExtractor context, String label, String value, int lx, int vx, int y) {
        context.text(font, Component.literal(label), lx, y, LABEL_COLOR, false);
        context.text(font, Component.literal(value), vx, y, VALUE_COLOR, false);
    }
}