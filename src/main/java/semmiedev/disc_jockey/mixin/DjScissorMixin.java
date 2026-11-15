package semmiedev.disc_jockey.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.4-snapshot-8: FrontendRenderPass.enableScissor 对 0 尺寸裁剪区直接抛
 * "Scissor size must be >0, was 0x108"。
 * 成对跳过 GuiRenderer 的 scissor 开关，保持状态机一致；
 * DJ 编辑器自行按 visibleTicks()/visibleRows() 计算可见网格，不依赖框架裁剪。
 */
@Mixin(GuiRenderer.class)
public class DjScissorMixin {
    private static volatile boolean djLogged = false;

    @Inject(method = "enableScissor", at = @At("HEAD"), cancellable = true)
    private void djSkipEnableScissor(CallbackInfo ci) {
        if (!djLogged) {
            djLogged = true;
            System.out.println("[DJ_SCISSOR_MIXIN] active - scissor skipped (unconditional)");
        }
        ci.cancel();
    }

    @Inject(method = "disableScissor", at = @At("HEAD"), cancellable = true, require = 0)
    private void djSkipDisableScissor(CallbackInfo ci) {
        ci.cancel();
    }
}