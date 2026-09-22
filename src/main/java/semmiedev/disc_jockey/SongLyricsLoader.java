package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ✅ 26.3：为每首歌曲加载同目录下同名 .lrc 歌词文件。
 * <p>
 * 匹配规则：歌曲 "xxx.nbs" → 查找 "xxx.lrc"（去后缀后同名）。
 * 无对应 lrc 时 song.lyrics 保持 null，LyricsPlayer 自动跳过。
 * <p>
 * 线程模型：由 SongLoader.loadSongs() 的子线程调用。文件解析（IO 密集）在
 * 子线程完成，赋值 song.lyrics 切回主线程，避免与 UI 线程竞争。
 */
public final class SongLyricsLoader {

    private SongLyricsLoader() {}

    public static void loadLyrics() {
        List<Song> songs = SongLoader.SONGS;
        if (songs == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        int total = songs.size();
        // 子线程解析
        List<Pair> parsed = new ArrayList<>();
        int loaded = 0, failed = 0;
        for (Song song : songs) {
            if (song == null || song.fileName == null) continue;
            try {
                Path lrc = resolveLyricsPath(song);
                if (lrc != null && Files.isRegularFile(lrc)) {
                    Lyrics lyrics = Lyrics.parse(lrc);
                    if (lyrics != null) {
                        parsed.add(new Pair(song, lyrics));
                        loaded++;
                    }
                }
            } catch (IOException e) {
                failed++;
                Main.LOGGER.warn("[DJ] 歌词解析失败 {}: {}", song.fileName, e.getMessage());
            }
        }

        final int fLoaded = loaded, fFailed = failed;
        // 主线程赋值，线程安全
        mc.execute(() -> {
            for (Pair p : parsed) p.song.lyrics = p.lyrics;
            Main.LOGGER.info("[DJ] 歌词加载完成：成功 {} 首，失败 {} 首，歌曲总数 {}", fLoaded, fFailed, total);
        });
    }

    /**
     * 推导 .lrc 路径。优先级：
     * 1) 按 Song.relativePath 在 songsFolder 下查找
     * 2) 兜底：songsFolder + fileName（去后缀）+ .lrc
     */
    private static Path resolveLyricsPath(Song song) {
        String base = stripExt(song.fileName);
        // 优先：相对路径所在的子目录（支持子目录里的歌）
        if (song.relativePath != null && !song.relativePath.isEmpty()) {
            Path relBase = stripExtPath(song.relativePath);
            Path candidate = Main.songsFolder.toPath().resolve(relBase + ".lrc");
            if (Files.exists(candidate)) return candidate;
        }
        // 兜底：平铺在 songsFolder 下
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