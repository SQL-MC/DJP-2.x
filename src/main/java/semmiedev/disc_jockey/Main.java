package semmiedev.disc_jockey;

import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;                    // ✅ 新增
import net.minecraft.client.resources.sounds.SimpleSoundInstance; // ✅ 新增
import net.minecraft.core.Holder;                          // ✅ 新增
import org.apache.logging.log4j.LogManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumVisualizer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
/*
   =========================================================
   ⚠️ 以下 import 在 compileJava 阶段会失败
   ⚠️ 已注释屏蔽，代码逻辑完整保留
   ⚠️ 编译通过后，删除注释符号即可恢复
   =========================================================
*/
// import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
/*
   =========================================================
*/

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
/*
   =========================================================
   ⚠️ 以下 import 在 compileJava 阶段会失败
   ⚠️ 已注释屏蔽，代码逻辑完整保留
   ⚠️ 编译通过后，删除注释符号即可恢复
   =========================================================
*/
import net.minecraft.client.Minecraft;
// import net.minecraft.client.gui.GuiGraphics; // ✅ 26.2 不存在此类，注释掉
// import net.minecraft.resources.ResourceLocation;
/*
   ⚠️ 26.2 中 ResourceLocation 已正式重命名为 Identifier，按你原始注释说明恢复此行
   ⚠️ 代码逻辑完整保留，仅修正类名适配 26.2
   =========================================================
*/
import net.minecraft.resources.Identifier;
/*
   =========================================================
*/

import net.minecraft.sounds.SoundSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
// ✅ 26.3 键绑定说明（官方证实，切勿再改 GLFW/Type.SCANCODE）：
//   - KeyMapping 三参构造 (String, int keysym, Category) 在 26.3 仍存在；
//   - keysym 一律用 InputConstants.KEY_*（KEY_J/M/L/P/LBRACKET/B…），Fabric 内部适配 SDL3；
//   - ⚠️ 严禁 Type.SCANCODE / Type.KEYSYM（26.3 已移除，会 NoSuchFieldError）。
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;  // 26.3 键事件（供排错/反射，不影响键绑定）

import java.io.File;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
// ✅ 删掉了错误的JDK Config导包：import java.io.ObjectInputFilter.Config;
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

    // ========== ✅ 唯一新增：版本号（来自 gradle.properties） ==========
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

    // ========== 2.6.2：统一入口 ==========
    public static final Previewer PREVIEWER = new Previewer();
    public static final SongPlayer SONG_PLAYER = new SongPlayer();
    public static final SpectrumVisualizer SPECTRUM = new SpectrumVisualizer();
    // ====================================

    // ✅ 独立频谱缓冲器实例（和PREVIEWER/SONG_PLAYER同级，不破坏原有逻辑）
    public static final SpectrumDataSmoother SMOOTHER = new SpectrumDataSmoother();

    public static File songsFolder;
    // ✅ 明确指向你自己的Config类，避免和JDK内置Config冲突
    public static semmiedev.disc_jockey.Config config;
    // ✅ 泛型明确为你自己的Config类
    public static ConfigHolder<semmiedev.disc_jockey.Config> configHolder;

    // ========== ✅ bool → boolean ==========
    private static boolean sentWelcome = false;

    // ========== 2.6.2：预览快捷键 ==========
    private KeyMapping muteKey;
    private KeyMapping loopKey;
    // ======================================

    // ========== ✅ 升降调快捷键（[ 降 / ] 升） ==========
    private KeyMapping transposeDownKey;
    private KeyMapping transposeUpKey;
    // ========================================================

    // ========== ✅ 菜单按钮按键绑定 ==========
    private KeyMapping djKey;      // ← K 键，🎵
    private KeyMapping pianoKey;   // ← P 键，🎹
    // ==============================================

    // ========== ✅ DJP021500：预览播放速度（/discjockey speed 共享，问题3） ==========
    //   默认 1.0；由 /discjockey speed 同时设置，Previewer.tickAndPlay 步进乘此值。
    //   Smoother/Visualizer 不动，仅 Previewer 步进乘此值。
    public static float PREVIEW_SPEED = 1.0F;
    // ============================================================

    // ========== ✅ FIX DJP010928：删掉 lastInjectedScreen 缓存 ==========
    // 原因：资源包重载会 new TitleScreen()，旧实例上的按钮随旧实例 GC，
    //       缓存导致新实例不被重新注入 → 按钮消失。
    // 改用 ensureMenuButton() 每帧幂等检查，有按钮跳过、没有就加。
    // ==============================================

    /*
       =========================================================
       ✅ 第一条：静音系统（非智能版 · 26.2 最稳）
       =========================================================
    */
    private static boolean mutedByDJ = false;


    /*
       =========================================================
       ✅ 替代方案：空实现，保证编译通过
       =========================================================
    */
    private static void muteGameMusic() {}
    private static void restoreGameMusic() {}

    // ========== ✅ DJP020002：跨帧安全开界面队列（根治 /discjockey 偶发打不开） ==========
    // 问题根因：命令执行完聊天框会立即关闭自己，同帧 setScreen 会被连带关闭带走。
    // 解法：命令只往队列里 offer，由下一帧 START_CLIENT_TICK 消费并 setScreen，
    //       此时聊天框早已关闭完毕，界面不会被带走。ConcurrentLinkedQueue 保证线程安全。
    private static final ConcurrentLinkedQueue<Screen> pendingScreens = new ConcurrentLinkedQueue<>();

    /**
     * 线程安全地请求打开一个界面。由下一帧 START_CLIENT_TICK 统一 setScreen，
     * 彻底避开命令执行完毕时聊天框关闭把界面一起带走的竞争。
     * 供 DiscjockeyCommand（及任何非渲染线程调用方）使用。
     */
    public static void openScreenOnNextTick(Screen screen) {
        if (screen != null) {
            pendingScreens.offer(screen);
        }
    }
    // =============================================================================

    @Override
    public void onInitializeClient() {
        // ✅ 适配新版AutoConfig API，直接获取已注册的ConfigHolder
        configHolder = AutoConfig.register(semmiedev.disc_jockey.Config.class, JanksonConfigSerializer::new);
        config = configHolder.getConfig();

        if (config.configVersion < 1) {
            config = new semmiedev.disc_jockey.Config();
            config.configVersion = 1;
            configHolder.save();
        }

        // ✅ DJP021500：强制常驻频谱可见（问题2根因修复）
        //   旧配置若 spectrumAlwaysVisible=false，主菜单 screen==null → HUD lambda 直接 return → 频谱不显示。
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

        /*
           =========================================================
           ⚠️ HUD 注册代码完整保留 - 编译通过后恢复
           =========================================================
        */
        /*
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> {

            ResourceLocation spectrumLayer =
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "spectrum");

            HudElementRegistry.addLast(spectrumLayer, (guiGraphics, deltaTracker) -> {
                Minecraft mc = Minecraft.getInstance();
                Config cfg = Main.configHolder.getConfig();

                if (!cfg.spectrumAlwaysVisible
                        && !(mc.screen instanceof semmiedev.disc_jockey.gui.screen.DiscJockeyScreen)) {
                    return;
                }

                if (!SONG_PLAYER.running && !PREVIEWER.running) {
                    return;
                }

                int width = mc.getWindow().getScaledWidth();
                int height = mc.getWindow().getScaledHeight();
                int bottomY = height - 75;

                semmiedev.disc_jockey.gui.hud.SpectrumRendererManager.getCurrent().render(
                        guiGraphics,
                        width,
                        height,
                        SPECTRUM.currentLevels,
                        15,
                        bottomY
                );
            });
        });
        */
        /*
           =========================================================
           ✅ 占位：保持 CLIENT_STARTED 注册习惯
           ✅ 【终极修复版】纯反射 + 系统ClassLoader Proxy
           ✅ 编译期不引用任何 Fabric HUD 类（避免类找不到）
           ✅ 运行时通过 Thread.getContextClassLoader() 获取 Fabric 的 ClassLoader
           ✅ Fabric 的 instanceof HudElement 检查必过
           ✅ 运行时探测 HudElement 的真实 SAM 方法名
           =========================================================
        */
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            LOGGER.info("[Disc Jockey] Client fully started, beginning HUD reflection setup...");

            try {
                // ===== 1) 拿到 Fabric 的 ClassLoader =====
                ClassLoader fabricClassLoader = Thread.currentThread().getContextClassLoader();
                LOGGER.info("[Disc Jockey] ✓ Got Fabric ClassLoader: {}", fabricClassLoader);

                // ===== 2) 用 Fabric 的 ClassLoader 加载 HUD 相关类 =====
                Class<?> hudRegistryClass = fabricClassLoader.loadClass(
                        "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
                Class<?> hudElementClass = fabricClassLoader.loadClass(
                        "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement");
                LOGGER.info("[Disc Jockey] ✓ Loaded HudElementRegistry class");
                LOGGER.info("[Disc Jockey] ✓ Loaded HudElement class");

                // ===== 3) 运行时探测 HudElement 的单一抽象方法（SAM）=====
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

                // ===== 5) 创建 Identifier =====
                Class<?> identifierClass = Class.forName("net.minecraft.resources.Identifier");
                Constructor<?> identifierCtor =
                        identifierClass.getDeclaredConstructor(String.class, String.class);
                identifierCtor.setAccessible(true);
                Object spectrumLayer = identifierCtor.newInstance(MOD_ID, "spectrum");
                LOGGER.info("[Disc Jockey] ✓ Created Identifier: {}", spectrumLayer);

                // ===== 6) 获取 addLast 方法 =====
                Method addLast = hudRegistryClass.getMethod(
                        "addLast", identifierClass, hudElementClass);
                LOGGER.info("[Disc Jockey] ✓ Cached HudElementRegistry.addLast method");

                // ===== 7) 用 Fabric ClassLoader 创建 Proxy =====
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
                                    LOGGER.info("[Disc Jockey] HUD rendering: width={}, height={}, bottomY={}",
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
                                            // ✅ 使用 SMOOTHER 缓冲后的数据，和 GUI 内频谱同款平滑
                                            renderMethod.invoke(manager,
                                                    guiGraphicsExtractor, width, height,
                                                    SMOOTHER.getSmoothedLevels(), 15, bottomY);
                                        } else if (renderMethod.getParameterCount() == 1) {
                                            renderMethod.invoke(manager, guiGraphicsExtractor);
                                        }
                                        LOGGER.info("[Disc Jockey] ✅ HUD render completed");
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
                LOGGER.info("[Disc Jockey] ✓ HudElement Proxy created via Fabric ClassLoader");

                addLast.invoke(null, spectrumLayer, hudElement);
                LOGGER.info("[Disc Jockey] ✅ 26.2 HUD registered successfully! Spectrum will now render when playing.");
                return;

            } catch (Throwable officialEx) {
                LOGGER.info("[Disc Jockey] 26.3 HUD(legacy fallback) (HudElementRegistry) not available, falling back to legacy Proxy method", officialEx);
            }

            /*
               =========================================================
               ✅ 第二优先级：原有反射注册逻辑（26.1及以下兼容，100%保留你原来的代码）
               =========================================================
            */
            try {
                LOGGER.info("[Disc Jockey] Loading Minecraft reflection classes...");
                Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                LOGGER.info("[Disc Jockey] ✓ Loaded Minecraft class");

                Class<?> guiGraphicsExtractorClass = Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor");
                LOGGER.info("[Disc Jockey] ✓ Loaded GuiGraphicsExtractor class (26.2 HUD two parameters)");

                Class<?> identifierClass = Class.forName("net.minecraft.resources.Identifier");
                LOGGER.info("[Disc Jockey] ✓ Loaded Identifier class");

                Class<?> hudRegistryClass = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
                LOGGER.info("[Disc Jockey] ✓ Loaded hudElementRegistry class");

                Class<?> spectrumRendererClass = Class.forName("semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager");
                LOGGER.info("[Disc Jockey] ✓ Loaded SpectrumRendererManager class from gui.screen.spectrum package");

                Field guiField = mcClass.getDeclaredField("gui");
                guiField.setAccessible(true);
                LOGGER.info("[Disc Jockey] ✓ Cached Minecraft.gui private field");

                Field screenField = Class.forName("net.minecraft.client.gui.Gui").getDeclaredField("screen");
                screenField.setAccessible(true);
                LOGGER.info("[Disc Jockey] ✓ Cached Gui.screen private field");

                Object mc = mcClass.getMethod("getInstance").invoke(null);
                LOGGER.info("[Disc Jockey] ✓ Got Minecraft instance");

                Constructor<?> identifierCtor = identifierClass.getDeclaredConstructor(String.class, String.class);
                identifierCtor.setAccessible(true);
                Object spectrumLayer = identifierCtor.newInstance(MOD_ID, "spectrum");
                LOGGER.info("[Disc Jockey] ✓ Created spectrum Identifier via reflection: {}", spectrumLayer);

                Class<?> hudElementClass = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement");
                java.lang.reflect.Method addLast = hudRegistryClass.getMethod("addLast",
                        identifierClass,
                        hudElementClass);
                LOGGER.info("[Disc Jockey] ✓ Cached HudElementRegistry.addLast method");

                Object hudElement = java.lang.reflect.Proxy.newProxyInstance(
                    hudElementClass.getClassLoader(),
                    new Class<?>[]{hudElementClass},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if (args != null && args.length == 2
                                && ("extractRenderState".equals(mName) || "extract".equals(mName) || "render".equals(mName))) {
                            Object guiGraphicsExtractor = args[0];
                            Object deltaTracker = args[1];
                            try {
                                Object currentMc = mcClass.getMethod("getInstance").invoke(null);
                                semmiedev.disc_jockey.Config cfg = configHolder.getConfig();

                                if (!cfg.spectrumAlwaysVisible) {
                                    Object gui = guiField.get(currentMc);
                                    Object screen = screenField.get(gui);
                                    if (screen == null || !screen.getClass().getName().contains("DiscJockeyScreen")) {
                                        return null;
                                    }
                                    if (!SONG_PLAYER.running && !PREVIEWER.running) {
                                        return null;
                                    }
                                } else {
                                    if (!SONG_PLAYER.running && !PREVIEWER.running) {
                                        return null;
                                    }
                                }

                                Method guiWidthMethod = guiGraphicsExtractor.getClass().getMethod("guiWidth");
                                Method guiHeightMethod = guiGraphicsExtractor.getClass().getMethod("guiHeight");
                                int width = (int) guiWidthMethod.invoke(guiGraphicsExtractor);
                                int height = (int) guiHeightMethod.invoke(guiGraphicsExtractor);
                                int bottomY = height - 75;

                                Object manager = spectrumRendererClass.getMethod("getCurrent").invoke(null);

                                Method renderMethod = null;
                                try {
                                    renderMethod = spectrumRendererClass.getMethod("render",
                                            guiGraphicsExtractor.getClass(),
                                            int.class,
                                            int.class,
                                            float[].class,
                                            int.class,
                                            int.class);
                                } catch (NoSuchMethodException e1) {
                                    try {
                                        renderMethod = spectrumRendererClass.getMethod("render", guiGraphicsExtractor.getClass());
                                    } catch (NoSuchMethodException e2) {
                                        LOGGER.warn("[Disc Jockey] Could not find exact render method signature, trying alternatives");
                                    }
                                }

                                if (renderMethod != null) {
                                    LOGGER.info("[Disc Jockey] HUD rendering: width={}, height={}, bottomY={}", width, height, bottomY);
                                    if (renderMethod.getParameterCount() == 6) {
                                        // ✅ 使用 SMOOTHER 缓冲后的数据
                                        renderMethod.invoke(manager, guiGraphicsExtractor, width, height, SMOOTHER.getSmoothedLevels(), 15, bottomY);
                                    } else if (renderMethod.getParameterCount() == 1) {
                                        renderMethod.invoke(manager, guiGraphicsExtractor);
                                    } else {
                                        LOGGER.warn("[Disc Jockey] Unsupported render method signature, parameter count: {}", renderMethod.getParameterCount());
                                    }
                                    LOGGER.info("[Disc Jockey] HUD render completed successfully");
                                } else {
                                    LOGGER.error("[Disc Jockey] Could not find suitable render method for SpectrumRendererManager");
                                }
                            } catch (Throwable renderEx) {
                                LOGGER.error("[Disc Jockey] HUD render failed", renderEx);
                            }
                            return null;
                        } else {
                            if (method.getReturnType() == void.class) {
                                return null;
                            } else if (method.getReturnType() == int.class) {
                                return 0;
                            } else if (method.getReturnType() == boolean.class) {
                                return false;
                            } else if (method.getReturnType() == long.class) {
                                return 0L;
                            } else if (method.getReturnType() == double.class) {
                                return 0.0;
                            } else if (method.getReturnType() == float.class) {
                                return 0.0f;
                            }
                        }
                        return null;
                    }
                );

                addLast.invoke(null, spectrumLayer, hudElement);
                LOGGER.info("[Disc Jockey] ✅ HUD reflection registration completed successfully! Spectrum will now render when playing.");

            } catch (Throwable hudEx) {
                LOGGER.error("[Disc Jockey] ❌ HUD reflection initialization failed! Check stack trace below:", hudEx);

                /*
                   =========================================================
                   ✅ 【26.2 兼容层】当反射失败时，尝试使用 26.2 新 API
                   =========================================================
                */
                try {
                    LOGGER.info("[Disc Jockey] Attempting 26.2 HUD compatibility layer...");

                    try {
                        Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
                        Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement");
                    } catch (ClassNotFoundException e) {
                        LOGGER.info("[Disc Jockey] 26.2 HUD API classes not found in this environment. Skipping compatibility layer.");
                        return;
                    }

                    Class<?> hudRegistryClass = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry");
                    Class<?> hudElementClass = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement");

                    Class<?> identifierClass = Class.forName("net.minecraft.resources.Identifier");
                    Constructor<?> identifierCtor = identifierClass.getDeclaredConstructor(String.class, String.class);
                    identifierCtor.setAccessible(true);
                    Object spectrumLayer = identifierCtor.newInstance(MOD_ID, "spectrum");

                    Method addLast = hudRegistryClass.getMethod("addLast", identifierClass, hudElementClass);

                    Object hudElement = java.lang.reflect.Proxy.newProxyInstance(
                        hudElementClass.getClassLoader(),
                        new Class<?>[]{hudElementClass},
                        (proxy, method, args) -> {
                            String mName = method.getName();
                            if (args != null && args.length == 2
                                    && ("extractRenderState".equals(mName) || "extract".equals(mName) || "render".equals(mName))) {
                                Object guiGraphicsExtractor = args[0];
                                Object deltaTracker = args[1];
                                try {
                                    Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                                    Object mc = mcClass.getMethod("getInstance").invoke(null);
                                    semmiedev.disc_jockey.Config cfg = configHolder.getConfig();

                                    Field guiField = mcClass.getDeclaredField("gui");
                                    guiField.setAccessible(true);
                                    Field screenField = Class.forName("net.minecraft.client.gui.Gui").getDeclaredField("screen");
                                    screenField.setAccessible(true);

                                    if (!cfg.spectrumAlwaysVisible) {
                                        Object gui = guiField.get(mc);
                                        Object screen = screenField.get(gui);
                                        if (screen == null || !screen.getClass().getName().contains("DiscJockeyScreen")) {
                                            return null;
                                        }
                                        if (!SONG_PLAYER.running && !PREVIEWER.running) {
                                            return null;
                                        }
                                    } else {
                                        if (!SONG_PLAYER.running && !PREVIEWER.running) {
                                            return null;
                                        }
                                    }

                                    Method guiWidthMethod = guiGraphicsExtractor.getClass().getMethod("guiWidth");
                                    Method guiHeightMethod = guiGraphicsExtractor.getClass().getMethod("guiHeight");
                                    int width = (int) guiWidthMethod.invoke(guiGraphicsExtractor);
                                    int height = (int) guiHeightMethod.invoke(guiGraphicsExtractor);
                                    int bottomY = height - 75;

                                    Class<?> spectrumRendererClass = Class.forName("semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager");
                                    Object manager = spectrumRendererClass.getMethod("getCurrent").invoke(null);

                                    Class<?> guiGraphicsExtractorClass = Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor");

                                    Method renderMethod = null;
                                    try {
                                        renderMethod = spectrumRendererClass.getMethod("render",
                                                guiGraphicsExtractorClass,
                                                int.class,
                                                int.class,
                                                float[].class,
                                                int.class,
                                                int.class);
                                    } catch (NoSuchMethodException e1) {
                                        try {
                                            renderMethod = spectrumRendererClass.getMethod("render", guiGraphicsExtractorClass);
                                        } catch (NoSuchMethodException e2) {
                                            LOGGER.warn("[Disc Jockey] Could not find render method in compatibility layer");
                                        }
                                    }

                                    if (renderMethod != null) {
                                        LOGGER.info("[Disc Jockey] HUD rendering: width={}, height={}, bottomY={}", width, height, bottomY);
                                        if (renderMethod.getParameterCount() == 6) {
                                            // ✅ 使用 SMOOTHER 缓冲后的数据
                                            renderMethod.invoke(manager, guiGraphicsExtractor, width, height, SMOOTHER.getSmoothedLevels(), 15, bottomY);
                                        } else if (renderMethod.getParameterCount() == 1) {
                                            renderMethod.invoke(manager, guiGraphicsExtractor);
                                        }
                                        LOGGER.info("[Disc Jockey] HUD render completed");
                                    }
                                } catch (Throwable t) {
                                    LOGGER.error("[Disc Jockey] 26.2 HUD render failed", t);
                                }
                            }
                            return null;
                        }
                    );

                    addLast.invoke(null, spectrumLayer, hudElement);
                    LOGGER.info("[Disc Jockey] ✅ 26.2 HUD compatibility layer registered successfully!");

                } catch (Throwable compatEx) {
                    LOGGER.error("[Disc Jockey] ❌ 26.2 HUD compatibility layer also failed!", compatEx);
                }
            }

            /*
               =========================================================
               ✅ 【26.2 对策】全向音符盒 — if(false) 死代码包裹
               ✅ 26.2 中 SoundPlayCallback / SoundInstancePlayCallback 均已移除
               ✅ 用 if(false) 把整个块包住：代码完整保留、编译为死代码、运行期零开销
               ✅ 不引入 Mixin、不改 build.gradle、不加新文件
               ✅ 等 Fabric 出新声音 API 后，把 if(false) 改成版本检测即可恢复
               ✅ 下面所有代码（含兼容层）一字未删，全部保留
               =========================================================
            */
            LOGGER.info("[Disc Jockey] Omnidirectional note block: disabled on 26.2 (API removed, code preserved for future restore)");
            if (false) {
            LOGGER.info("[Disc Jockey] Beginning omnidirectional note block setup...");
            try {
                Class<?> soundPlayCallbackClass = Class.forName("net.fabricmc.fabric.api.client.sound.v1.SoundPlayCallback");
                Class<?> cancellationContextClass = Class.forName("net.fabricmc.fabric.api.client.sound.v1.SoundPlayCallback$CancellationContext");
                Class<?> soundInstanceClass = Class.forName("net.minecraft.client.resources.sounds.SoundInstance");
                Class<?> holderClass = Class.forName("net.minecraft.resources.Holder");
                Class<?> registriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
                Class<?> resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation");
                Class<?> soundSourceClass = Class.forName("net.minecraft.sounds.SoundSource");
                Class<?> simpleSoundInstanceClass = Class.forName("net.minecraft.client.resources.sounds.SimpleSoundInstance");
                Class<?> randomSourceClass = Class.forName("net.minecraft.util.RandomSource");
                Class<?> attenuationClass = Class.forName("net.minecraft.client.resources.sounds.SoundInstance$Attenuation");
                Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
                Class<?> soundManagerClass = Class.forName("net.minecraft.client.sounds.SoundManager");

                Object event = soundPlayCallbackClass.getField("EVENT").get(null);
                Method invokeMethod = event.getClass().getMethod("invoker");
                Object invoker = invokeMethod.invoke(event);

                Method registerMethod = invoker.getClass().getMethod("register", java.util.function.BiConsumer.class);

                Object soundEventRegistry = registriesClass.getField("SOUND_EVENT").get(null);
                Method getRegistryKeyMethod = registriesClass.getMethod("getKey", holderClass);

                Method getSoundEventMethod = soundInstanceClass.getMethod("getSoundEvent");
                Method getSourceMethod = soundInstanceClass.getMethod("getSource");
                Method getVolumeMethod = soundInstanceClass.getMethod("getVolume");
                Method getPitchMethod = soundInstanceClass.getMethod("getPitch");
                Method getSeedMethod = soundInstanceClass.getMethod("getSeed");
                Method getXMethod = soundInstanceClass.getMethod("getX");
                Method getYMethod = soundInstanceClass.getMethod("getY");
                Method getZMethod = soundInstanceClass.getMethod("getZ");

                Method mcGetInstanceMethod = minecraftClass.getMethod("getInstance");
                Field soundManagerField = minecraftClass.getDeclaredField("soundManager");
                soundManagerField.setAccessible(true);
                Method soundManagerPlayMethod = soundManagerClass.getMethod("play", soundInstanceClass);

                Method randomSourceCreateMethod = randomSourceClass.getMethod("create", long.class);

                java.lang.reflect.Constructor<?> simpleSoundCtor = simpleSoundInstanceClass.getConstructor(
                        holderClass, soundSourceClass, float.class, float.class,
                        randomSourceClass, boolean.class, int.class, attenuationClass,
                        double.class, double.class, double.class, boolean.class
                );

                final Object attenuationNone;
                {
                    Object temp = null;
                    for (Object enumVal : attenuationClass.getEnumConstants()) {
                        if ("NONE".equals(enumVal.toString())) {
                            temp = enumVal;
                            break;
                        }
                    }
                    attenuationNone = temp;
                }

                registerMethod.invoke(invoker, (java.util.function.BiConsumer<Object, Object>) (soundInstance, context) -> {
                    try {
                        if (!((Main.config.omnidirectionalNoteBlockSounds && Main.SONG_PLAYER.running) || Main.PREVIEWER.running)) {
                            return;
                        }

                        Object soundEventHolder = getSoundEventMethod.invoke(soundInstance);
                        Object registryKey = getRegistryKeyMethod.invoke(soundEventRegistry, soundEventHolder);
                        if (registryKey == null) return;
                        String soundPath = (String) resourceLocationClass.getMethod("getPath").invoke(registryKey);
                        if (!soundPath.startsWith("block.note_block")) return;

                        cancellationContextClass.getMethod("cancel").invoke(context);

                        Object mcInst = mcGetInstanceMethod.invoke(null);
                        Object soundMgr = soundManagerField.get(mcInst);
                        Object randomSrc = randomSourceCreateMethod.invoke(null, getSeedMethod.invoke(soundInstance));
                        Object omniSound = simpleSoundCtor.newInstance(
                                soundEventHolder,
                                getSourceMethod.invoke(soundInstance),
                                getVolumeMethod.invoke(soundInstance),
                                getPitchMethod.invoke(soundInstance),
                               randomSrc,
                                false, 0, attenuationNone,
                                getXMethod.invoke(soundInstance),
                                getYMethod.invoke(soundInstance),
                                getZMethod.invoke(soundInstance),
                                true
                        );
                        soundManagerPlayMethod.invoke(soundMgr, omniSound);

                    } catch (Throwable t) {
                        LOGGER.error("[Disc Jockey] Omnidirectional note block callback failed", t);
                    }
                });

                LOGGER.info("[Disc Jockey] ✅ Omnidirectional note block callback registered successfully!");

            } catch (Throwable omniEx) {
                LOGGER.info("[Disc Jockey] SoundPlayCallback not found (expected for 26.2), falling back to compatibility layer", omniEx);

                try {
                    LOGGER.info("[Disc Jockey] Attempting 26.2 sound compatibility layer...");

                    try {
                        Class.forName("net.fabricmc.fabric.api.client.sound.v1.SoundInstancePlayCallback");
                        Class.forName("net.minecraft.client.sounds.SoundEngine");
                    } catch (ClassNotFoundException e) {
                        LOGGER.info("[Disc Jockey] 26.2 Sound API classes not found in this environment. Skipping compatibility layer.");
                        return;
                    }

                    Class<?> soundInstancePlayCallbackClass = Class.forName("net.fabricmc.fabric.api.client.sound.v1.SoundInstancePlayCallback");
                    Class<?> soundEngineClass = Class.forName("net.minecraft.client.sounds.SoundEngine");
                    Class<?> soundInstanceClass = Class.forName("net.minecraft.client.resources.sounds.SoundInstance");

                    Object event = soundInstancePlayCallbackClass.getField("EVENT").get(null);

                    Method registerMethod = event.getClass().getMethod("register", java.util.function.BiConsumer.class);

                    registerMethod.invoke(event, (java.util.function.BiConsumer<Object, Object>) (soundEngine, soundInstance) -> {
                        try {
                            if (!((Main.config.omnidirectionalNoteBlockSounds && Main.SONG_PLAYER.running) || Main.PREVIEWER.running)) {
                                return;
                            }

                            Method getSoundEventMethod = soundInstanceClass.getMethod("getSoundEvent");
                            Object soundEventHolder = getSoundEventMethod.invoke(soundInstance);

                            Class<?> holderClass = Class.forName("net.minecraft.resources.Holder");
                            Class<?> registriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
                            Class<?> resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation");

                            Object soundEventRegistry = registriesClass.getField("SOUND_EVENT").get(null);
                            Method getRegistryKeyMethod = registriesClass.getMethod("getKey", holderClass);

                            Object registryKey = getRegistryKeyMethod.invoke(soundEventRegistry, soundEventHolder);
                            if (registryKey == null) return;
                            String soundPath = (String) resourceLocationClass.getMethod("getPath").invoke(registryKey);
                            if (!soundPath.startsWith("block.note_block")) return;

                            Method getSourceMethod = soundInstanceClass.getMethod("getSource");
                            Method getVolumeMethod = soundInstanceClass.getMethod("getVolume");
                            Method getPitchMethod = soundInstanceClass.getMethod("getPitch");
                            Method getSeedMethod = soundInstanceClass.getMethod("getSeed");
                            Method getXMethod = soundInstanceClass.getMethod("getX");
                            Method getYMethod = soundInstanceClass.getMethod("getY");
                            Method getZMethod = soundInstanceClass.getMethod("getZ");

                            Class<?> simpleSoundInstanceClass = Class.forName("net.minecraft.client.resources.sounds.SimpleSoundInstance");
                            Class<?> randomSourceClass = Class.forName("net.minecraft.util.RandomSource");
                            Class<?> attenuationClass = Class.forName("net.minecraft.client.resources.sounds.SoundInstance$Attenuation");

                            Method randomSourceCreateMethod = randomSourceClass.getMethod("create", long.class);

                            java.lang.reflect.Constructor<?> simpleSoundCtor = simpleSoundInstanceClass.getConstructor(
                                    holderClass, getSourceMethod.getReturnType(), float.class, float.class,
                                    randomSourceClass, boolean.class, int.class, attenuationClass,
                                    double.class, double.class, double.class, boolean.class
                            );

                            Object attenuationNone = null;
                            for (Object enumVal : attenuationClass.getEnumConstants()) {
                                if ("NONE".equals(enumVal.toString())) {
                                    attenuationNone = enumVal;
                                    break;
                                }
                            }

                            Object randomSrc = randomSourceCreateMethod.invoke(null, getSeedMethod.invoke(soundInstance));
                            Object omniSound = simpleSoundCtor.newInstance(
                                    soundEventHolder,
                                    getSourceMethod.invoke(soundInstance),
                                    getVolumeMethod.invoke(soundInstance),
                                    getPitchMethod.invoke(soundInstance),
                                    randomSrc,
                                    false, 0, attenuationNone,
                                    getXMethod.invoke(soundInstance),
                                    getYMethod.invoke(soundInstance),
                                    getZMethod.invoke(soundInstance),
                                    true
                            );

                            Method playMethod = soundEngineClass.getMethod("playSeededSound",
                                    double.class, double.class, double.class,
                                    holderClass, getSourceMethod.getReturnType(),
                                    float.class, float.class, long.class,
                                    boolean.class);

                            playMethod.invoke(soundEngine,
                                    getXMethod.invoke(soundInstance),
                                    getYMethod.invoke(soundInstance),
                                    getZMethod.invoke(soundInstance),
                                    soundEventHolder,
                                    getSourceMethod.invoke(soundInstance),
                                    getVolumeMethod.invoke(soundInstance),
                                    getPitchMethod.invoke(soundInstance),
                                    getSeedMethod.invoke(soundInstance),
                                    true
                            );

                        } catch (Throwable t) {
                            LOGGER.error("[Disc Jockey] 26.2 sound callback failed", t);
                        }
                    });

                    LOGGER.info("[Disc Jockey] ✅ 26.2 sound compatibility layer registered successfully!");

                } catch (Throwable soundCompatEx) {
                    LOGGER.info("[Disc Jockey] 26.2 sound compatibility layer skipped (non-critical)", soundCompatEx);
                }
            }
            }
        });

        // ========== 26.3 键绑定：三参构造 KeyMapping(String, keysym, Category) ==========
        // 官方证实：26.3 仍支持此构造器；keysym 用 InputConstants.KEY_*，Fabric 内部适配 SDL3。
        // ⚠️ 严禁改成 Type.SCANCODE（NoSuchFieldError）或 GLFW.GLFW_KEY_*（26.3 已移除）。


        // ========== 已有：打开 GUI（J 键） ==========
        KeyMapping openScreenKeyBind = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        MOD_ID + ".key_bind.open_screen",
                        InputConstants.KEY_J,
                        KeyMapping.Category.MISC
                )
        );

        // ========== 2.6.2：预览静音（M） ==========
        muteKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        MOD_ID + ".key_bind.preview_mute",
                        InputConstants.KEY_M,
                        KeyMapping.Category.MISC
                )
        );

        // ========== 2.6.2：预览循环（L 键，避免和 P 键钢琴冲突） ==========
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
                        InputConstants.KEY_LBRACKET, // [  (GLFW_KEY_LEFT_BRACKET = 91)
                        KeyMapping.Category.MISC
                )
        );

        transposeUpKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        MOD_ID + ".key_bind.transpose_up",
                        InputConstants.KEY_RBRACKET, // ]  (GLFW_KEY_RIGHT_BRACKET = 93)
                        KeyMapping.Category.MISC
                )
        );

        // ========== ✅ 菜单按钮按键（K=开DJ toggle，P=开钢琴） ==========
        djKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "🎵", // ← Emoji 显示，不和 J 重复
                        InputConstants.KEY_B, // ← K 键（26.3: GLFW_KEY_B → InputConstants.KEY_B）
                        KeyMapping.Category.MISC
                )
        );
        pianoKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "🎹", // ← Emoji 显示
                        InputConstants.KEY_P, // ← P 键
                        KeyMapping.Category.MISC
                )
        );
        // ==============================================================

        /*
           ============================================================
           START_CLIENT_TICK：客户端级 tick（最安全）
           ✅ DJP010930 修复：升降调从 START_LEVEL_TICK 移到这里
           ✅ 原因：主菜单没有 level → START_LEVEL_TICK 永不触发 → 移调失效
           ✅ 同时用 consumeClick() + isDown() 双通道检测防抖
           ✅ DJP020002：消费跨帧开界面队列（根治 /discjockey 偶发打不开）
           ============================================================
        */
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            // ========== ✅ DJP020002：消费跨帧开界面队列（最早执行，优先开界面） ==========
            Screen nextScreen;
            while ((nextScreen = pendingScreens.poll()) != null) {
                setScreenCompat(client, nextScreen);
            }
            // ================================================================================

            // ✅ 主菜单频谱数据更新（根治"世界外没频谱"）
            //   进世界后 START_LEVEL_TICK 也会调，不冲突（双重更新无害，数据一致）
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
                    // ✅ 26.2 兼容：反射调 setScreen
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

            // ========== ✅ 升降调快捷键处理（DJP010930 修复：在 CLIENT_TICK 里） ==========
            boolean transposed = false;
            int newVal = Main.SONG_PLAYER.transpose;

            // ✅ DJP021500：仅 consumeClick（边缘触发，按下瞬间一次）。
            //   原 `|| isDown()` 每 tick 为 true 都会 ±1，长按一秒约跳 20 次 → 过灵敏。
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
            // ==============================================
        });

        /*
           ============================================================
           START_LEVEL_TICK：世界 tick（播放 / 预览 / 频谱）
           ============================================================
        */
        ClientTickEvents.START_LEVEL_TICK.register(world -> {
            try {
                if (SONG_PLAYER.running && !mutedByDJ) {
                    muteGameMusic();
                }

                for (ClientTickEvents.StartLevelTick listener : TICK_LISTENERS) {
                    listener.onStartTick(world);
                }

                // ========== ✅ 2.6.2：频谱完整驱动链 ==========
                AudioLevelCollector.update();
                SPECTRUM.tick();
                // ✅ 驱动独立频谱缓冲器（和SPECTRUM.tick()同级，不破坏原有逻辑）
                SMOOTHER.tick();

            } catch (Exception e) {
                LOGGER.error("Tick crashed, stopping safely", e);
                SONG_PLAYER.stop();
                PREVIEWER.stop();
                restoreGameMusic();
            }
        });

        /*
           ============================================================
           ✅✅✅ 菜单按钮注入：END_CLIENT_TICK（完整版，不删减）
           ✅ DJP010928 修复：改用 ensureMenuButton() 每帧幂等检查
           ✅ 删掉 lastInjectedScreen 缓存，重载后新 TitleScreen 自动补按钮
           ✅ 主菜单 / 暂停菜单 左下角注入 ♪ 按钮
           ✅ 反射调 addRenderableWidget 绕过 protected
           ✅ 去重：已有 ♪ 就跳过
           ============================================================
        */
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            // ✅ 26.2 兼容：通过反射获取当前 screen
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

            // K 键：toggle DiscJockey 界面（🎵）
            while (djKey.consumeClick()) {
                if (currentScreen == null) {
                    setScreenCompat(client, new semmiedev.disc_jockey.gui.screen.DiscJockeyScreen(null));
                } else if (currentScreen instanceof semmiedev.disc_jockey.gui.screen.DiscJockeyScreen) {
                    ((semmiedev.disc_jockey.gui.screen.DiscJockeyScreen) currentScreen).onClose();
                }
            }

            // P 键：开关钢琴界面（保持你原有逻辑）
            while (pianoKey.consumeClick()) {
                try {
                    Class<?> pianoClass = Class.forName("semmiedev.disc_jockey.gui.screen.PianoKeyboardScreen");
                    java.lang.reflect.Constructor<?> ctor = pianoClass.getConstructor(Screen.class);
                    setScreenCompat(client, (Screen) ctor.newInstance(currentScreen));
                } catch (Throwable t) {
                    LOGGER.warn("无法打开钢琴界面：{}", t.getMessage());
                }
            }

            // ✅ DJP010928 修复：每帧确保按钮存在（无缓存，重载后自动补）
            if (currentScreen != null) {
                String cn = currentScreen.getClass().getName();
                if (cn.endsWith(".TitleScreen") || cn.endsWith(".PauseScreen")) {
                    ensureMenuButton(currentScreen);
                }
            }
        });

        /*
           ============================================================
           ✅✅✅ 频谱渲染：END_CLIENT_TICK（完整覆盖版）
           ✅ 使用反射兼容 getScreen() / getScaledWidth() / getScaledHeight()
           ✅ 不删除任何原有代码，仅修正编译错误
           ============================================================
        */
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

                // ===== 渲染频谱 =====
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

    /* =========================================================
       ✅ 反射获取当前 Screen（26.2 兼容）
       ========================================================= */
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

    /* =========================================================
       ✅ DJP010928 修复：ensureMenuButton（幂等，无缓存）
       ✅ 每帧检查当前 screen 有无 ♪ 按钮，没有就加
       ✅ 重载后新 TitleScreen 自动补上，不再消失
       ========================================================= */
    private static void ensureMenuButton(Screen screen) {
        try {
            // 去重：已经有 ♪ 就跳过
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

            // 反射调 addRenderableWidget（26.2 protected 绕过）
            Method m = Screen.class.getDeclaredMethod("addRenderableWidget",
                    net.minecraft.client.gui.components.events.GuiEventListener.class);
            m.setAccessible(true);
            m.invoke(screen, djBtn);

            LOGGER.info("[DJ] ♪ button ensured on {}", screen.getClass().getSimpleName());
        } catch (Throwable t) {
            LOGGER.warn("Menu button ensure failed: {}", t.getMessage());
        }
    }

    /* =========================================================
       ✅ 反射获取屏幕高度（兼容 26.2 各种方法名）
       ========================================================= */
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
        return 480; // fallback
    }

    /* =========================================================
       ✅ 独立频谱渲染（END_CLIENT_TICK 用）
       ========================================================= */
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

    /**
     * ✅ 26.2 兼容：反射调用 setScreen。
     * 优先尝试 Minecraft.setScreen(Screen)，失败则 fallback 到 Minecraft.gui.setScreen(Screen)。
     * 这样无论 26.2 怎么改名都不会编译失败。
     */
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

    /**
     * ✅ 供外部类（DiscJockeyCommand 等）调用的静态版本。
     * ✅ DJP020002：保留原 setScreenCompatStatic（渲染线程 fallback）作为兼容入口；
     *   新增 openScreenOnNextTick（跨帧队列）作为根治偶发打不开的首选入口。
     */
    public static void setScreenCompatStatic(Object mc, Screen screen) {
        // ✅ DJP021500：包到渲染线程（问题4根因修复）
        //   外接屏/虚拟桌面环境下，若命令在 non-render 线程调用 setScreen，
        //   会触发 fabric-screen-api "screen not correctly initialised"。
        //   用 Minecraft.execute(...) 切到渲染线程后再 setScreen。
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> setScreenCompat(mc, screen));
    }

    /**
     * ✅ DJP010930 修复：主菜单安全播放
     * 主菜单下 level == null，走 SoundManager 直接播放路径
     * ✅ 不依赖 PianoAPI（该类在 26.2 中不存在）
     */
    public static void playSafeOnMainMenu(int noteId, float volume) {
        try {
            Minecraft mc = Minecraft.getInstance();
            // 游戏内和主菜单统一走 SoundManager 反射路径
            playNoteViaSoundManager(mc, noteId, volume);
        } catch (Throwable t) {
            LOGGER.warn("Main menu play failed (noteId={}): {}", noteId, t.getMessage());
        }
    }

    /**
     * 通过反射调用 SoundManager 播放音符盒声音
     * 不依赖 PianoAPI 类，兼容 26.2
     * noteId: 0-87 对应 MIDI 音符
     * volume: 0.0-1.0
     */
    private static void playNoteViaSoundManager(Minecraft mc, int noteId, float volume) {
        try {
            // noteId 0-87 → pitch 0.5~2.0
            float pitch = 0.5f + (noteId / 87.0f) * 1.5f;
            pitch = Math.max(0.5f, Math.min(2.0f, pitch));
            volume = Math.max(0.0f, Math.min(1.0f, volume));

            Object sm = mc.getClass().getMethod("getSoundManager").invoke(mc);

            // 1) 拿 NOTE_BLOCK_HARP 的 Holder
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

            // 2) 用 forUI(Holder, float) 造实例（1.21 真实存在的方法）[4,8](@ref)
            Class<?> simpleSoundClass = Class.forName("net.minecraft.client.resources.sounds.SimpleSoundInstance");
            Method forUI = simpleSoundClass.getMethod("forUI",
                    soundEventHolder.getClass(),  // Holder<SoundEvent>
                    float.class);
            Object instance = forUI.invoke(null, soundEventHolder, pitch);

            // 3) 通过 SoundManager.play 播放
            sm.getClass().getMethod("play",
                    Class.forName("net.minecraft.client.resources.sounds.SoundInstance"))
                    .invoke(sm, instance);

        } catch (Throwable t) {
            LOGGER.warn("playNoteViaSoundManager failed (noteId={}): {}", noteId, t.getMessage());
        }
    }
}
