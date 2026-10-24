package semmiedev.disc_jockey.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.Nullable;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Song;
import semmiedev.disc_jockey.Util;
import semmiedev.disc_jockey.mixin.EntryListWidgetAccessor;

public class SongListWidget extends AbstractSelectionList<SongListWidget.SongEntry> {

    public SongListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
        super(client, width, height, top, itemHeight);
    }

    public void safeClearEntries() {
        this.clearEntries();
    }

    public void safeReplaceEntries(java.util.Collection<SongListWidget.SongEntry> entries) {
        this.replaceEntries(entries);
    }

    @SuppressWarnings("unchecked")
    public java.util.List<SongListWidget.SongEntry> getModifiableChildren() {
        return (java.util.List<SongListWidget.SongEntry>) ((EntryListWidgetAccessor) this).getChildrenList();
    }

    public int getItemHeight() {
        return this.defaultEntryHeight;
    }

    @Override
    public int getRowWidth() {
        return width - 40;
    }

    @Override
    protected int scrollBarX() {
        return getX() + width - 12;
    }

    @Override
    public void setSelected(@Nullable SongListWidget.SongEntry entry) {
        SongListWidget.SongEntry selectedEntry = getSelected();
        if (selectedEntry != null) selectedEntry.selected = false;
        if (entry != null) entry.selected = true;
        super.setSelected(entry);
    }

    
    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        SongEntry selected = this.getSelected();
        if (selected != null && selected.song != null) {
            
            output.add(NarratedElementType.TITLE, Component.literal(safeName(selected.song)));
        } else {
            output.add(NarratedElementType.TITLE, Component.translatable("disc_jockey.screen.select_song"));
        }
    }

    
    public static String safeName(Song s) {
        if (s == null) return "Untitled";
        String n = safePart(s.name);
        String f = safePart(s.fileName);
        String base = (f != null) ? stripExt(f) : null;
        if (n != null && !n.isEmpty()) {
            if (base != null && !base.isEmpty()) return n + " (" + base + ")";
            return n;
        }
        return (base != null && !base.isEmpty()) ? base : "Untitled";
    }

    
    private static String safePart(String t) {
        if (t == null) return null;
        String trimmed = t.trim();
        if (trimmed.isEmpty()) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != '_') return trimmed;
        }
        return null; // 全是 '_'
    }

    private static String stripExt(String name) {
        return (name == null) ? "" : name.replaceAll("\\.(?i)(nbs|mid)$", "");
    }

    public static class SongEntry extends Entry<SongListWidget.SongEntry> {
        private static final Identifier ICONS = Identifier.fromNamespaceAndPath(Main.MOD_ID, "textures/gui/icons.png");

        public final int index;
        public final Song song;

        public boolean selected, favorite;
        public SongListWidget songListWidget;
        private long lastClickedAt = Util.TIMESTAMP_UNINITIALIZED;

        private final Minecraft client = Minecraft.getInstance();

        public SongEntry(Song song, int index) {
            this.song = song;
            this.index = index;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
            int x = this.getX();
            int y = this.getY();
            int entryWidth = this.getWidth();
            int entryHeight = this.getHeight();

            if (selected) {
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFFFFFF);
                context.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0x000000);
            }

            
            
            context.text(client.font, safeName(song), x + entryWidth / 2, y + 5,
                    selected ? 0xFFFFFFFF : 0xFF808080);

            
            int iconX = x + 2;
            int iconY = y + 2;
            int frame = (favorite ? 26 : 0) + (isOverFavoriteButton(mouseX, mouseY) ? 13 : 0); // 0/13/26/39
            context.blit(RenderPipelines.GUI_TEXTURED,
                    ICONS, iconX, iconY,
                    (float) frame, 0f,   // ✅ u/v 为 float（像素坐标转 float）
                    13, 12,              
                    52, 12);             
        }

        public Component getNarrateText() {
            
            return Component.literal(safeName(song));
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            double mouseX = click.x();
            double mouseY = click.y();
            int button = click.button();

            if (isOverFavoriteButton(mouseX, mouseY)) {
                favorite = !favorite;
                if (favorite) {
                    Main.config.favorites.add(song.fileName);
                } else {
                    Main.config.favorites.remove(song.fileName);
                }
                return true;
            }

            if (songListWidget.getSelected() == this && lastClickedAt != -1L && Util.now() - lastClickedAt <= 350) {
                Main.SONG_PLAYER.start(this.song);
            } else {
                songListWidget.setSelected(this);
                lastClickedAt = Util.now();
            }
            return true;
        }

        private boolean isOverFavoriteButton(double mouseX, double mouseY) {
            int x = this.getX();
            int y = this.getY();
            return mouseX > x + 2 && mouseX < x + 15 && mouseY > y + 2 && mouseY < y + 14;
        }
    }
}