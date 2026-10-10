package semmiedev.disc_jockey.gui.screen.spectrum;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.util.List;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpectrumRendererManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Disc Jockey/SpectrumRendererManager");

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
            renderer.render(extractor, width, height, levels, bands, baseY);
        } catch (Throwable t) {
            LOGGER.error("[Disc Jockey] SpectrumRenderer render failed: {}", t.toString());
        }
    }
}