package semmiedev.disc_jockey.gui.screen.spectrum;

import net.minecraft.client.gui.GuiGraphicsExtractor;


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