package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.*;

public class HotReloadSongs {

    private static WatchService watcher;
    private static Path songsPath;
    private static final AtomicBoolean dirty = new AtomicBoolean(false);

    public static void init(Path path) {
        try {
            songsPath = path;
            watcher = FileSystems.getDefault().newWatchService();
            songsPath.register(watcher,
                    ENTRY_CREATE,
                    ENTRY_DELETE,
                    ENTRY_MODIFY
            );
        } catch (IOException ignored) {
            
        }
    }

    public static void tick() {
        if (watcher == null) return;

        
        if (!dirty.compareAndSet(true, false)) {
            WatchKey key = watcher.poll();
            if (key == null) return;

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.context() instanceof Path p) {
                    String name = p.getFileName().toString().toLowerCase();
                    if (name.endsWith(".mp3") || name.endsWith(".ogg") || name.endsWith(".wav")) {
                        dirty.set(true);
                    }
                }
            }
            key.reset();
        }

        if (dirty.get()) {
            Minecraft.getInstance().execute(() -> {
                SongLoader.loadSongs();
                Minecraft.getInstance().gui.chatListener().handleSystemMessage(
                        Component.literal("§b[Disc Jockey] §f检测到歌曲变动，已刷新列表"),
                        true
                );
            });
        }
    }
}