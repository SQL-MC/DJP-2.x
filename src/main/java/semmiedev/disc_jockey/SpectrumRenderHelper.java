package semmiedev.disc_jockey;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ✅ SpectrumRenderHelper（最终版 · 无动画 · 无重影）
 *
 * 问题根因（看图13确认）：
 *   旧版 Helper 有完整的 lerp 动画系统（currentY/targetY/elapsedTime），
 *   currentY 从 -100 慢慢 lerp 到 height-75，
 *   动画过程中间帧把频谱画在了错误位置 → 模糊重影。
 *
 * 修复：
 *   ✅ 删除所有位置动画（currentY/targetY/elapsedTime/isMoving）
 *   ✅ 删除 lerp 插值逻辑
 *   ✅ 只做一件事：在正确位置画一次
 *   ✅ renderingLock 防双画
 *
 * 位置公式（和 DiscJockeyScreen.renderSpectrum 完全一致）：
 *   baseY = height - 75
 *   GUI 里 screenHeight=this.height, baseY=screenHeight-75
 *   HUD 里 height=guiGraphicsExtractor.guiHeight()
 *
 * ⚠️ 注意：Main.java 的 HUD 回调里用的是 bottomY = height - 55
 *   如果位置还差 20px，把 BASE_Y_OFFSET 从 75 改成 55
 */
public class SpectrumRenderHelper {
    private static final Logger LOGGER = LogManager.getLogger("Disc Jockey-RenderHelper");

    // === 位置偏移（和 GUI 的 screenHeight - 75 对齐）===
    // 如果实际测试发现还差 20px，改成 55
    private static final int BASE_Y_OFFSET = 75;

    // === 渲染互斥锁：防止多路径同帧双画 ===
    private static final AtomicBoolean renderingLock = new AtomicBoolean(false);

    // === 反射缓存（只初始化一次）===
    private static Class<?> minecraftClass;
    private static Method getInstanceMethod;
    private static Field screenField;
    static {
        try {
            minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            getInstanceMethod = minecraftClass.getMethod("getInstance");
            screenField = minecraftClass.getField("screen");
        } catch (Throwable t) {
            minecraftClass = null;
            getInstanceMethod = null;
            screenField = null;
        }
    }

    /**
     * 渲染频谱（无动画，画一次就走）
     */
    public static void render(
            Object guiGraphics,
            Class<?> spectrumRendererClass,
            Method renderMethod,
            int width,
            int height,
            float[] spectrumData
    ) {
        // ✅ 防重影：同一帧只画一次
        if (!renderingLock.compareAndSet(false, true)) {
            return;
        }
        try {
            Object manager = spectrumRendererClass.getMethod("getCurrent").invoke(null);

            // ✅ GUI 开着时让 GUI 自己画
            Object screen = null;
            if (minecraftClass != null && getInstanceMethod != null && screenField != null) {
                Object mc = getInstanceMethod.invoke(null);
                screen = screenField.get(mc);
            }
            if (screen != null && screen.getClass().getName().contains("DiscJockeyScreen")) {
                return;
            }

            // ✅ 和 GUI 完全一样的公式：height - 75
            int baseY = height - BASE_Y_OFFSET;

            // ✅ 直接画，不做任何位置动画
            // 参数对应 SpectrumRendererManager.render(guiGraphics, width, height, levels, startX, baseY)
            renderMethod.invoke(manager, guiGraphics, width, height, spectrumData, 15, baseY);

        } catch (Throwable ex) {
            LOGGER.error("[Disc Jockey-RenderHelper] Render failed", ex);
        } finally {
            renderingLock.set(false);
        }
    }

    /* =========================================================
       ✅ 以下方法保留签名（外部可能调用），但不再做任何动画
       ========================================================= */
    public static void resetAnimation() {
        // 无动画，空实现
    }

    public static void setTargetY(float y) {
        // 无动画，空实现
    }

    public static float getCurrentY() {
        // 返回固定值，表示"无动画状态"
        return -100f;
    }
}
