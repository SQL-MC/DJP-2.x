package semmiedev.disc_jockey.gui.screen.spectrum;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * ✅ 频谱渲染接口
 * 所有具体频谱（条形 / 波形 / 圆环 / 镜像 / 粒子）都必须实现此接口
 *
 * ⚠️ 约定：
 * - levels.length == SpectrumVisualizer.BAND_COUNT（固定 16）
 * - baseX / baseY 是“左下锚点”，由调用方决定
 */
public interface SpectrumRenderer {

    void render(
            GuiGraphicsExtractor context,
            int screenWidth,
            int screenHeight,
            float[] levels,
            int baseX,
            int baseY
    );
}