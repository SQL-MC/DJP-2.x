package semmiedev.disc_jockey.util;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;


public final class BlitCompat {

    private static final Logger LOGGER = LogManager.getLogger("Disc Jockey/BlitCompat");

    
    private static final String[] PIPELINE_FIELD_CANDIDATES = {
            "GUI_TEXTURED",
            "GUI",
            "GUI_OPAQUE_TEXTURED_BACKGROUND",
            "GUI_OPAQUE_TEX_BG",
            "GUI_TEXTURED_OVERLAY",
            "GUI_TEXTURED_PREMULTIPLIED_ALPHA",
            "GUI_TEXT_HIGHLIGHT",
    };

    private static final Object PIPELINE;
    private static final String RESOLVED_NAME;

    private static final Class<?> PIPELINE_TYPE;

    static {
        Class<?> pt = null;
        try {
            
            pt = Class.forName("com.mojang.renderpearl.api.pipeline.RenderPipeline");
        } catch (Throwable t) {
            LOGGER.warn("[BlitCompat] 找不到 RenderPipeline 类：{}", t.toString());
        }
        PIPELINE_TYPE = pt;

        Object pipe = null;
        String name = "<none>";

        Class<?> rpClass = null;
        try {
            rpClass = Class.forName("net.minecraft.client.renderer.RenderPipelines");
        } catch (Throwable t) {
            LOGGER.warn("[BlitCompat] 找不到 RenderPipelines 类：{}", t.toString());
        }

        if (rpClass != null) {
            
            for (String cand : PIPELINE_FIELD_CANDIDATES) {
                try {
                    java.lang.reflect.Field f = rpClass.getDeclaredField(cand);
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null && (PIPELINE_TYPE == null || PIPELINE_TYPE.isInstance(v))) {
                        pipe = v;
                        name = cand;
                        break;
                    }
                } catch (NoSuchFieldException ignored) {
                    
                } catch (Throwable t) {
                    LOGGER.warn("[BlitCompat] 读取字段 {} 失败：{}", cand, t.toString());
                }
            }

            
            if (pipe == null) {
                for (java.lang.reflect.Field f : rpClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    if (PIPELINE_TYPE != null && !PIPELINE_TYPE.equals(f.getType())) continue;
                    if (!f.getName().toUpperCase().contains("GUI")) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (v != null) {
                            pipe = v;
                            name = f.getName();
                            break;
                        }
                    } catch (Throwable ignored) {
                        
                    }
                }
            }
        }

        PIPELINE = pipe;
        RESOLVED_NAME = name;
        if (pipe != null) {
            LOGGER.info("[BlitCompat] ✓ 已解析 GUI 渲染管线：RenderPipelines.{}", name);
        } else {
            LOGGER.error("[BlitCompat] ⚠ 未能解析任何 GUI 渲染管线，blit 将退化为不传管线（界面不崩溃，但背景/图标可能不绘制）");
        }
    }

    private BlitCompat() {}

    
    public static Object guiPipeline() {
        return PIPELINE;
    }

    
    public static void blit(GuiGraphicsExtractor gfx,
                            Identifier texture,
                            int x, int y,
                            int u, int v,
                            int w, int h,
                            int tw, int th) {
        if (PIPELINE != null) {
            try {
                getBlitWithPipeline().invoke(gfx, PIPELINE, texture, x, y, u, v, w, h, tw, th);
                return;
            } catch (Throwable t) {
                LOGGER.warn("[BlitCompat] 带管线 blit 调用失败：{}", t.toString());
            }
        }
        
        try {
            Method m = getBlitWithoutPipeline();
            if (m != null) {
                m.invoke(gfx, texture, x, y, u, v, w, h, tw, th);
            }
        } catch (Throwable t) {
            LOGGER.error("[BlitCompat] 兜底 blit 也失败（已解析管线={}）：{}", RESOLVED_NAME, t.toString());
        }
    }

    

    private static volatile Method blitWithPipeline;
    private static volatile Method blitWithoutPipeline;

    
    private static Method getBlitWithPipeline() throws NoSuchMethodException {
        Method m = blitWithPipeline;
        if (m != null) return m;
        synchronized (BlitCompat.class) {
            if (blitWithPipeline != null) return blitWithPipeline;
            
            blitWithPipeline = findBlitMethod(10, PIPELINE_TYPE);
            if (blitWithPipeline == null) {
                throw new NoSuchMethodException(
                        "GuiGraphicsExtractor.blit(RenderPipeline, Identifier, int...×8) not found");
            }
            return blitWithPipeline;
        }
    }

    
    private static Method getBlitWithoutPipeline() {
        Method m = blitWithoutPipeline;
        if (m != null) return m;
        synchronized (BlitCompat.class) {
            if (blitWithoutPipeline != null) return blitWithoutPipeline;
            // 首参必须是 Identifier / ResourceLocation
            blitWithoutPipeline = findBlitMethod(9, Identifier.class);
            return blitWithoutPipeline;
        }
    }

    
    private static Method findBlitMethod(int paramCount, Class<?> expectedFirstParam) {
        for (Method m : GuiGraphicsExtractor.class.getMethods()) {
            if (!"blit".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != paramCount) continue;
            if (expectedFirstParam != null) {
                if (pts.length == 0) continue;
                if (!expectedFirstParam.isAssignableFrom(pts[0])) continue;
            }
            return m;
        }
        return null;
    }
}