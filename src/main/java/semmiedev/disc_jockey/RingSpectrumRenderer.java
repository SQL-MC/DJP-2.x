package semmiedev.disc_jockey.gui.screen.spectrum;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public class RingSpectrumRenderer implements SpectrumRenderer {

    @Override
    public void render(GuiGraphicsExtractor context, int screenWidth, int screenHeight,
                       float[] levels, int baseX, int baseY) {

        int barCount = levels.length;
        int maxRadius = 96;
        int centerX = baseX + 40;
        int centerY = baseY - 10;
        int barWidth = 5;

        for (int i = 0; i < barCount; i++) {
            float level = levels[i];
            int radius = 10 + (int) (level * maxRadius);

            double angle = 2 * Math.PI * i / barCount - Math.PI / 2;
            int x = centerX + (int) (Math.cos(angle) * radius);
            int y = centerY + (int) (Math.sin(angle) * radius);

            int innerX = centerX + (int) (Math.cos(angle) * 10);
            int innerY = centerY + (int) (Math.sin(angle) * 10);

            int color = lerpColor(level);

            int minX = Math.min(innerX, x);
            int minY = Math.min(innerY, y);
            int maxX = Math.max(innerX, x);
            int maxY = Math.max(innerY, y);
            context.fill(minX, minY, maxX + 1, maxY + 1, color);
        }
    }

    private int lerpColor(float t) {
        if (t < 0.33F) return lerp(0xFF00AA00, 0xFFFFDD00, t / 0.33F);
        else if (t < 0.66F) return lerp(0xFFFFDD00, 0xFFFF6600, (t - 0.33F) / 0.33F);
        else return lerp(0xFFFF6600, 0xFFFF2200, (t - 0.66F) / 0.34F);
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