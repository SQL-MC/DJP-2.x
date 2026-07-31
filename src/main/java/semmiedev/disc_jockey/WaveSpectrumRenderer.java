package semmiedev.disc_jockey.gui.screen.spectrum;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public class WaveSpectrumRenderer implements SpectrumRenderer {

    @Override
    public void render(GuiGraphicsExtractor context, int screenWidth, int screenHeight,
                       float[] levels, int baseX, int baseY) {

        int barCount = levels.length;
        int maxHeight = 96;
        int spacing = 4;

        for (int i = 0; i < barCount - 1; i++) {
            float l1 = levels[i];
            float l2 = levels[i + 1];

            int x1 = baseX + i * spacing;
            int y1 = baseY - (int) (l1 * maxHeight);
            int x2 = baseX + (i + 1) * spacing;
            int y2 = baseY - (int) (l2 * maxHeight);

            int color = lerpColor((l1 + l2) * 0.5F);

            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            context.fill(x1, minY, x2, maxY + 1, color);
        }

        for (int i = 0; i < barCount; i++) {
            float level = levels[i];
            int x = baseX + i * spacing;
            int y = baseY - (int) (level * maxHeight);
            context.fill(x - 1, y - 1, x + 1, y + 1, 0xFFFFFFFF);
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