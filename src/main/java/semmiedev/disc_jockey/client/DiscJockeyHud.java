package semmiedev.disc_jockey.client;

import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class DiscJockeyHud {
    private static final Logger LOGGER = LogManager.getLogger("Disc Jockey HUD");
    private static boolean initialized = false;

    public static void init() {
        if (initialized) {
            LOGGER.info("HUD already initialized, skipping");
            return;
        }
        LOGGER.info("Starting HUD reflection initialization...");
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            LOGGER.info("Loaded Minecraft class");

            // ✅ 26.2 正确类
            Class<?> guiGraphicsExtractorClass =
                    Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor");
            LOGGER.info("Loaded GuiGraphicsExtractor class");

            // ✅ 26.2：ResourceLocation → Identifier
            Class<?> identifierClass =
                    Class.forName("net.minecraft.resources.Identifier");
            LOGGER.info("Loaded Identifier class");

            Class<?> hudRegistryClass = Class.forName(
                    "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry"
            );
            LOGGER.info("Loaded HudElementRegistry class");

            Class<?> spectrumRendererClass = Class.forName(
                    "semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager"
            );
            LOGGER.info("Loaded SpectrumRendererManager class");

            Class<?> guiClass = Class.forName("net.minecraft.client.gui.Gui");
            LOGGER.info("Loaded Gui class");

            Method getInstanceMethod = mcClass.getMethod("getInstance");
            Field guiField = mcClass.getDeclaredField("gui");
            guiField.setAccessible(true);
            Field screenField = guiClass.getDeclaredField("screen");
            screenField.setAccessible(true);

            Method getWindowMethod = mcClass.getMethod("getWindow");

            // ✅ 26.2：getScaledWidth → getGuiScaledWidth
            Method getWidthMethod =
                    getWindowMethod.getReturnType().getMethod("getGuiScaledWidth");
            Method getHeightMethod =
                    getWindowMethod.getReturnType().getMethod("getGuiScaledHeight");

            // ✅ 26.2：Identifier.of → 私有构造反射
            Constructor<?> idCtor =
                    identifierClass.getDeclaredConstructor(String.class, String.class);
            idCtor.setAccessible(true);

            Method addLastMethod = hudRegistryClass.getMethod(
                    "addLast",
                    identifierClass,
                    java.util.function.BiConsumer.class
            );

            Method getCurrentRendererMethod =
                    spectrumRendererClass.getMethod("getCurrent");

            Method renderMethod = spectrumRendererClass.getMethod(
                    "render",
                    guiGraphicsExtractorClass,
                    int.class,
                    int.class,
                    float[].class,
                    int.class,
                    int.class
            );

            Object spectrumLayer =
                    idCtor.newInstance(Main.MOD_ID, "spectrum");

            addLastMethod.invoke(null, spectrumLayer,
                    (java.util.function.BiConsumer<Object, Object>)
                            (guiGraphics, deltaTracker) -> {
                        try {
                            Object currentMc = getInstanceMethod.invoke(null);
                            Config cfg = Main.configHolder.getConfig();

                            if (!cfg.spectrumAlwaysVisible) {
                                Object gui = guiField.get(currentMc);
                                Object screen = screenField.get(gui);
                                if (screen == null ||
                                    !screen.getClass().getName()
                                            .contains("DiscJockeyScreen")) {
                                    return;
                                }
                            }

                            if (!Main.SONG_PLAYER.running
                                    && !Main.PREVIEWER.running) {
                                return;
                            }

                            Object window = getWindowMethod.invoke(currentMc);
                            int width = (int) getWidthMethod.invoke(window);
                            int height = (int) getHeightMethod.invoke(window);
                            int bottomY = height - 55;

                            Object renderer =
                                    getCurrentRendererMethod.invoke(null);

                            renderMethod.invoke(
                                    renderer,
                                    guiGraphics,
                                    width,
                                    height,
                                    Main.SPECTRUM.currentLevels,
                                    15,
                                    bottomY
                            );
                        } catch (Throwable t) {
                            LOGGER.error("HUD render failed", t);
                        }
                    });

            initialized = true;
            LOGGER.info("✅ HUD reflection initialization successful!");
        } catch (Throwable t) {
            LOGGER.error("❌ HUD reflection initialization failed!", t);
        }
    }
}