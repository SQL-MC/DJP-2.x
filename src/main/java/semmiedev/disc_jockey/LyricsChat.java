package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;


public final class LyricsChat {
    
    private static final int MAX_LENGTH = 256;

    private static long lastSentAt;

    private LyricsChat() {
    }

    
    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!LyricsDispatch.awaitingSelectorProbe()) return;
            if (!(message.getContents() instanceof TranslatableContents contents)) return;
            if (!"argument.entity.selector.not_allowed".equals(contents.getKey())) return;
            LyricsDispatch.onSelectorRefused();
        });
    }

    public static void send(String text) {
        String message = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (message.isEmpty()) return;
        if (message.length() > MAX_LENGTH) message = message.substring(0, MAX_LENGTH);

        long now = System.currentTimeMillis();
        if (now - lastSentAt < Math.max(100, Main.config.lyricsMinIntervalMs)) return;

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        lastSentAt = now;

        if (Main.config.lyricsOutputToPublic) {
            connection.sendChat(message);
        } else {
            LyricsDispatch.send(connection, message);
        }
    }

    
    public static boolean shouldWarnAboutTargets() {
        
        return LyricsDispatch.playersInRadius() > LyricsDispatch.WARN_TARGET_COUNT;
    }

    public static Component warningText(int targets, int intervalMillis) {
        return Component.translatable(Main.MOD_ID + ".lyrics.dm_warning", targets, Math.max(1, intervalMillis / 1000));
    }
}
