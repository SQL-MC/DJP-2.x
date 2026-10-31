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
            // 26.3：net.minecraft.client.renderer.RenderPipelines
            "GUI_TEXTURED",
            "GUI",
            "GUI_OPAQUE_TEXTURED_BACKGROUND",
            "GUI_OPAQUE_TEX_BG",
            "GUI_TEXTURED_OVERLAY",
            "GUI_TEXTURED_PREMULTIPLIED_ALPHA",
            "GUI_TEXT_HIGHLIGHT",
            // 26.4 snapshot-6：net.minecraft.client.gui.GuiPipeline（javap 实测）
            "TEXTURED",
            "TEXTURED_PREMULTIPLIED_ALPHA",
            "OPAQUE_TEXTURED_BACKGROUND",
            "DEFAULT",
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
        for (String cn : new String[]{
                "net.minecraft.client.renderer.RenderPipelines",   // 26.3
                "net.minecraft.client.gui.GuiPipeline"}) {          // 26.4 snapshot-6
            try {
                rpClass = Class.forName(cn);
                break;
            } catch (Throwable t) {
                LOGGER.warn("[BlitCompat] 找不到管线类 {}：{}", cn, t.toString());
            }
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
    /** blitFull 命中签名只报一次 */
    private static final AtomicBoolean WARN_FULL = new AtomicBoolean(false);
    /** 已确认带管线路径不可用，后续不再尝试（省反射开销、杜绝刷屏） */
    private static volatile boolean pipeDisabled = false;

    // ==================== 方法缓存 ====================

    private static volatile Method blitWithPipeline;
    private static volatile Method blitWithoutPipeline;
    private static volatile boolean withPipeLookupDone = false;
    private static volatile boolean withoutPipeLookupDone = false;

    /**
     * 绘制纹理。返回是否真的画成功了（调用方可据此决定要不要退回纯色）。
     *
     * ★ 后端无关：OpenGL（26.3）/ Vulkan（26.4）对这里的代码是透明的，
     *   MC 的 GuiGraphics 抽象层屏蔽了后端差异。真正需要适配的是【API 签名】：
     *     · 有 RenderPipelines 类（26.3）→ 10 参带管线，u/v 为像素
     *     · 无 RenderPipelines 类（26.4）→ 9 参无管线，u/v 为 0..1 归一化
     *   两条路径的实参排布在下面分别处理，因此同一个 jar 两端都能画。
     */
    public static boolean blit(GuiGraphicsExtractor gfx,
                            Identifier texture,
                            int x, int y,
                            int u, int v,
                            int w, int h,
                            int tw, int th) {
        if (gfx == null || texture == null) return false;

        // ---- 路径 1：带管线（10 参，像素 UV）----
        if (PIPELINE != null && !pipeDisabled) {
            Object[] args = {PIPELINE, texture, x, y, u, v, w, h, tw, th};
            Method m = getBlitWithPipeline(args);
            if (m != null) {
                try {
                    m.invoke(gfx, coerce(m, args));
                    return true;
                } catch (Throwable t) {
                    if (WARN_PIPE.compareAndSet(false, true)) {
                        LOGGER.warn("[BlitCompat] 带管线 blit 调用失败，此后改用无管线（此条只报一次）：{}", t.toString());
                    }
                    pipeDisabled = true;
                }
            }
        }

        // ---- 路径 2：无管线（9 参，归一化 UV）----
        // 形参是 (texture, x, y, width, height, u0, v0, uWidth, vHeight)，
        // 后 4 个是 0..1 归一化，不是像素 —— 传 u/v 进去会让 width/height 变 0，
        // 调用不报错但什么也画不出（26.4 早期"只剩纯色"就是这个坑）。
        Object[] args2 = {texture, x, y, w, h,
                  tw > 0 ? (float) u / tw : 0f,
                  th > 0 ? (float) v / th : 0f,
                  tw > 0 ? (float) w / tw : 1f,
                  th > 0 ? (float) h / th : 1f};
        Method m2 = getBlitWithoutPipeline(args2);
        if (m2 != null) {
            try {
                m2.invoke(gfx, coerce(m2, args2));
                return true;
            } catch (Throwable t) {
                if (WARN_FALLBACK.compareAndSet(false, true)) {
                    LOGGER.error("[BlitCompat] 无管线 blit 也失败（已解析管线={}），此条只报一次：{}",
                            RESOLVED_NAME, t.toString());
                }
            }
        } else if (WARN_FALLBACK.compareAndSet(false, true)) {
            LOGGER.error("[BlitCompat] 找不到可用的 blit 方法，纹理绘制跳过（已解析管线={}）", RESOLVED_NAME);
        }
        return false;
    }

    /**
     * 把【整张】贴图缩放绘制到 (x, y, w, h) 目标矩形（UV 固定 0..1）。
     *
     * ★ 与 blit() 的关键区别：
     *   blit() 的 w/h 同时承担「源区域尺寸」与「目标绘制尺寸」两个角色，
     *   在归一化路径下会把 UV 宽度算成 w/tw —— 例如格子 9x9、贴图 16x16
     *   时得到 9/16≈0.56，只采样左上角 56% 再拉伸，图标被裁切放大；
     *   音符密集（上万音符）时整片糊成黑白黄斑，正是 26.4 之前「GUI 烂掉」的根因。
     *
     *   blitFull 固定采样整张贴图（u0=0, v0=0, uW=1, vH=1），
     *   目标尺寸独立传 (w, h)，因此是「缩放」而不是「裁切放大」。
     *
     * 只匹配归一化签名（后 4 位是 float），不会误匹配像素 UV 版本，
     * 避免把 (x,y,w,h) 当成 (u,v,w,h) 传进去画出 0 尺寸。
     */
    public static boolean blitFull(GuiGraphicsExtractor gfx,
                                   Identifier texture,
                                   int x, int y, int w, int h) {
        if (gfx == null || texture == null) return false;

        // ---- 路径 1：带管线 + 归一化（26.3 若提供该重载）----
        if (PIPELINE != null && !pipeDisabled) {
            Method m = findNormalizedBlit(true);
            if (m != null) {
                try {
                    m.invoke(gfx, coerce(m, new Object[]{PIPELINE, texture, x, y, w, h, 0f, 0f, 1f, 1f}));
                    return true;
                } catch (Throwable t) {
                    pipeDisabled = true;
                    if (WARN_FULL.compareAndSet(false, true)) {
                        LOGGER.warn("[BlitCompat] blitFull 带管线失败，改走无管线（只报一次）：{}", t.toString());
                    }
                }
            }
        }

        // ---- 路径 2：无管线 + 归一化（26.4 snapshot-6 实际走这条）----
        Method m2 = findNormalizedBlit(false);
        if (m2 != null) {
            try {
                m2.invoke(gfx, coerce(m2, new Object[]{texture, x, y, w, h, 0f, 0f, 1f, 1f}));
                if (WARN_FULL.compareAndSet(false, true)) {
                    LOGGER.info("[BlitCompat] ✓ blitFull 命中归一化签名：blit{}", signature(m2));
                }
                return true;
            } catch (Throwable t) {
                if (WARN_FALLBACK.compareAndSet(false, true)) {
                    LOGGER.error("[BlitCompat] blitFull 无管线归一化失败（只报一次）：{}", t.toString());
                }
            }
        }
        if (WARN_FALLBACK.compareAndSet(false, true)) {
            LOGGER.error("[BlitCompat] blitFull 找不到归一化 blit，图标退回纯色");
        }
        return false;
    }

    /**
     * 精确匹配「归一化 UV」版 blit，避免误命中像素 UV 版本。
     *   带管线：(pipeline, Identifier, int x, int y, int w, int h, float u0, float v0, float uW, float vH)
     *   无管线：(Identifier, int x, int y, int w, int h, float u0, float v0, float uW, float vH)
     */
    private static Method findNormalizedBlit(boolean withPipe) {
        int off = withPipe ? 1 : 0;
        for (Method m : GuiGraphicsExtractor.class.getMethods()) {
            if (!"blit".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != off + 9) continue;

            boolean ok = true;
            if (withPipe) {
                if (PIPELINE == null || !p[0].isInstance(PIPELINE)) ok = false;
            }
            if (ok) {
                Class<?> tex = p[off];
                if (!(Identifier.class.isAssignableFrom(tex) || tex.isAssignableFrom(Identifier.class))) ok = false;
            }
            // x, y, w, h 四位必须是 int（排除把 (u,v) 排在前面的像素版）
            for (int i = off + 1; i <= off + 4 && ok; i++) {
                if (p[i] != int.class) ok = false;
            }
            // u0, v0, uWidth, vHeight 四位必须是 float
            for (int i = off + 5; i <= off + 8 && ok; i++) {
                if (p[i] != float.class) ok = false;
            }
            if (ok) return m;
        }
        return null;
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
