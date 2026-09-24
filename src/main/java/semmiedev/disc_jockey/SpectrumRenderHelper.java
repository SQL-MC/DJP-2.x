package semmiedev.disc_jockey;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;


public class SpectrumRenderHelper {
    private static final Logger LOGGER = LogManager.getLogger("Disc Jockey-RenderHelper");

    
    
    private static final int BASE_Y_OFFSET = 75;

    
    private static final AtomicBoolean renderingLock = new AtomicBoolean(false);

    
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

    
    public static void render(
            Object guiGraphics,
            Class<?> spectrumRendererClass,
            Method renderMethod,
            int width,
            int height,
            float[] spectrumData
    ) {
        
        if (!renderingLock.compareAndSet(false, true)) {
            return;
        }
        try {
            Object manager = spectrumRendererClass.getMethod("getCurrent").invoke(null);

            
            Object screen = null;
            if (minecraftClass != null && getInstanceMethod != null && screenField != null) {
                Object mc = getInstanceMethod.invoke(null);
                screen = screenField.get(mc);
            }
            if (screen != null && screen.getClass().getName().contains("DiscJockeyScreen")) {
                return;
            }

            
            int baseY = height - BASE_Y_OFFSET;

            
            
            renderMethod.invoke(manager, guiGraphics, width, height, spectrumData, 15, baseY);

        } catch (Throwable ex) {
            LOGGER.error("[Disc Jockey-RenderHelper] Render failed", ex);
        } finally {
            renderingLock.set(false);
        }
    }

    
    public static void resetAnimation() {
        
    }

    public static void setTargetY(float y) {
        
    }

    public static float getCurrentY() {
        // 返回固定值，表示"无动画状态"
        return -100f;
    }
}
