package semmiedev.disc_jockey.util;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * ✅ 26.3 渲染兼容工具
 * ---------------------------------------------------------------
 * 问题：Minecraft 26.3-rc 从 {@code RenderPipelines} 中移除了
 *       {@code GUI_TEXTURED} 静态字段（或对其重命名），导致
 *       {@code context.blit(RenderPipelines.GUI_TEXTURED, ...)}
 *       在打开 DJ 界面时抛出 {@code NoSuchFieldError}，整个界面崩溃。
 *
 * 方案：运行时通过反射解析一个合适的 GUI 渲染管线对象，不再硬编码字段名。
 *       - 优先按候选名（GUI_TEXTURED / GUI / GUI_OPAQUE_TEXTURED_BACKGROUND …）精确取值
 *       - 候选均未命中时，扫描 RenderPipelines 的全部静态字段，挑选类型为
 *         RenderPipeline 且名字含 "GUI" 的字段（对 26.3 后续小版本重命名免疫）
 *       - 若管线仍解析失败，blit 退化为调用无管线参数的重载（最坏仅图标/背景不绘制，
 *         但界面不再崩溃）
 *
 * 用法：将 {@code context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h, tw, th)}
 *      统一替换为 {@code BlitCompat.blit(context, texture, x, y, u, v, w, h, tw, th)}
 * ---------------------------------------------------------------
 */
public final class BlitCompat {

    private static final Logger LOGGER = LogManager.getLogger("Disc Jockey/BlitCompat");

    /** 候选管线字段名（按优先级）。26.3 小版本若改名，反射会逐一尝试。 */
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
            pt = Class.forName("com.mojang.blaze3d.pipeline.RenderPipeline");
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
            // 1) 优先按已知候选名精确取静态字段
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
                    // 该候选在当前版本不存在，尝试下一个
                } catch (Throwable t) {
                    LOGGER.warn("[BlitCompat] 读取字段 {} 失败：{}", cand, t.toString());
                }
            }

            // 2) 候选均未命中：扫描全部静态字段，挑类型匹配且名字含 GUI 的字段
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
                        // 忽略单个字段读取失败
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

    /** 已解析的 GUI 渲染管线对象；可能为 null（{@link #blit} 内部已处理）。 */
    public static Object guiPipeline() {
        return PIPELINE;
    }

    /**
     * 兼容版 blit，参数顺序与原 {@code blit(RenderPipeline, ResourceLocation, x, y, u, v, w, h, tw, th)} 一致：
     * {@code texture, x, y, u, v, width, height, textureWidth, textureHeight}。
     */
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
        // 兜底：无管线参数的 blit 重载（首参为 Identifier 的那一个）
        try {
            Method m = getBlitWithoutPipeline();
            if (m != null) {
                m.invoke(gfx, texture, x, y, u, v, w, h, tw, th);
            }
        } catch (Throwable t) {
            LOGGER.error("[BlitCompat] 兜底 blit 也失败（已解析管线={}）：{}", RESOLVED_NAME, t.toString());
        }
    }

    /* ==================== 反射缓存 ==================== */

    private static volatile Method blitWithPipeline;
    private static volatile Method blitWithoutPipeline;

    /** 带管线参数的 blit(RenderPipeline, Identifier, 8×int)，按首参类型精确锁定。 */
    private static Method getBlitWithPipeline() throws NoSuchMethodException {
        Method m = blitWithPipeline;
        if (m != null) return m;
        synchronized (BlitCompat.class) {
            if (blitWithPipeline != null) return blitWithPipeline;
            // 首参必须是 RenderPipeline
            blitWithPipeline = findBlitMethod(10, PIPELINE_TYPE);
            if (blitWithPipeline == null) {
                throw new NoSuchMethodException(
                        "GuiGraphicsExtractor.blit(RenderPipeline, Identifier, int...×8) not found");
            }
            return blitWithPipeline;
        }
    }

    /** 无管线参数的 blit(Identifier, 8×int)；不存在则返回 null。 */
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

    /**
     * 在 {@link GuiGraphicsExtractor} 中查找名为 "blit"、参数个数 = {@code paramCount}、
     * 且第 0 个参数可赋值给 {@code expectedFirstParam} 的方法。
     * {@code expectedFirstParam} 为 null 时不校验首参类型（仅按参数个数匹配）。
     */
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
