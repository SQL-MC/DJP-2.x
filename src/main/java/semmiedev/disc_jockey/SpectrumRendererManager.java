package semmiedev.disc_jockey.gui.screen.spectrum;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.util.List;
import java.util.Arrays;

public class SpectrumRendererManager {

    public enum Style {
        BAR("条形", 0),
        WAVE("波形", 1),
        RING("圆环", 2),
        MIRROR("镜像对称", 3),
        PARTICLE("粒子", 4);

        public final String displayName;
        private final int index;

        Style(String displayName, int index) {
            this.displayName = displayName;
            this.index = index;
        }

        public int getIndex() {
            return index;
        }
    }

    private static final List<SpectrumRenderer> RENDERERS = Arrays.asList(
        new BarSpectrumRenderer(),
        new WaveSpectrumRenderer(),
        new RingSpectrumRenderer(),
        new MirrorSpectrumRenderer(),
        new ParticleSpectrumRenderer()
    );

    private static int currentIndex = 0;

    public static SpectrumRenderer getCurrent() {
        return RENDERERS.get(currentIndex);
    }

    public static void next() {
        currentIndex = (currentIndex + 1) % RENDERERS.size();
    }

    public static void prev() {
        currentIndex = (currentIndex - 1 + RENDERERS.size()) % RENDERERS.size();
    }

    public static Style getCurrentStyle() {
        return Style.values()[currentIndex];
    }

    public static String getCurrentDisplayName() {
        return getCurrentStyle().displayName;
    }

    public static void setStyle(Style style) {
        currentIndex = style.getIndex();
    }

    /*
       ✅ 26.2 修正：GuiGraphicsExtractor 本身就是绘制上下文
       ✅ 不再调 extractor.graphics()，直接透传 extractor
       ✅ 底层 Renderer.render 第一个参数必须是 GuiGraphicsExtractor
    */
    public static void render(
            GuiGraphicsExtractor extractor,
            int width,
            int height,
            float[] levels,
            int bands,
            int baseY
    ) {
        SpectrumRenderer renderer = getCurrent();
        if (renderer == null) return;

        try {
            // 直接调 26.2 签名：render(GuiGraphicsExtractor, int, int, float[], int, int)
            renderer.getClass().getMethod(
                "render",
                GuiGraphicsExtractor.class,
                int.class, int.class,
                float[].class,
                int.class, int.class
            ).invoke(renderer, extractor, width, height, levels, bands, baseY);
        } catch (Throwable t) {
            // 老 Renderer 还没改签名时打日志，不崩
            System.err.println("[Disc Jockey] SpectrumRenderer render failed (check renderer signature): " + t);
        }
    }
}