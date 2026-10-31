package semmiedev.disc_jockey.util;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 音色图标：走 MC 方块图集（blocks.png-atlas）的 sprite 绘制。
 * 图集启动后已上传就绪，不存在"纹理未加载→画出棋盘"的问题。
 * 全程反射，任一环节拿不到就返回 false，调用方保持纯色。
 */
public final class DjSpriteIcons {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String[] SPRITE = {
            "note_block", "oak_planks", "stone", "sand",
            "glass", "white_wool", "clay", "gold_block",
            "packed_ice", "bone_block_top", "iron_block", "soul_sand",
            "pumpkin_side", "emerald_block", "hay_block_side", "glowstone"
    };

    private static boolean tried, ready, drawnLogged, failLogged;
    private static Object atlas, missingSprite, pipe;
    private static Method mGetSprite, mBlitSprite, mMissingSprite;
    private static int idMode = -1;
    private static int pathMode = 0;

    public static boolean draw(Object gfx, int instrument, int x, int y, int w, int h) {
        if (gfx == null || instrument < 0 || instrument >= SPRITE.length) return false;
        if (!ensure(gfx)) return false;
        try {
            Object id = makeId(SPRITE[instrument]);
            if (id == null) return false;
            Object sprite = mGetSprite.invoke(atlas, id);
            if (sprite == null) return false;
            if (missingSprite != null && sprite == missingSprite) return false;
            mBlitSprite.invoke(gfx, pipe, sprite, x, y, w, h);
            if (!drawnLogged) {
                drawnLogged = true;
                LOGGER.info("[Editor] ✓ 图集 sprite 音色图标已绘制：{}", SPRITE[instrument]);
            }
            return true;
        } catch (Throwable t) {
            if (!failLogged) { failLogged = true; LOGGER.warn("[Editor] sprite 图标绘制失败：{}", t); }
            return false;
        }
    }

    private static boolean ensure(Object gfx) {
        if (tried) return ready;
        tried = true;
        try {
            Minecraft mc = Minecraft.getInstance();

            Object am = null;
            for (Method m : mc.getClass().getMethods()) {
                if (m.getName().equals("getAtlasManager") && m.getParameterCount() == 0) { am = m.invoke(mc); break; }
            }
            if (am == null) { fail("getAtlasManager 未找到"); return false; }

            Object blocksId = Class.forName("net.minecraft.client.renderer.texture.TextureAtlas")
                    .getField("LOCATION_BLOCKS").get(null);

            Object at = null;
            for (Method m : am.getClass().getMethods()) {
                if (m.getName().equals("getAtlasOrThrow") && m.getParameterCount() == 1) { at = m.invoke(am, blocksId); break; }
            }
            if (at == null) {
                for (Method m : am.getClass().getMethods()) {
                    if (m.getName().equals("getAtlas") && m.getParameterCount() == 1) { at = m.invoke(am, blocksId); break; }
                }
            }
            if (at == null) { fail("拿不到方块图集"); return false; }
            atlas = at;

            for (Method m : at.getClass().getMethods()) {
                if (mGetSprite == null && m.getName().equals("getSprite") && m.getParameterCount() == 1) mGetSprite = m;
                if (mMissingSprite == null && m.getName().equals("missingSprite") && m.getParameterCount() == 0) mMissingSprite = m;
            }
            if (mGetSprite == null) { fail("TextureAtlas.getSprite 未找到"); return false; }
            if (mMissingSprite != null) missingSprite = mMissingSprite.invoke(at);

            pipe = findPipe();
            if (pipe == null) { fail("找不到纹理管线常量"); return false; }

            Class<?> spriteCls = Class.forName("net.minecraft.client.renderer.texture.TextureAtlasSprite");
            for (Method m : gfx.getClass().getMethods()) {
                if (!m.getName().equals("blitSprite")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 6) continue;
                if (p[1] != spriteCls) continue;
                if (p[2] != int.class || p[3] != int.class || p[4] != int.class || p[5] != int.class) continue;
                if (!p[0].isInstance(pipe)) continue;
                mBlitSprite = m;
                break;
            }
            if (mBlitSprite == null) { fail("找不到 blitSprite(管线,sprite,x,y,w,h)"); return false; }

            ready = probePath();
            if (ready) {
                LOGGER.info("[Editor] ✓ 图集 sprite 路由就绪：管线={}，前缀={}",
                        pipe.getClass().getSimpleName(), (pathMode == 0 ? "block/" : "无"));
            }
            return ready;
        } catch (Throwable t) {
            fail(String.valueOf(t));
            return false;
        }
    }

    private static boolean probePath() {
        for (int pm = 0; pm <= 1; pm++) {
            pathMode = pm;
            idMode = -1;
            try {
                Object id = makeId(SPRITE[0]);
                if (id == null) continue;
                Object sp = mGetSprite.invoke(atlas, id);
                if (sp == null) continue;
                if (missingSprite != null && sp == missingSprite) continue;
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static Object makeId(String name) {
        String path = (pathMode == 1) ? name : ("block/" + name);
        if (idMode < 0) {
            for (int mode = 0; mode < 3; mode++) {
                try {
                    Object o = tryId(mode, path);
                    if (o != null) { idMode = mode; return o; }
                } catch (Throwable ignored) {}
            }
            return null;
        }
        try { return tryId(idMode, path); } catch (Throwable t) { return null; }
    }

    private static Object tryId(int mode, String path) throws Exception {
        switch (mode) {
            case 0:  return Identifier.class.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, "minecraft", path);
            case 1:  return Identifier.class.getMethod("parse", String.class).invoke(null, "minecraft:" + path);
            default: return Identifier.class.getMethod("withDefaultNamespace", String.class).invoke(null, "minecraft:" + path);
        }
    }

    private static Object findPipe() {
        String[][] c = {
                {"net.minecraft.client.gui.GuiPipeline", "TEXTURED"},
                {"net.minecraft.client.gui.GuiPipeline", "TEXTURED_PREMULTIPLIED_ALPHA"},
                {"net.minecraft.client.renderer.RenderPipelines", "GUI_TEXTURED"},
                {"net.minecraft.client.renderer.RenderPipelines", "GUI_TEXTURED_PREMULTIPLIED_ALPHA"}
        };
        for (String[] k : c) {
            try {
                Object v = Class.forName(k[0]).getField(k[1]).get(null);
                if (v != null) return v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void fail(String why) {
        if (!failLogged) { failLogged = true; LOGGER.warn("[Editor] 图集 sprite 路线不可用（退回纯色）：{}", why); }
    }
}
//https://b23.tv/BV1AUFzeCE68