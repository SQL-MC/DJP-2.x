package semmiedev.disc_jockey.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.swing.RowFilter.Entry;

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

        // ---- 26.4 兼容：RenderPipelines.GUI_TEXTURED 已被移除，改为运行时反射解析 ----
        private static Object  cachedPipeline        = null;
        private static boolean cachedPipelineDone    = false;

        /** 依次试探若干候选类名 × 字段名；都找不到就按类型名含 Pipeline、字段名含 GUI 兜底扫一遍 */
        private static Object resolveGuiPipeline() {
            if (cachedPipelineDone) return cachedPipeline;
            cachedPipelineDone = true;

            String[] classNames = {
                    "net.minecraft.client.gui.GuiPipeline",
                    "net.minecraft.client.renderer.RenderPipelines",
                    "net.minecraft.client.renderer.pipeline.RenderPipelines",
                    "com.mojang.renderpearl.api.pipeline.RenderPipelines",
                    "com.mojang.blaze3d.pipeline.RenderPipelines"
            };
            String[] fieldNames = {
                    "GUI_TEXTURED", "GUI_TEXTURED_PIPELINE", "GUI",
                    "GUI_TEXTURE", "GUI_TEXTURED_PIPELINE", "TEXTURED"
            };

            for (String cn : classNames) {
                Class<?> c;
                try { c = Class.forName(cn); } catch (Throwable ignored) { continue; }

                for (String fn : fieldNames) {
                    try {
                        java.lang.reflect.Field f = c.getField(fn);
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (v != null) { cachedPipeline = v; return v; }
                    } catch (Throwable ignored) {}
                }

                // 兜底：按「类型名含 Pipeline + 字段名含 GUI」扫静态字段
                try {
                    for (java.lang.reflect.Field f : c.getFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        String tn = f.getType().getName();
                        String n  = f.getName().toUpperCase(java.util.Locale.ROOT);
                        if (!tn.contains("Pipeline")) continue;
                        if (!n.contains("GUI")) continue;
                        try {
                            f.setAccessible(true);
                            Object v = f.get(null);
                            if (v != null) { cachedPipeline = v; return v; }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
            return null;
        }

        /** 形参类型与实参是否兼容（含基本类型装箱比对） */
        private static boolean paramFits(Class<?> p, Object a) {
            if (a == null) return !p.isPrimitive();
            if (p.isPrimitive()) {
                if (p == int.class)     return a instanceof Integer;
                if (p == float.class)   return a instanceof Float;
                if (p == long.class)    return a instanceof Long;
                if (p == double.class)  return a instanceof Double;
                if (p == boolean.class) return a instanceof Boolean;
                if (p == short.class)   return a instanceof Short;
                if (p == byte.class)    return a instanceof Byte;
                if (p == char.class)    return a instanceof Character;
                return false;
            }
            return p.isInstance(a);
        }

        /** 反射调用 blit：按方法名 + 形参个数 + 类型兼容匹配，失败返回 false（不抛异常） */
        private static boolean tryBlit(Object ctx, Object[] args) {
            if (ctx == null) return false;
            for (java.lang.reflect.Method m : ctx.getClass().getMethods()) {
                if (!"blit".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != args.length) continue;
                boolean ok = true;
                for (int i = 0; i < ps.length; i++) {
                    if (!paramFits(ps[i], args[i])) { ok = false; break; }
                }
                if (!ok) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(ctx, args);
                    return true;
                } catch (Throwable ignored) {}
            }
            return false;
        }

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

        // 26.4 snapshot-5 起，Entry 的抽象渲染回调改为 2 参数：extractContent(context, hovered)。
        // 只保留 5 参数版本会导致 AbstractMethodError（子类未实现父类抽象方法）。
        // 这里两个签名都定义，且都不加 @Override —— 这样无论父类抽象的是哪一个都能实现，
        // 也不会因为某一版不存在该签名而编译失败。两者都转调 drawEntry()。

        /** 26.4 snapshot-5+ 签名 */
        public void extractContent(GuiGraphicsExtractor context, boolean hovered) {
            double mx = 0, my = 0;
            try {
                mx = client.mouseHandler.xpos();
                my = client.mouseHandler.ypos();
            } catch (Throwable ignored) {}
            drawEntry(context, mx, my, hovered, 0f);
        }

        /** 旧签名（26.4 snapshot-4 及更早） */
        public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
            drawEntry(context, mouseX, mouseY, hovered, deltaTicks);
        }

        private void drawEntry(GuiGraphicsExtractor context, double mouseX, double mouseY, boolean hovered, float deltaTicks) {
            int x = this.getX();
            int y = this.getY();
            int entryWidth = this.getWidth();
            int entryHeight = this.getHeight();

            if (selected) {
                // ⚠ fill() 的颜色是 ARGB，必须写满 8 位。
                // 原来写 0xFFFFFF / 0x000000 只有 6 位 → alpha=00 → 完全透明，选中高亮看不见。
                context.fill(x, y, x + entryWidth, y + entryHeight, 0xFFFFFFFF);
                context.fill(x + 1, y + 1, x + entryWidth - 1, y + entryHeight - 1, 0xFF000000);
            }

            
            
            context.text(client.font, safeName(song), x + entryWidth / 2, y + 5,
                    selected ? 0xFFFFFFFF : 0xFF808080);

            
            int iconX = x + 2;
            int iconY = y + 2;
            int frame = (favorite ? 26 : 0) + (isOverFavoriteButton(mouseX, mouseY) ? 13 : 0); // 0/13/26/39

            // 26.4 移除了 RenderPipelines.GUI_TEXTURED，编译期直接引用会 NoSuchFieldError。
            // 这里管线对象与 blit 调用全部走反射：先试「带管线」的 10 参版本，
            // 失败再试「不带管线」的 9 参版本；都失败就跳过图标，绝不抛异常。
            Object pipe = resolveGuiPipeline();
            if (pipe != null && tryBlit(context, new Object[]{
                    pipe, ICONS, iconX, iconY, (float) frame, 0f, 13, 12, 52, 12})) {
                // 已绘制
            } else {
                tryBlit(context, new Object[]{
                        ICONS, iconX, iconY, (float) frame, 0f, 13, 12, 52, 12});
            }
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
