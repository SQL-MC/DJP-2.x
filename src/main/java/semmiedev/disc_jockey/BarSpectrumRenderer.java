package semmiedev.disc_jockey.gui.screen.spectrum;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public class BarSpectrumRenderer implements SpectrumRenderer {

    @Override
    public void render(GuiGraphicsExtractor context, int screenWidth, int screenHeight,
                       float[] levels, int baseX, int baseY) {

        int barCount = levels.length;
        int barWidth = 5;
        int barGap = 2;
        int maxHeight = 96;

        for (int i = 0; i < barCount; i++) {
            float level = levels[i];
            int barHeight = Math.max(2, (int) (level * maxHeight));

            int x = baseX + i * (barWidth + barGap);
            int topY = baseY - barHeight;

            int color = lerpColor(level);

            context.fill(x, topY, x + barWidth, baseY, color);

            if (barHeight > 4) {
                context.fill(x, topY, x + barWidth, topY + 2, 0x88FFFFFF);
            }
        }

        context.fill(baseX - 2, baseY, baseX + barCount * (barWidth + barGap) - barGap + 2, baseY + 1, 0x66FFFFFF);
    }

    private int lerpColor(float t) {
        if (t < 0.33F) {
            return lerp(0xFF00AA00, 0xFFFFDD00, t / 0.33F);
        } else if (t < 0.66F) {
            return lerp(0xFFFFDD00, 0xFFFF6600, (t - 0.33F) / 0.33F);
        } else {
            return lerp(0xFFFF6600, 0xFFFF2200, (t - 0.66F) / 0.34F);
        }
    }

    private int lerp(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ai = (a >> 24) & 0xFF;
        int ri = (a >> 16) & 0xFF;
        int gi = (a >> 8) & 0xFF;
        int bi = a & 0xFF;

        int ar = (b >> 24) & 0xFF;
        int rr = (b >> 16) & 0xFF;
        int gr = (b >> 8) & 0xFF;
        int br = b & 0xFF;

        int r = (int) (ri + (rr - ri) * t);
        int g = (int) (gi + (gr - gi) * t);
        int bb = (int) (bi + (br - bi) * t);
        int aa = (int) (ai + (ar - ai) * t);

        return (aa << 24) | (r << 16) | (g << 8) | bb;
    }
}