package semmiedev.disc_jockey.util;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
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
                    Field f = rpClass.getDeclaredField(cand);
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
                for (Field f : rpClass.getDeclaredFields()) {
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


    // ==================== 防刷屏开关（只报一次） ====================

    /** 带管线调用失败只报一次，之后永久走无管线路径 */
    private static final AtomicBoolean WARN_PIPE = new AtomicBoolean(false);
    /** 无管线也失败只报一次 */
    private static final AtomicBoolean WARN_FALLBACK = new AtomicBoolean(false);
    /** 已确认带管线路径不可用，后续不再尝试（省反射开销、杜绝刷屏） */
    private static volatile boolean pipeDisabled = false;

    // ==================== 方法缓存 ====================

    private static volatile Method blitWithPipeline;
    private static volatile Method blitWithoutPipeline;
    private static volatile boolean withPipeLookupDone = false;
    private static volatile boolean withoutPipeLookupDone = false;

    public static void blit(GuiGraphicsExtractor gfx,
                            Identifier texture,
                            int x, int y,
                            int u, int v,
                            int w, int h,
                            int tw, int th) {
        if (gfx == null || texture == null) return;

        // ---- 路径 1：带管线（10 参）----
        if (PIPELINE != null && !pipeDisabled) {
            Object[] args = {PIPELINE, texture, x, y, u, v, w, h, tw, th};
            Method m = getBlitWithPipeline(args);
            if (m != null) {
                try {
                    m.invoke(gfx, coerce(m, args));
                    return;
                } catch (Throwable t) {
                    if (WARN_PIPE.compareAndSet(false, true)) {
                        LOGGER.warn("[BlitCompat] 带管线 blit 调用失败，此后改用无管线（此条只报一次）：{}", t.toString());
                    }
                    pipeDisabled = true;
                }
            }
        }

        // ---- 路径 2：无管线（9 参）----
        Object[] args2 = {texture, x, y, u, v, w, h, tw, th};
        Method m2 = getBlitWithoutPipeline(args2);
        if (m2 != null) {
            try {
                m2.invoke(gfx, coerce(m2, args2));
                return;
            } catch (Throwable t) {
                if (WARN_FALLBACK.compareAndSet(false, true)) {
                    LOGGER.error("[BlitCompat] 无管线 blit 也失败（已解析管线={}），此条只报一次：{}",
                            RESOLVED_NAME, t.toString());
                }
            }
        } else if (WARN_FALLBACK.compareAndSet(false, true)) {
            LOGGER.error("[BlitCompat] 找不到可用的 blit 方法，纹理绘制跳过（已解析管线={}）", RESOLVED_NAME);
        }
    }


    private static Method getBlitWithPipeline(Object[] args) {
        Method m = blitWithPipeline;
        if (m != null) return m;
        if (withPipeLookupDone) return null;
        synchronized (BlitCompat.class) {
            if (blitWithPipeline != null) return blitWithPipeline;
            if (withPipeLookupDone) return null;
            withPipeLookupDone = true;
            Method found = findBlitMethod(args);
            if (found != null) {
                blitWithPipeline = found;
                LOGGER.info("[BlitCompat] ✓ 选中带管线签名：blit{}", signature(found));
            } else {
                LOGGER.warn("[BlitCompat] 未找到 10 参带管线 blit，改用无管线（此条只报一次）");
            }
            return found;
        }
    }


    private static Method getBlitWithoutPipeline(Object[] args) {
        Method m = blitWithoutPipeline;
        if (m != null) return m;
        if (withoutPipeLookupDone) return null;
        synchronized (BlitCompat.class) {
            if (blitWithoutPipeline != null) return blitWithoutPipeline;
            if (withoutPipeLookupDone) return null;
            withoutPipeLookupDone = true;
            Method found = findBlitMethod(args);
            if (found != null) {
                blitWithoutPipeline = found;
                LOGGER.info("[BlitCompat] ✓ 选中无管线签名：blit{}", signature(found));
            }
            return found;
        }
    }

    /**
     * 按「实参逐个可赋值」挑方法，而不是只看形参个数。
     *
     * 关键：26.4 的 10 参版本 u/v 是 float，直接传 Integer 会
     * IllegalArgumentException: argument type mismatch。
     * 所以匹配阶段允许「数字 ↔ 数字」，真正转换交给 coerce()。
     */
    private static Method findBlitMethod(Object[] args) {
        Method loose = null;
        for (Method m : GuiGraphicsExtractor.class.getMethods()) {
            if (!"blit".equals(m.getName())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != args.length) continue;

            boolean strict = true;
            boolean ok = true;
            for (int i = 0; i < pts.length && ok; i++) {
                Class<?> p = pts[i];
                Object a = args[i];

                if (a == null) { ok = false; break; }

                if (isNumericPrimitive(p)) {
                    // 数字之间可互相转换（int → float 等）
                    if (!(a instanceof Number)) ok = false;
                    continue;
                }
                if (p == boolean.class || p == Boolean.class) {
                    if (!(a instanceof Boolean)) ok = false;
                    continue;
                }
                if (p.isPrimitive()) { ok = false; break; }

                if (p.isAssignableFrom(a.getClass())) continue;

                // 非严格：类型对不上但形参个数对（例如接口/包装差异）
                strict = false;
            }
            if (!ok) continue;
            if (strict) return m;
            if (loose == null) loose = m;
        }
        return loose;
    }

    /** 按目标形参类型做基本类型/数字转换 */
    private static Object[] coerce(Method m, Object[] args) {
        Class<?>[] pts = m.getParameterTypes();
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = convert(args[i], pts[i]);
        }
        return out;
    }

    private static Object convert(Object a, Class<?> p) {
        if (a == null) return null;
        if (!p.isPrimitive()) return a;
        if (a instanceof Number) {
            Number n = (Number) a;
            if (p == int.class) return n.intValue();
            if (p == float.class) return n.floatValue();
            if (p == double.class) return n.doubleValue();
            if (p == long.class) return n.longValue();
            if (p == short.class) return n.shortValue();
            if (p == byte.class) return n.byteValue();
        }
        return a;
    }

    private static boolean isNumericPrimitive(Class<?> p) {
        return p == int.class || p == float.class || p == double.class
                || p == long.class || p == short.class || p == byte.class;
    }

    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pts[i].getSimpleName());
        }
        return sb.append(')').toString();
    }
}
