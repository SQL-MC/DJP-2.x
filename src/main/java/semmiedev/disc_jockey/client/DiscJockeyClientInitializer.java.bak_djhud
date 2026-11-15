package semmiedev.disc_jockey.client;

import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Config;

public class DiscJockeyClientInitializer {

    public static void init() {
        try {
            
            Class<?> hudRegistryClass =
                    Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
            Class<?> mcClass =
                    Class.forName("net.minecraft.client.Minecraft");
            Class<?> resourceLocationClass =
                    Class.forName("net.minecraft.resources.ResourceLocation");
            Class<?> guiGraphicsClass =
                    Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor");

            Object mc = mcClass.getMethod("getInstance").invoke(null);

            Object spectrumLayer = resourceLocationClass
                    .getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, Main.MOD_ID, "spectrum");

            java.lang.reflect.Method addLast = hudRegistryClass.getMethod(
                    "addLast",
                    resourceLocationClass,
                    java.util.function.BiConsumer.class
            );

            addLast.invoke(null, spectrumLayer,
                    (java.util.function.BiConsumer<Object, Object>) (guiGraphics, deltaTracker) -> {
                try {
                    Object currentMc = mcClass.getMethod("getInstance").invoke(null);
                    Config cfg = Main.configHolder.getConfig();

                    
                    Object gui = mcClass.getDeclaredField("gui").get(currentMc);
                    Object screen = gui.getClass().getDeclaredField("screen").get(gui);

                    if (!cfg.spectrumAlwaysVisible) {
                        if (screen == null ||
                            !screen.getClass().getName().contains("DiscJockeyScreen")) {
                            return;
                        }
                    }

                    if (!Main.SONG_PLAYER.running && !Main.PREVIEWER.running) {
                        return;
                    }

                    
                    Object window = currentMc.getClass().getMethod("getWindow").invoke(currentMc);
                    int width = (int) window.getClass()
                            .getMethod("getGuiScaledWidth").invoke(window);
                    int height = (int) window.getClass()
                            .getMethod("getGuiScaledHeight").invoke(window);
                    int bottomY = height - 55;

                    
                    Class<?> srmClass = Class.forName(
                            "semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager"
                    );
                    Object manager = srmClass.getMethod("getCurrent").invoke(null);

                    srmClass.getMethod(
                            "render",
                            guiGraphicsClass,
                            int.class,
                            int.class,
                            float[].class,
                            int.class,
                            int.class
                    ).invoke(
                            manager,
                            guiGraphics,
                            width,
                            height,
                            Main.SPECTRUM.currentLevels,
                            15,
                            bottomY
                    );

                } catch (Exception ignored) {}
            });

        } catch (Exception ignored) {}
    }
}