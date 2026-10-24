package semmiedev.disc_jockey.gui.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.Song;

public class SongDetailScreen extends Screen {
<<<<<<< Updated upstream
    private final Screen parent;
    private final Song song;

    /** 兼容旧调用点 */
    public SongDetailScreen(Song song) { this(null, song); }

    public SongDetailScreen(Screen parent, Song song) {
        super(Component.literal("Song Details"));
        this.parent = parent;
        this.song = song;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int startY = height / 2 - 80;

        String displayName = (song.displayName != null) ? song.displayName : "(unknown)";
        String author = (song.author != null) ? song.author : "Unknown";
        String originalAuthor = (song.originalAuthor != null) ? song.originalAuthor : "Unknown";
        String desc = (song.description != null) ? song.description : "None";
        if (desc.length() > 120) desc = desc.substring(0, 120) + "...";

        int noteCount = (song.notes != null) ? song.notes.length : 0;
        // getLengthInSeconds() 返回 double，不能用 float 接收（会有精度丢失编译错误）
        double seconds;
        try { seconds = song.getLengthInSeconds(); } catch (Throwable t) { seconds = 0d; }

        addRenderableWidget(Button.builder(Component.literal("♪ " + displayName), b -> {})
                .bounds(centerX - 150, startY, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Author: " + author), b -> {})
                .bounds(centerX - 150, startY + 25, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Original: " + originalAuthor), b -> {})
                .bounds(centerX - 150, startY + 50, 300, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Desc: " + desc), b -> {})
                .bounds(centerX - 150, startY + 75, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Length: " + String.format("%.1f s", seconds)), b -> {})
                .bounds(centerX - 150, startY + 100, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Notes: " + noteCount), b -> {})
                .bounds(centerX - 150, startY + 125, 300, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Back"), b -> {
                    if (parent != null) Main.setScreenCompatStatic(minecraft, parent);
                    else minecraft.setScreenAndShow(null);
                }).bounds(centerX - 50, startY + 160, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, 0xCC000000);
    }
}
=======
  private final Song song;
  
  private static final int OVERLAY_COLOR = -1728053248;
  
  private static final int PANEL_COLOR = -267382768;
  
  private static final int ACCENT_COLOR = -11141206;
  
  public SongDetailScreen(Song song) {
    super((Component)Component.literal("Song Details"));
    this.song = song;
  }
  
  protected void init() {
    int centerX = this.width / 2;
    int startY = this.height / 2 - 80;
    String displayName = (this.song.displayName != null) ? this.song.displayName : "(unknown)";
    String author = (this.song.author != null) ? this.song.author : "Unknown";
    String originalAuthor = (this.song.originalAuthor != null) ? this.song.originalAuthor : "Unknown";
    String desc = (this.song.description != null) ? this.song.description : "None";
    if (desc.length() > 120)
      desc = desc.substring(0, 120) + "..."; 
    addRenderableWidget((GuiEventListener)Button.builder((Component)Component.literal("" + displayName), b -> {
          
          }).bounds(centerX - 150, startY, 300, 20).build());
    addRenderableWidget((GuiEventListener)Button.builder((Component)Component.literal("Author: " + author), b -> {
          
          }).bounds(centerX - 150, startY + 25, 300, 20).build());
    addRenderableWidget((GuiEventListener)Button.builder((Component)Component.literal("Original: " + originalAuthor), b -> {
          
          }).bounds(centerX - 150, startY + 50, 300, 20).build());
    addRenderableWidget((GuiEventListener)Button.builder((Component)Component.literal("Desc: " + desc), b -> {
          
          }).bounds(centerX - 150, startY + 75, 300, 20).build());
    addRenderableWidget((GuiEventListener)Button.builder(
          (Component)Component.literal("Length: " + String.format("%.1f s", new Object[] { Double.valueOf(this.song.getLengthInSeconds()) })), b -> {
          
          }).bounds(centerX - 150, startY + 100, 300, 20).build());
    addRenderableWidget((GuiEventListener)Button.builder(
          (Component)Component.literal("Notes: " + this.song.notes.length), b -> {
          
          }).bounds(centerX - 150, startY + 125, 300, 20).build());
    addRenderableWidget((GuiEventListener)Button.builder(
          (Component)Component.literal("Back"), b -> {
            try {
              Main.setScreenCompatStatic(this.minecraft, null);
              return;
            } catch (Throwable throwable) {
              try {
                this.minecraft.gui.setScreen(null);
              } catch (Throwable throwable1) {}
              return;
            } 
          }).bounds(centerX - 50, startY + 160, 100, 20).build());
  }
  
  public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
    context.fill(0, 0, this.width, this.height, -1728053248);
    int panelW = 320;
    int panelH = 200;
    int px = (this.width - panelW) / 2;
    int py = (this.height - panelH) / 2;
    context.fill(px, py, px + panelW, py + panelH, -267382768);
    context.fill(px, py, px + panelW, py + 2, -11141206);
    context.fill(px, py, px + 2, py + panelH, -11141206);
    context.fill(px + panelW - 2, py, px + panelW, py + panelH, -11141206);
    context.fill(px, py + panelH - 2, px + panelW, py + panelH, -11141206);
    super.extractRenderState(context, mouseX, mouseY, delta);
  }
}
>>>>>>> Stashed changes
