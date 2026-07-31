package semmiedev.disc_jockey.gui.screen.spectrum;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public class ParticleSpectrumRenderer implements SpectrumRenderer {

    @Override
    public void render(GuiGraphicsExtractor context, int screenWidth, int screenHeight,
                       float[] levels, int baseX, int baseY) {

        int barCount = levels.length;
        int dotSize = 3;
        int spacing = 6;

        for (int i = 0; i < barCount; i++) {
            float level = levels[i];
            int dotCount = Math.max(1, (int) (level * 10));

            int startX = baseX + i * spacing;

            for (int j = 0; j < dotCount; j++) {
                int y = baseY - j * dotSize - dotSize;
                int alpha = (int) (level * 200) + 55;
                alpha = Math.min(255, alpha);

                int color = (alpha << 24) | 0x00FFAA;
                context.fill(startX, y, startX + dotSize, y + dotSize, color);
            }
        }
    }
}