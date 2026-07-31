package semmiedev.disc_jockey;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.autoconfig.AutoConfigClient;
import net.minecraft.client.gui.screens.Screen;

/**
 * 原始可用版本的ModMenu集成实现
 * 完全匹配DJPlus 2.6.2-rc的API调用逻辑
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // 和你反编译出来的字节码100%一致，强转Screen是必须的
        return parent -> (Screen) AutoConfigClient.getConfigScreen(Config.class, parent).get();
    }
}