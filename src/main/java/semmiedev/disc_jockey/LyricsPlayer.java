package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;


public final class LyricsPlayer implements ClientTickEvents.StartLevelTick {
    
    private static final long BACKWARD_SEEK_MILLIS = 500;
    
    private static final long FORWARD_SKIP_MILLIS = 1500;

    private static Song song;
    private static Lyrics currentLyrics;
    private static int nextIndex;
    private static long lastSongMillis = -1;
    private static long lastOffsetMillis;

    private LyricsPlayer() {
    }

    public static void register() {
        Main.TICK_LISTENERS.add(new LyricsPlayer());
    }

    // ==================== ✅ 2.7.8：播放/预览双源工具方法 ====================

    
    private static Song currentSong() {
        if (Main.SONG_PLAYER.running && Main.SONG_PLAYER.song != null) return Main.SONG_PLAYER.song;
        if (Main.PREVIEWER.isRunning() && Main.PREVIEWER.getSong() != null) return Main.PREVIEWER.getSong();
        return null;
    }

    
    private static long currentSongTimeMillis() {
        if (Main.SONG_PLAYER.running && Main.SONG_PLAYER.song != null) {
            return (long) (Main.SONG_PLAYER.getSongElapsedSeconds() * 1000);
        }
        if (Main.PREVIEWER.isRunning() && Main.PREVIEWER.getSong() != null) {
            return (long) (Main.PREVIEWER.getTick() / 20.0 * 1000);
        }
        return -1;
    }

    
    private static Lyrics currentLyrics() {
        Song s = currentSong();
        return s == null ? null : s.lyrics;
    }

    

    @Override
    public void onStartTick(ClientLevel level) {
        if (level == null) return;
    
    
        long now = System.currentTimeMillis();
        if (now % 3000 < 50) {
            boolean sp = Main.SONG_PLAYER.running;
            boolean pp = Main.PREVIEWER.isRunning();
            Song ps = sp ? Main.SONG_PLAYER.song : null;
            Song pps = pp ? Main.PREVIEWER.getSong() : null;
            Main.LOGGER.info("[DJ-DEBUG] tick: SP_running={}, SP_song={}, PV_running={}, PV_song={}, curSong={}, hasLyrics={}, nextIndex={}",
                    sp, ps == null ? "null" : ps.displayName,
                    pp, pps == null ? "null" : pps.displayName,
                    currentSong() == null ? "null" : currentSong().displayName,
                    hasLyrics(), nextIndex);
        }
        Song playing = currentSong();
        Lyrics playingLyrics = currentLyrics();

        
        
        if (playing != song || playingLyrics != currentLyrics) {
            song = playing;
            currentLyrics = playingLyrics;
            nextIndex = 0;
            lastSongMillis = -1;
            lastOffsetMillis = playingLyrics == null ? 0 : playingLyrics.offsetMillis();
        }

        if (playing == null || playingLyrics == null) {
            nextIndex = 0;
            lastSongMillis = -1;
            return;
        }

        long songMillis = currentSongTimeMillis();

        if (playingLyrics.offsetMillis() != lastOffsetMillis) {
            
            
            
            lastOffsetMillis = playingLyrics.offsetMillis();
            nextIndex = playingLyrics.indexAt(songMillis) + 1;
            lastSongMillis = songMillis;
            return;
        }

        if (lastSongMillis >= 0) {
            long delta = songMillis - lastSongMillis;
            
            //   这种"大幅回退"是切歌（song 引用已变）导致的，上面已经通过
            
            
            
            
            if (delta < -BACKWARD_SEEK_MILLIS) {
                nextIndex = playingLyrics.indexAt(songMillis); 
                lastSongMillis = songMillis;
                return;
            }
            long forwardLimit = (long) (FORWARD_SKIP_MILLIS * Math.max(1.0f, Main.SONG_PLAYER.speed));
            if (delta > forwardLimit) {
                nextIndex = playingLyrics.indexAt(songMillis) + 1;
                lastSongMillis = songMillis;
                return;
            }
        }
        lastSongMillis = songMillis;

        while (nextIndex < playingLyrics.size() && playingLyrics.line(nextIndex).timeMs() <= songMillis) {
            if (Main.config.lyricsChatOutput) LyricsChat.send(playingLyrics.line(nextIndex).text());
            nextIndex++;
        }
    }

    
    public static Lyrics.Line currentLine() {
        
        Song playing = currentSong();
        if (playing == null || playing.lyrics == null) return null;
        int index = playing.lyrics.indexAt(currentSongTimeMillis());
        return index < 0 ? null : playing.lyrics.line(index);
    }

    
    public static Lyrics.Line followingLine() {
        Song playing = currentSong();
        if (playing == null || playing.lyrics == null) return null;
        int index = playing.lyrics.indexAt(currentSongTimeMillis()) + 1;
        return index >= playing.lyrics.size() ? null : playing.lyrics.line(index);
    }

    
    public static boolean hasLyrics() {
        
        Song playing = currentSong();
        return playing != null && playing.lyrics != null;
    }

    
    public static long offsetMillis() {
        Song playing = currentSong();
        return playing == null || playing.lyrics == null ? 0 : playing.lyrics.offsetMillis();
    }

    
    public static List<Lyrics.Line> previewLines() {
        
        Song playing = currentSong();
        if (playing == null || playing.lyrics == null) return List.of();

        Lyrics lyrics = playing.lyrics;
        int index = lyrics.indexAt(currentSongTimeMillis());
        int first = Math.max(0, index);
        List<Lyrics.Line> lines = new ArrayList<>(2);
        if (first < lyrics.size()) lines.add(lyrics.line(first));
        if (first + 1 < lyrics.size()) lines.add(lyrics.line(first + 1));
        return lines;
    }
}