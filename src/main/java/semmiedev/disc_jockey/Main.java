package semmiedev.disc_jockey;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.slf4j.Logger;

import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager;
import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumVisualizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

@Mod(Main.MOD_ID)
public class Main {
    public static final String MOD_ID = "disc_jockey";
    public static final Component NAME = Component.literal("Disc Jockey");

    public static final Logger LOGGER = LogUtils.getLogger();

    /** NeoForge 26.3 无 Fabric 的 ClientTickEvents，改为自定义 tick 接口 */
    public interface TickListener {
        void onStartTick(net.minecraft.client.multiplayer.ClientLevel level);
    }
    public static final List<TickListener> TICK_LISTENERS = new ArrayList<>();

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

    public static final SpectrumDataSmoother SMOOTHER = new SpectrumDataSmoother();

    public static File songsFolder;

    public static semmiedev.disc_jockey.Config config;

    /** 兼容层：保留 getConfig() / save()，内部委托 ModConfigSpec */
    public static final ConfigHolder<semmiedev.disc_jockey.Config> configHolder =
            new ConfigHolder<>(() -> config, () -> semmiedev.disc_jockey.Config.SPEC.save());

    public static final class ConfigHolder<T> {
        private final Supplier<T> getter;
        private final Runnable saver;
        public ConfigHolder(Supplier<T> getter, Runnable saver) {
            this.getter = getter; this.saver = saver;
        }
        public T getConfig() { return getter.get(); }
        public void save() { saver.run(); }
    }

    private static boolean sentWelcome = false;

    private static KeyMapping openScreenKeyBind;
    private static KeyMapping muteKey;
    private static KeyMapping loopKey;
    private static KeyMapping transposeDownKey;
    private static KeyMapping transposeUpKey;
    private static KeyMapping djKey;
    private static KeyMapping pianoKey;

    public static float PREVIEW_SPEED = 1.0F;

    private static boolean mutedByDJ = false;

    /** 26.3 移除了 Minecraft.screen 字段，改为自追踪当前界面 */
    private static Screen lastScreen = null;

    private static double savedMusicVolume = -1D;

    private static void muteGameMusic() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        try {
            var opt = mc.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
            if (opt == null) return;
            if (savedMusicVolume < 0D) savedMusicVolume = (Double) opt.get();
            opt.set(0.0D);
        } catch (Throwable t) {
            LOGGER.warn("muteGameMusic failed: {}", t.getMessage());
        }
    }

    // ✅ 修复1：原此处有一行裸语句 NeoForge.EVENT_BUS.addListener(this::onScreenInit);
    //    类体中不能直接放执行语句 → 编译必挂。已删除，注册挪进构造器。

    /**
     * 主菜单 / 暂停界面入口（唯一来源）。
     * 原 ensureMenuButton 的 "♪" 按钮已删除，避免与此处重复。
     */
    private void onScreenInit(net.neoforged.neoforge.client.event.ScreenEvent.Init.Post event) {
        Screen scr = event.getScreen();
        boolean isTitle = scr instanceof net.minecraft.client.gui.screens.TitleScreen;
        boolean isPause = scr instanceof net.minecraft.client.gui.screens.PauseScreen;
        if (!isTitle && !isPause) return;

        event.addListener(Button.builder(Component.literal("🎵 Disc Jockey"),
                b -> Main.setScreenCompatStatic(net.minecraft.client.Minecraft.getInstance(),
                        new semmiedev.disc_jockey.gui.screen.DiscJockeyScreen(null)))
                .pos(10, 10).size(100, 20).build());
    }

    private static void restoreGameMusic() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || savedMusicVolume < 0D) return;
        try {
            var opt = mc.options.getSoundSourceOptionInstance(SoundSource.MUSIC);
            if (opt == null) return;
            opt.set(savedMusicVolume);
            savedMusicVolume = -1D;
        } catch (Throwable t) {
            LOGGER.warn("restoreGameMusic failed: {}", t.getMessage());
        }
    }

    private static final ConcurrentLinkedQueue<Screen> pendingScreens = new ConcurrentLinkedQueue<>();

    public static void openScreenOnNextTick(Screen screen) {
        if (screen != null) pendingScreens.offer(screen);
    }

    // ==================== 注册（NeoForge DeferredRegister） ====================
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final Supplier<JukeboxProjectorItem> JUKEBOX_PROJECTOR_ITEM =
            ITEMS.registerItem("jukebox_projector",
                    props -> {
                        JukeboxProjectorItem item =
                                new JukeboxProjectorItem(props.stacksTo(16));
                        JukeboxProjectorItem.INSTANCE = item;
                        return item;
                    });
    public static ItemStack JUKEBOX_PROJECTOR_STACK() {
        return new ItemStack(JUKEBOX_PROJECTOR_ITEM.get());
    }
    /** 兼容旧引用名（JukeboxProjectorItem 内部若用 Main.JUKEBOX_PROJECTOR） */
    public static Item JUKEBOX_PROJECTOR() { return JUKEBOX_PROJECTOR_ITEM.get(); }

    public static final Supplier<CreativeModeTab> DJ_TAB = TABS.register("dj_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable(MOD_ID, "jukebox_projector"))
                    .icon(() -> new ItemStack(JUKEBOX_PROJECTOR_ITEM.get()))
                    .displayItems((params, output) ->
                            output.accept(JUKEBOX_PROJECTOR_ITEM.get()))
                    .build());

    public Main(IEventBus bus, ModContainer container) {
        ITEMS.register(bus);
        TABS.register(bus);
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        config = Config.INSTANCE;

        songsFolder = new File(FMLPaths.CONFIGDIR.get().toFile()
                + File.separator + MOD_ID + File.separator + "songs");
        if (!songsFolder.isDirectory()) songsFolder.mkdirs();
        SongLoader.loadSongs();

        bus.addListener((FMLClientSetupEvent e) -> onClientSetup());
        bus.addListener(this::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderGui);
        NeoForge.EVENT_BUS.addListener(this::onClientChat);
        NeoForge.EVENT_BUS.addListener(this::onScreenInit);        // ✅ 修复1：主菜单入口
        NeoForge.EVENT_BUS.addListener(this::onClientDisconnect);  // ✅ 修复3：断线清理

        LyricsChat.register();
    }

    // ==================== 按键注册 ====================
    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        openScreenKeyBind = new KeyMapping(MOD_ID + ".key_bind.open_screen",
                InputConstants.KEY_J, KeyMapping.Category.MISC);
        muteKey = new KeyMapping(MOD_ID + ".key_bind.preview_mute",
                InputConstants.KEY_M, KeyMapping.Category.MISC);
        loopKey = new KeyMapping(MOD_ID + ".key_bind.preview_loop",
                InputConstants.KEY_L, KeyMapping.Category.MISC);
        transposeDownKey = new KeyMapping(MOD_ID + ".key_bind.transpose_down",
                InputConstants.KEY_LBRACKET, KeyMapping.Category.MISC);
        transposeUpKey = new KeyMapping(MOD_ID + ".key_bind.transpose_up",
                InputConstants.KEY_RBRACKET, KeyMapping.Category.MISC);
        djKey = new KeyMapping("🎵", InputConstants.KEY_B, KeyMapping.Category.MISC);
        pianoKey = new KeyMapping("🎹", InputConstants.KEY_P, KeyMapping.Category.MISC);

        event.register(openScreenKeyBind);
        event.register(muteKey);
        event.register(loopKey);
        event.register(transposeDownKey);
        event.register(transposeUpKey);
        event.register(djKey);
        event.register(pianoKey);
    }
    private void onClientSetup() {
        LOGGER.info("[Disc Jockey] Client setup complete (NeoForge 26.3).");
    }

    private void onClientChat(ClientChatReceivedEvent e) {
        // 歌词聊天逻辑已迁到 LyricsChat，这里留空
    }

    // ==================== 客户端 Tick ====================
    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        // --- 世界内 tick ---
        if (client.level != null) {
            try {
                if (SONG_PLAYER.running && !mutedByDJ) muteGameMusic();

                for (TickListener listener : new ArrayList<>(TICK_LISTENERS)) {
                    if (listener instanceof Previewer) continue;
                    listener.onStartTick(client.level);
                }
                // ★ 修复4a：Previewer 已从此块移出（主菜单也要驱动它）
                AudioLevelCollector.update();
            } catch (Exception e) {
                LOGGER.error("Tick crashed, stopping safely", e);
                SONG_PLAYER.stop();
                PREVIEWER.stop();
                restoreGameMusic();
            }
        }

        // ★ 修复4b：Previewer 世界内外都驱动。
        //   主菜单 client.level == null 时传 null → Previewer.playSoundSafe 走
        //   SoundManager 分支（不依赖 level/player）→ 主菜单可预览，
        //   并触发 Main.SPECTRUM.onNotePlayed → 频谱有数据源。
        if (Previewer.isRunning()) {
            try {
                Previewer.getInstance().onStartTick(client.level);
            } catch (Exception e) {
                LOGGER.error("Previewer tick crashed", e);
                Previewer.stop();
            }
        }

        // ✅ 修复2：原写法包在 if (client.level == null) 内 → 世界内从不 tick
        //          → SMOOTHER 永远返回 0 → 频谱不动。改为无条件更新。
        SPECTRUM.tick();
        SMOOTHER.tick();

        // --- 跨帧开界面队列 ---
        Screen nextScreen;
        while ((nextScreen = pendingScreens.poll()) != null) {
            setScreenCompat(client, nextScreen);
        }

        if (!sentWelcome) {
            sentWelcome = true;
            sendSystemMessage(client,
                    Component.literal("§a[Disc Jockey] §fPress §eJ §fto open Music Console"), false);
        }

        if (openScreenKeyBind != null && openScreenKeyBind.consumeClick()) {
            if (SongLoader.loadingSongs) {
                sendSystemMessage(client, Component.translatable(MOD_ID + ".still_loading"), false);
                SongLoader.showToast = true;
            } else {
                setScreenCompat(client, new semmiedev.disc_jockey.gui.screen.DiscJockeyScreen(null));
            }
        }

        if (muteKey != null && muteKey.consumeClick()) {
            Previewer.previewMute = !Previewer.previewMute;
            sendSystemMessage(client, Component.literal("§b[Disc Jockey] §fPreview "
                    + (Previewer.previewMute ? "§cMuted" : "§aUnmuted")), true);
        }

        if (loopKey != null && loopKey.consumeClick()) {
            Previewer.loopPreview = !Previewer.loopPreview;
            sendSystemMessage(client, Component.literal("§b[Disc Jockey] §fPreview Loop "
                    + (Previewer.loopPreview ? "§aON" : "§cOFF")), true);
        }

        boolean transposed = false;
        int newVal = Main.SONG_PLAYER.transpose;
        if (transposeDownKey != null && transposeDownKey.consumeClick()) {
            newVal = Main.SONG_PLAYER.transpose - 1;
            if (newVal < -24) newVal = -24;
            transposed = true;
        }
        if (transposeUpKey != null && transposeUpKey.consumeClick()) {
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
            String display = String.format(java.util.Locale.ROOT, "%+d", newVal);
            if (display.equals("+0")) display = "0";
            sendSystemMessage(client, Component.literal("§b[Disc Jockey] §fTranspose §e" + display), true);
        }

        // --- B / P 快捷键开界面 ---
        Screen currentScreen = getCurrentScreen(client);
        while (djKey != null && djKey.consumeClick()) {
            if (currentScreen == null) {
                setScreenCompat(client, new semmiedev.disc_jockey.gui.screen.DiscJockeyScreen(null));
            } else if (currentScreen instanceof semmiedev.disc_jockey.gui.screen.DiscJockeyScreen) {
                ((semmiedev.disc_jockey.gui.screen.DiscJockeyScreen) currentScreen).onClose();
            }
        }
        while (pianoKey != null && pianoKey.consumeClick()) {
            try {
                Class<?> pianoClass = Class.forName("semmiedev.disc_jockey.gui.screen.PianoKeyboardScreen");
                java.lang.reflect.Constructor<?> ctor = pianoClass.getConstructor(Screen.class);
                setScreenCompat(client, (Screen) ctor.newInstance(currentScreen));
            } catch (Throwable t) {
                LOGGER.warn("无法打开钢琴界面：{}", t.getMessage());
            }
        }

        // ★ 修复6：删除 ensureMenuButton(currentScreen) 调用块。
        //          它与 onScreenInit 的按钮重复，且依赖 lastScreen 追踪不可靠。
    }

    // ==================== HUD：RenderGuiEvent.Post（替代 60 行 Proxy 反射） ====================
    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        Screen screen = getCurrentScreen(mc);
        boolean inDjScreen = screen != null
                && screen.getClass().getName().contains("DiscJockeyScreen");

        // ★ 修复5a：原逻辑主菜单 screen==null 会被第一道守卫直接 return。
        //          改为：始终显示开关打开时 或 处于 DJ 界面内 才画。
        if (!config.spectrumAlwaysVisible && !inDjScreen) return;

        // ★ 修复5b：只在世界内要求"正在播放"；主菜单交给数据源自己决定
        if (mc.level != null && !SONG_PLAYER.running && !Previewer.isRunning()) return;

        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int bottomY = height - 75;

        SpectrumRendererManager.render(graphics, width, height,
                SMOOTHER.getSmoothedLevels(), 15, bottomY);
    }

    // ==================== 命令 ====================
    // 客户端命令：事件在 mod 总线上，源类型为 SharedSuggestionProvider
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        DiscjockeyCommand.register(event.getDispatcher());
    }

    /** 打开配置界面（替代 Fabric 的 AutoConfigClient.getConfigScreen）。 */
    public static Screen openConfigScreen(Screen parent) {
        return new ConfigScreen(parent);
    }

    // ==================== 断线 / 退出 ====================
    @SubscribeEvent
    public void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        PREVIEWER.stop();
        SONG_PLAYER.stop();
        restoreGameMusic();
    }

    // ==================== 辅助方法（26.3 全部直接调用，去掉反射） ====================

    private static Screen getCurrentScreen(Minecraft mc) {
        return lastScreen;
    }

    private static int getScreenHeight(Minecraft mc) {
        try {
            return mc.getWindow().getGuiScaledHeight();
        } catch (Throwable ignored) {
            return 480;
        }
    }

    private static void renderSpectrumStandalone(GuiGraphicsExtractor graphics) {
        try {
            int w = graphics.guiWidth();
            int h = graphics.guiHeight();
            if (w <= 0 || h <= 0) return;
            SpectrumRendererManager.render(graphics, w, h, SMOOTHER.getSmoothedLevels(), 15, h - 55);
        } catch (Throwable ignored) {}
    }

    private static void setScreenCompat(Minecraft mc, Screen screen) {
        if (mc == null) return;
        lastScreen = screen;
        mc.setScreenAndShow(screen);
    }

    public static void setScreenCompatStatic(Object mc, Screen screen) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> setScreenCompat(client, screen));
    }

    /** 26.3：Gui.getChat() 已不存在，统一走 LocalPlayer.sendSystemMessage */
    private static void sendSystemMessage(Minecraft mc, Component msg, boolean overlay) {
        if (mc == null) return;
        if (mc.player != null) {
            mc.player.sendSystemMessage(msg);
        } else {
            LOGGER.info("[DJ] {}", msg.getString());
        }
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

            SoundEvent se = SoundEvents.NOTE_BLOCK_HARP.value();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(
                    se, pitch, volume));
        } catch (Throwable t) {
            LOGGER.warn("playNoteViaSoundManager failed (noteId={}): {}", noteId, t.getMessage());
        }
    }
}