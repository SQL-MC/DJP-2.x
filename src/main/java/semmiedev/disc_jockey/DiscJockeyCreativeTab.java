package semmiedev.disc_jockey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 26.3：FabricCreativeModeTab 不存在，改用 Main 的 DeferredRegister 注册。
 * 本类只保留 KEY / TAB 两个符号供旧代码引用，实际注册动作由 Main.DJ_TAB 完成，
 * 避免同一 ResourceKey 被注册两次导致 "Duplicate registration" 启动崩溃。
 */
public final class DiscJockeyCreativeTab {
    private static final Logger LOGGER = LoggerFactory.getLogger("Disc Jockey/DiscJockeyCreativeTab");

    public static final ResourceKey<CreativeModeTab> KEY = ResourceKey.create(
            BuiltInRegistries.CREATIVE_MODE_TAB.key(),
            Identifier.fromNamespaceAndPath("disc_jockey", "dj_tab"));

    /** 延迟到 Main 注册完成后再取，避免静态初始化顺序问题 */
    public static CreativeModeTab TAB() {
        return Main.DJ_TAB.get();
    }

    public static void register() {
        // NeoForge：注册由 DeferredRegister 完成，这里仅打日志确认
        LOGGER.info("[Disc Jockey] 创造页Tab已由 DeferredRegister 注册, INSTANCE=" + JukeboxProjectorItem.INSTANCE);
    }

    private DiscJockeyCreativeTab() {}
}
