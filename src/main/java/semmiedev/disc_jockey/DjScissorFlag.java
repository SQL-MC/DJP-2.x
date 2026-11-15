package semmiedev.disc_jockey;

/** 编辑器打开期间置 true；mixin 据此跳过 26.4-snapshot-8 的 0 尺寸 scissor。 */
public final class DjScissorFlag {
    private DjScissorFlag() {}
    public static volatile boolean ACTIVE = false;
}