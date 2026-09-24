package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.AbstractMap;


public final class SongLyricsLoader {

    private SongLyricsLoader() {}

    public static void loadLyrics() {
        List<Song> songs = SongLoader.SONGS;
        if (songs == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        int total = songs.size();
        
        List<AbstractMap.SimpleEntry<Song, Lyrics>> parsed = new ArrayList<>();
        int loaded = 0, failed = 0;
        for (Song song : songs) {
            if (song == null || song.fileName == null) continue;
            try {
                Path lrc = resolveLyricsPath(song);
                if (lrc != null && Files.isRegularFile(lrc)) {
                    Lyrics lyrics = Lyrics.parse(lrc);
                    if (lyrics != null) {
                        parsed.add(new AbstractMap.SimpleEntry<>(song, lyrics));;
                        loaded++;
                    }
                }
            } catch (IOException e) {
                failed++;
                Main.LOGGER.warn("[DJ] 歌词解析失败 {}: {}", song.fileName, e.getMessage());
            }
        }

        final int fLoaded = loaded, fFailed = failed;
        
        mc.execute(() -> {
            for (AbstractMap.SimpleEntry<Song, Lyrics> p : parsed) p.getKey().lyrics = p.getValue();
            Main.LOGGER.info("[DJ] 歌词加载完成：成功 {} 首，失败 {} 首，歌曲总数 {}", fLoaded, fFailed, total);
        });
    }

    
    private static Path resolveLyricsPath(Song song) {
        String base = stripExt(song.fileName);
        
        if (song.relativePath != null && !song.relativePath.isEmpty()) {
            Path relBase = stripExtPath(song.relativePath);
            Path candidate = Main.songsFolder.toPath().resolve(relBase + ".lrc");
            if (Files.exists(candidate)) return candidate;
        }
        
        return Main.songsFolder.toPath().resolve(base + ".lrc");
    }

    private static String stripExt(String name) {
        return name == null ? "" : name.replaceAll("(?i)\\.(nbs|mid)$", "");
    }

    private static Path stripExtPath(String rel) {
        return Path.of(rel.replaceAll("(?i)\\.(nbs|mid)$", ""));
    }

    private static final class Pair {
        final Song song;
        final Lyrics lyrics;
        Pair(Song song, Lyrics lyrics) { this.song = song; this.lyrics = lyrics; }
    }
}