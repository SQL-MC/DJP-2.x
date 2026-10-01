package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class DiscJockeyCreativeTab {

    public static final ResourceKey<CreativeModeTab> KEY = ResourceKey.create(
            BuiltInRegistries.CREATIVE_MODE_TAB.key(),
            Identifier.fromNamespaceAndPath("disc_jockey", "dj_tab"));

    // ⚠️ 关键：用 displayItems 直接塞，不碰 CreativeModeTabEvents
    public static final CreativeModeTab TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, KEY,
            FabricCreativeModeTab.builder()
                    .title(Component.translatable("creativeTab.disc_jockey"))
                    .icon(() -> new ItemStack(JukeboxProjectorItem.INSTANCE))
                    .displayItems((params, output) -> {
                        if (JukeboxProjectorItem.INSTANCE != null) {
                            output.accept(JukeboxProjectorItem.INSTANCE);
                        }
                    })
                    .build());

    // 空壳也行，但保留“触碰TAB”确保静态初始化执行
    public static void register() {
        CreativeModeTab _ = TAB; // 强制触发静态注册
        System.out.println("[Disc Jockey] 创造页Tab已注册, INSTANCE=" + JukeboxProjectorItem.INSTANCE);
    }

    private DiscJockeyCreativeTab() {}
}