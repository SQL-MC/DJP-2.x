package semmiedev.disc_jockey;

import net.minecraft.world.item.Item;        // Item, Item.Properties
import net.minecraft.core.Registry;           // Registry.register
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumVisualizer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;         
import com.mojang.blaze3d.platform.InputConstants;          

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class Main implements ClientModInitializer {
    public static final String MOD_ID = "disc_jockey";
    public static final Component NAME = Component.literal("Disc Jockey");

    public static final Logger LOGGER = LogManager.getLogger("Disc Jockey");
    public static final ArrayList<ClientTickEvents.StartLevelTick> TICK_LISTENERS = new ArrayList<>();

    
    public static final String VERSION;

    static {
        String v = "unknown";
        try (InputStream in = Main.class.getClassLoader()
                .getResourceAsStream("assets/disc_jockey/version.txt")) {
            if (in != null) {
                v = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .readLine().trim();
            }
        } catch (Exception ignored) {}
        VERSION = v;
    }


    public static final Previewer PREVIEWER = new Previewer();
    public static final SongPlayer SONG_PLAYER = new SongPlayer();
    public static final SpectrumVisualizer SPECTRUM = new SpectrumVisualizer();
    static { LyricsPlayer.register(); }   
    
    

    // ✅ 独立频谱缓冲器实例（和PREVIEWER/SONG_PLAYER同级，不破坏原有逻辑）
    public static final SpectrumDataSmoother SMOOTHER = new SpectrumDataSmoother();

    public static File songsFolder;
    
    public static semmiedev.disc_jockey.Config config;
    
    public static ConfigHolder<semmiedev.disc_jockey.Config> configHolder;

    
    private static boolean sentWelcome = false;

    
    private KeyMapping muteKey;
    private KeyMapping loopKey;
    

    // ========== ✅ 升降调快捷键（[ 降 / ] 升） ==========
    private KeyMapping transposeDownKey;
    private KeyMapping transposeUpKey;
    

    
    private KeyMapping djKey;      
    private KeyMapping pianoKey;   
    

    // ========== ✅ DJP021500：预览播放速度（/discjockey speed 共享，问题3） ==========
    //   默认 1.0；由 /discjockey speed 同时设置，Previewer.tickAndPlay 步进乘此值。
    //   Smoother/Visualizer 不动，仅 Previewer 步进乘此值。
    public static float PREVIEW_SPEED = 1.0F;
    
    private static boolean mutedByDJ = false;


    
    private static void muteGameMusic() {}
    private static void restoreGameMusic() {}

    // ========== ✅ DJP020002：跨帧安全开界面队列（根治 /discjockey 偶发打不开） ==========
    
    
    
    private static final ConcurrentLinkedQueue<Screen> pendingScreens = new ConcurrentLinkedQueue<>();

    
    public static void openScreenOnNextTick(Screen screen) {
        if (screen != null) {
            pendingScreens.offer(screen);
        }
    }
    public static final Item JUKEBOX_PROJECTOR = Registry.register(
        BuiltInRegistries.ITEM,
        Identifier.fromNamespaceAndPath(MOD_ID, "jukebox_projector"),
        new JukeboxProjectorItem(new Item.Properties().stacksTo(16).setId(
                ResourceKey.create(Registries.ITEM,
                        Identifier.fromNamespaceAndPath(MOD_ID, "jukebox_projector")))));
    static {
    // 注册完立刻把引用塞给物品类，保证单实例
        JukeboxProjectorItem.INSTANCE = (JukeboxProjectorItem) JUKEBOX_PROJECTOR;
    }                  

    @Override
    public void onInitializeClient() {
        DiscJockeyCreativeTab.register();
        configHolder = AutoConfig.register(semmiedev.disc_jockey.Config.class, JanksonConfigSerializer::new);
        config = configHolder.getConfig();

        if (config.configVersion < 1) {
            config = new semmiedev.disc_jockey.Config();
            config.configVersion = 1;
            configHolder.save();
        }

        
        
        //   无条件置 true 并持久化，确保主菜单/世界内频谱 HUD 始终注册渲染。
        if (!config.spectrumAlwaysVisible) {
            config.spectrumAlwaysVisible = true;
            try { configHolder.save(); } catch (Throwable ignored) {}
            LOGGER.info("[Disc Jockey] spectrumAlwaysVisible forced to true (was false in config)");
        }

        songsFolder = new File(
                FabricLoader.getInstance().getConfigDir() + File.separator + MOD_ID + File.separator + "songs"
        );
        if (!songsFolder.isDirectory()) songsFolder.mkdirs();

        SongLoader.loadSongs();
        
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            LOGGER.info("[Disc Jockey] Client fully started, beginning HUD reflection setup...");

            try {
                
                ClassLoader fabricClassLoader = Thread.currentThread().getContextClassLoader();
                LOGGER.info("[Disc Jockey] ✓ Got Fabric ClassLoader: {}", fabricClassLoader);

                
                Class<?> hudRegistryClass = fabricClassLoader.loadClass(
                        "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
                Class<?> hudElementClass = fabricClassLoader.loadClass(
                        "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement");
                LOGGER.info("[Disc Jockey] ✓ Loaded HudElementRegistry class");
                LOGGER.info("[Disc Jockey] ✓ Loaded HudElement class");

                
                Method samMethod = null;
                for (Method m : hudElementClass.getDeclaredMethods()) {
                    if (Modifier.isAbstract(m.getModifiers())) {
                        samMethod = m;
                        break;
                    }
                }
                if (samMethod == null) {
                    for (Method m : hudElementClass.getMethods()) {
                        if (Modifier.isAbstract(m.getModifiers())) {
                            samMethod = m;
                            break;
                        }
                    }
                }
                if (samMethod == null) {
                    throw new RuntimeException(
                            "Cannot find any abstract method on HudElement interface");
                }
                LOGGER.info("[Disc Jockey] ✓ HudElement SAM method: {} (paramCount={})",
                        samMethod.getName(), samMethod.getParameterCount());

                // ===== 4) 缓存 Minecraft / Gui / SpectrumRenderer 反射对象 =====
                Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                Object mcInstance = mcClass.getMethod("getInstance").invoke(null);

                Field guiField = mcClass.getDeclaredField("gui");
                guiField.setAccessible(true);
                LOGGER.info("[Disc Jockey] ✓ Cached Minecraft.gui field");

                Field screenField = Class.forName("net.minecraft.client.gui.Gui")
                        .getDeclaredField("screen");
                screenField.setAccessible(true);
                LOGGER.info("[Disc Jockey] ✓ Cached Gui.screen field");

                Class<?> spectrumRendererClass = Class.forName(
                        "semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager");
                LOGGER.info("[Disc Jockey] ✓ Loaded SpectrumRendererManager class");

                
                Class<?> identifierClass = Class.forName("net.minecraft.resources.Identifier");
                Constructor<?> identifierCtor =
                        identifierClass.getDeclaredConstructor(String.class, String.class);
                identifierCtor.setAccessible(true);
                Object spectrumLayer = identifierCtor.newInstance(MOD_ID, "spectrum");
                LOGGER.info("[Disc Jockey] ✓ Created Identifier: {}", spectrumLayer);

                
                Method addLast = hudRegistryClass.getMethod(
                        "addLast", identifierClass, hudElementClass);
                LOGGER.info("[Disc Jockey] ✓ Cached HudElementRegistry.addLast method");

                
                Object hudElement = java.lang.reflect.Proxy.newProxyInstance(
                        fabricClassLoader,
                        new Class<?>[]{hudElementClass},
                        (proxy, method, args) -> {
                            String _mname = method.getName();
                            if (args != null && args.length == 2
                                    && ("extractRenderState".equals(_mname)
                                        || "extract".equals(_mname)
                                        || "render".equals(_mname))) {
                                Object guiGraphicsExtractor = args[0];

                                try {
                                    semmiedev.disc_jockey.Config cfg = configHolder.getConfig();

                                    if (!cfg.spectrumAlwaysVisible) {
                                        Object gui = guiField.get(mcInstance);
                                        Object screen = screenField.get(gui);
                                        if (screen == null || !screen.getClass().getName()
                                                .contains("DiscJockeyScreen")) {
                                            return null;
                                        }
                                    }
                                    if (!SONG_PLAYER.running && !PREVIEWER.running) {
                                        return null;
                                    }

                                    int width = 0, height = 0;
                                    try {
                                        Method gw = guiGraphicsExtractor.getClass()
                                                .getMethod("guiWidth");
                                        Method gh = guiGraphicsExtractor.getClass()
                                                .getMethod("guiHeight");
                                        width = (int) gw.invoke(guiGraphicsExtractor);
                                        height = (int) gh.invoke(guiGraphicsExtractor);
                                    } catch (Exception e1) {
                                        try {
                                            Method getWindow = mcClass.getMethod("getWindow");
                                            Object window = getWindow.invoke(mcInstance);
                                            Class<?> winCls = window.getClass();
                                            try {
                                                width = (int) winCls.getMethod("getScaledWidth")
                                                        .invoke(window);
                                                height = (int) winCls.getMethod("getScaledHeight")
                                                        .invoke(window);
                                            } catch (NoSuchMethodException e2) {
                                                width = (int) winCls.getMethod("getGuiScaledWidth")
                                                        .invoke(window);
                                                height = (int) winCls.getMethod("getGuiScaledHeight")
                                                        .invoke(window);
                                            }
                                        } catch (Exception e3) {
                                            LOGGER.error("[Disc Jockey] Cannot get screen size", e3);
                                            return null;
                                        }
                                    }

                                    int bottomY = height - 75;
                                    LOGGER.debug("[Disc Jockey] HUD rendering: width={}, height={}, bottomY={}",
                                            width, height, bottomY);

                                    Object manager = spectrumRendererClass.getMethod("getCurrent")
                                            .invoke(null);

                                    Method renderMethod = null;
                                    try {
                                        renderMethod = spectrumRendererClass.getMethod(
                                                "render",
                                                guiGraphicsExtractor.getClass(),
                                                int.class, int.class,
                                                float[].class, int.class, int.class);
                                    } catch (NoSuchMethodException e1) {
                                        try {
                                            renderMethod = spectrumRendererClass.getMethod(
                                                    "render", guiGraphicsExtractor.getClass());
                                        } catch (NoSuchMethodException e2) {
                                            LOGGER.error("[Disc Jockey] No render method found on SpectrumRendererManager");
                                        }
                                    }

                                    if (renderMethod != null) {
                                        if (renderMethod.getParameterCount() == 6) {
                                            
                                            renderMethod.invoke(manager,
                                                    guiGraphicsExtractor, width, height,
                                                    SMOOTHER.getSmoothedLevels(), 15, bottomY);
                                        } else if (renderMethod.getParameterCount() == 1) {
                                            renderMethod.invoke(manager, guiGraphicsExtractor);
                                        }
                                        LOGGER.debug("[Disc Jockey] ✅ HUD render completed");
                                    }

                                } catch (Throwable renderEx) {
                                    LOGGER.error("[Disc Jockey] HUD render failed", renderEx);
                                }
                                return null;

                            } else {
                                Class<?> ret = method.getReturnType();
                                if (ret == void.class)      return null;
                                if (ret == int.class)        return 0;
                                if (ret == boolean.class)     return false;
                                if (ret == long.class)       return 0L;
                                if (ret == double.class)     return 0.0;
                                if (ret == float.class)      return 0.0f;
                                return null;
                            }
                        }
                );
                LOGGER.debug("[Disc Jockey] ✓ HudElement Proxy created via Fabric ClassLoader");

                addLast.invoke(null, spectrumLayer, hudElement);
                LOGGER.debug("[Disc Jockey] ✅ 26.2 HUD registered successfully! Spectrum will now render when playing.");
                return;

            } catch (Throwable officialEx) {
                LOGGER.info("[Disc Jockey] 26.3 HUD(legacy fallback) (HudElementRegistry) not available, falling back to legacy Proxy method", officialEx);
            }
        });

        
        
        


        
        KeyMapping openScreenKeyBind = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        MOD_ID + ".key_bind.open_screen",
                        InputConstants.KEY_J,
                        KeyMapping.Category.MISC
                )
        );

        
        muteKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        MOD_ID + ".key_bind.preview_mute",
                        InputConstants.KEY_M,
                        KeyMapping.Category.MISC
                )
        );

        
        loopKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        MOD_ID + ".key_bind.preview_loop",
                        InputConstants.KEY_L,
                        KeyMapping.Category.MISC
                )
        );

        // ========== ✅ 升降调快捷键（[ 降 / ] 升） ==========
        transposeDownKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        MOD_ID + ".key_bind.transpose_down",
                        InputConstants.KEY_LBRACKET, 
                        KeyMapping.Category.MISC
                )
        );

        transposeUpKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        MOD_ID + ".key_bind.transpose_up",
                        InputConstants.KEY_RBRACKET, 
                        KeyMapping.Category.MISC
                )
        );

        
        djKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "🎵", 
                        InputConstants.KEY_B, 
                        KeyMapping.Category.MISC
                )
        );
        pianoKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "🎹", 
                        InputConstants.KEY_P, 
                        KeyMapping.Category.MISC
                )
        );
        

        
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            for (ClientTickEvents.StartLevelTick listener : TICK_LISTENERS) {
                if (listener instanceof Previewer) continue;   
                listener.onStartTick(client.level);
            }
            
            Screen nextScreen;
            while ((nextScreen = pendingScreens.poll()) != null) {
                setScreenCompat(client, nextScreen);
            }
            

            // ✅ 主菜单频谱数据更新（根治"世界外没频谱"）
            
            if (client.level == null) {
                SPECTRUM.tick();
                SMOOTHER.tick();
            }
            if (!sentWelcome) {
                sentWelcome = true;
                client.gui.chatListener().handleSystemMessage(
                        Component.literal("§a[Disc Jockey] §fPress §eJ §fto open Music Console")
                                .withStyle(ChatFormatting.BOLD),
                        false
                );
            }

            if (openScreenKeyBind.consumeClick()) {
                if (SongLoader.loadingSongs) {
                    client.gui.chatListener().handleSystemMessage(
                            Component.translatable(MOD_ID + ".still_loading")
                                    .withStyle(ChatFormatting.RED),
                            false
                    );
                    SongLoader.showToast = true;
                } else {
                    
                    setScreenCompat(client, new semmiedev.disc_jockey.gui.screen.DiscJockeyScreen(null));
                }
            }

            if (muteKey.consumeClick()) {
                Previewer.previewMute = !Previewer.previewMute;
                client.gui.chatListener().handleSystemMessage(
                        Component.literal(
                                "§b[Disc Jockey] §fPreview " +
                                (Previewer.previewMute ? "§cMuted" : "§aUnmuted")
                        ),
                        true
                );
            }

            if (loopKey.consumeClick()) {
                Previewer.loopPreview = !Previewer.loopPreview;
                client.gui.chatListener().handleSystemMessage(
                        Component.literal(
                                "§b[Disc Jockey] §fPreview Loop " +
                                (Previewer.loopPreview ? "§aON" : "§cOFF")
                        ),
                        true
                );
            }

            
            boolean transposed = false;
            int newVal = Main.SONG_PLAYER.transpose;

            
            
            if (transposeDownKey.consumeClick()) {
                newVal = Main.SONG_PLAYER.transpose - 1;
                if (newVal < -24) newVal = -24;
                transposed = true;
            }
            if (transposeUpKey.consumeClick()) {
                newVal = Main.SONG_PLAYER.transpose + 1;
                if (newVal > 24) newVal = 24;
                transposed = true;
            }

            if (transposed) {
                Main.SONG_PLAYER.transpose = newVal;
                if (Main.SONG_PLAYER.song != null) {
                    NoteClamper.buildFoldedNotes(Main.SONG_PLAYER.song, newVal);
                }
                if (Main.SONG_PLAYER.song != null && Main.SONG_PLAYER.running) {
                    Main.SONG_PLAYER.tuner.reset();
                }
                String display = String.format("%+d", newVal);
                if (display.equals("+0")) display = "0";
                client.gui.chatListener().handleSystemMessage(
                        Component.literal(
                                "§b[Disc Jockey] §fTranspose §e" + display
                        ),
                        true
                );
            }
            
        });

        
        ClientTickEvents.START_LEVEL_TICK.register(world -> {
            try {
                if (SONG_PLAYER.running && !mutedByDJ) {
                    muteGameMusic();
                }

                for (ClientTickEvents.StartLevelTick listener : TICK_LISTENERS) {
                    if (listener instanceof Previewer) continue;   
                    listener.onStartTick(world);
                }

                
                if (Previewer.isRunning()) {
                    Previewer.getInstance().onStartTick(world);
                }

                
                AudioLevelCollector.update();
                SPECTRUM.tick();
                
                SMOOTHER.tick();

            } catch (Exception e) {
                LOGGER.error("Tick crashed, stopping safely", e);
                SONG_PLAYER.stop();
                PREVIEWER.stop();
                restoreGameMusic();
            }
        });

        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            
            Screen currentScreen = null;
            try {
                Field guiField = client.getClass().getDeclaredField("gui");
                guiField.setAccessible(true);
                Object gui = guiField.get(client);
                Field screenField = gui.getClass().getDeclaredField("screen");
                screenField.setAccessible(true);
                currentScreen = (Screen) screenField.get(gui);
            } catch (Throwable t) {
                currentScreen = null;
            }

            
            while (djKey.consumeClick()) {
                if (currentScreen == null) {
                    setScreenCompat(client, new semmiedev.disc_jockey.gui.screen.DiscJockeyScreen(null));
                } else if (currentScreen instanceof semmiedev.disc_jockey.gui.screen.DiscJockeyScreen) {
                    ((semmiedev.disc_jockey.gui.screen.DiscJockeyScreen) currentScreen).onClose();
                }
            }

            
            while (pianoKey.consumeClick()) {
                try {
                    Class<?> pianoClass = Class.forName("semmiedev.disc_jockey.gui.screen.PianoKeyboardScreen");
                    java.lang.reflect.Constructor<?> ctor = pianoClass.getConstructor(Screen.class);
                    setScreenCompat(client, (Screen) ctor.newInstance(currentScreen));
                } catch (Throwable t) {
                    LOGGER.warn("无法打开钢琴界面：{}", t.getMessage());
                }
            }

            
            if (currentScreen != null) {
                String cn = currentScreen.getClass().getName();
                if (cn.endsWith(".TitleScreen") || cn.endsWith(".PauseScreen")) {
                    ensureMenuButton(currentScreen);
                }
            }
        });

        
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                Object mc = Minecraft.getInstance();
                if (mc == null) return;

                Object screen = getCurrentScreen(mc);
                if (screen == null) return;

                if (!configHolder.getConfig().spectrumAlwaysVisible) {
                    if (!screen.getClass().getName().contains("DiscJockeyScreen")) {
                        return;
                    }
                    if (!SONG_PLAYER.running && !PREVIEWER.running) {
                        return;
                    }
                }

                
                renderSpectrumStandalone(mc);

            } catch (Throwable t) {
                LOGGER.error("[Disc Jockey] End client tick draw failed", t);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                DiscjockeyCommand.register(dispatcher)
        );

        ClientLoginConnectionEvents.DISCONNECT.register((handler, client) -> {
            PREVIEWER.stop();
            SONG_PLAYER.stop();
            restoreGameMusic();
        });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            restoreGameMusic();
        });
    }

    
    private static Screen getCurrentScreen(Object mc) {
        try {
            Field guiField = mc.getClass().getDeclaredField("gui");
            guiField.setAccessible(true);
            Object gui = guiField.get(mc);
            Field screenField = gui.getClass().getDeclaredField("screen");
            screenField.setAccessible(true);
            return (Screen) screenField.get(gui);
        } catch (Throwable t) {
            return null;
        }
    }

    
    private static void ensureMenuButton(Screen screen) {
        try {
            
            for (Object c : screen.children()) {
                if (c instanceof Button) {
                    Button b = (Button) c;
                    if ("♪".equals(b.getMessage().getString())) {
                        return;
                    }
                }
            }

            Minecraft mc = Minecraft.getInstance();
            int h = getScreenHeight(mc);

            Button djBtn = Button.builder(Component.literal("♪"), btn -> {
                try {
                    java.lang.reflect.Constructor<?> ctor =
                            semmiedev.disc_jockey.gui.screen.DiscJockeyScreen.class.getConstructor(Screen.class);
                    Screen dj = (Screen) ctor.newInstance(screen);
                    setScreenCompat(mc, dj);
                } catch (Throwable t) {
                    LOGGER.warn("打开 DiscJockey 失败：{}", t.getMessage());
                }
            }).bounds(5, h - 25, 20, 20).tooltip(
                    Tooltip.create(
                            Component.translatable("disc_jockey.screen.open_discjockey")
                    )
            ).build();

            
            Method m = Screen.class.getDeclaredMethod("addRenderableWidget",
                    net.minecraft.client.gui.components.events.GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(screen, djBtn);

            LOGGER.info("[DJ] ♪ button ensured on {}", screen.getClass().getSimpleName());
        } catch (Throwable t) {
            LOGGER.warn("Menu button ensure failed: {}", t.getMessage());
        }
    }

    
    private static int getScreenHeight(Object mc) {
        try {
            Method getWindow = mc.getClass().getMethod("getWindow");
            Object window = getWindow.invoke(mc);
            Class<?> winCls = window.getClass();
            for (String mn : new String[]{"getScaledHeight", "getGuiScaledHeight"}) {
                try { return (int) winCls.getMethod(mn).invoke(window); } catch (Throwable ignored) {}
            }
            for (String fn : new String[]{"scaledHeight", "height"}) {
                try {
                    Field f = winCls.getDeclaredField(fn);
                    f.setAccessible(true);
                    return f.getInt(window);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return 480; 
    }

    
    private static void renderSpectrumStandalone(Object mc) {
        try {
            int w = 0, h = 0;
            Method getWindow = mc.getClass().getMethod("getWindow");
            Object window = getWindow.invoke(mc);
            Class<?> winCls = window.getClass();
            for (String mn : new String[]{"getScaledWidth", "getGuiScaledWidth"}) {
                try { w = (int) winCls.getMethod(mn).invoke(window); break; } catch (Throwable ignored) {}
            }
            for (String mn : new String[]{"getScaledHeight", "getGuiScaledHeight"}) {
                try { h = (int) winCls.getMethod(mn).invoke(window); break; } catch (Throwable ignored) {}
            }
            if (w <= 0 || h <= 0) return;

            Class<?> guiGraphicsExtractorClass =
                Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor");
            Constructor<?> ctor =
                guiGraphicsExtractorClass.getDeclaredConstructor(int.class, int.class);
            ctor.setAccessible(true);
            Object guiGraphics = ctor.newInstance(w, h);

            Class<?> spectrumRendererClass =
                Class.forName("semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager");
            Object manager = spectrumRendererClass.getMethod("getCurrent").invoke(null);

            Method renderMethod = spectrumRendererClass.getMethod(
                "render",
                guiGraphicsExtractorClass,
                int.class,
                int.class,
                float[].class,
                int.class,
                int.class
            );

            renderMethod.invoke(manager, guiGraphics, w, h, SMOOTHER.getSmoothedLevels(), 15, h - 55);
        } catch (Throwable ignored) {}
    }

    
    private static void setScreenCompat(Object mc, Screen screen) {
        try {
            mc.getClass().getMethod("setScreen", Screen.class).invoke(mc, screen);
        } catch (Throwable t1) {
            try {
                Object gui = mc.getClass().getDeclaredField("gui").get(mc);
                gui.getClass().getMethod("setScreen", Screen.class).invoke(gui, screen);
            } catch (Throwable t2) {
                try {
                    mc.getClass().getMethod("setScreenAndShow", Screen.class).invoke(mc, screen);
                } catch (Throwable t3) {
                    LOGGER.warn("setScreen 全部失败：{}", t3.getMessage());
                }
            }
        }
    }

    
    public static void setScreenCompatStatic(Object mc, Screen screen) {
        
        //   外接屏/虚拟桌面环境下，若命令在 non-render 线程调用 setScreen，
        //   会触发 fabric-screen-api "screen not correctly initialised"。
        
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> setScreenCompat(mc, screen));
    }

    
    public static void playSafeOnMainMenu(int noteId, float volume) {
        try {
            Minecraft mc = Minecraft.getInstance();
            
            playNoteViaSoundManager(mc, noteId, volume);
        } catch (Throwable t) {
            LOGGER.warn("Main menu play failed (noteId={}): {}", noteId, t.getMessage());
        }
    }

    
    private static void playNoteViaSoundManager(Minecraft mc, int noteId, float volume) {
        try {
            
            float pitch = 0.5f + (noteId / 87.0f) * 1.5f;
            pitch = Math.max(0.5f, Math.min(2.0f, pitch));
            volume = Math.max(0.0f, Math.min(1.0f, volume));

            Object sm = mc.getClass().getMethod("getSoundManager").invoke(mc);

            
            Class<?> locClass = Class.forName("net.minecraft.resources.ResourceLocation");
            Constructor<?> locCtor = locClass.getDeclaredConstructor(String.class, String.class);
            locCtor.setAccessible(true);
            Object loc = locCtor.newInstance("minecraft", "block.note_block.harp");

            Class<?> builtInClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object soundEventReg = builtInClass.getField("SOUND_EVENT").get(null);
            Method getOpt = soundEventReg.getClass().getMethod("get", locClass);
            Object soundEventHolder = getOpt.invoke(soundEventReg, loc);

            if (soundEventHolder == null) {
                LOGGER.warn("SoundEvent note_block.harp not found in registry");
                return;
            }

            
            Class<?> simpleSoundClass = Class.forName("net.minecraft.client.resources.sounds.SimpleSoundInstance");
            Method forUI = simpleSoundClass.getMethod("forUI",
                    soundEventHolder.getClass(),  
                    float.class);
            Object instance = forUI.invoke(null, soundEventHolder, pitch);

            
            sm.getClass().getMethod("play",
                    Class.forName("net.minecraft.client.resources.sounds.SoundInstance"))
                    .invoke(sm, instance);

        } catch (Throwable t) {
            LOGGER.warn("playNoteViaSoundManager failed (noteId={}): {}", noteId, t.getMessage());
        }
    }
}
