package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


public final class LyricsDispatch {
    
    public static final int WARN_TARGET_COUNT = 15;

    
    private static final long SELECTOR_PROBE_MILLIS = 3000;

    
    public static final int MAX_COMMAND_BURST = 9;
    
    private static final double COMMANDS_PER_SECOND = 1.0;

    
    private static double availableCommands = -1;
    private static long lastRefillAt = System.currentTimeMillis();

    private static boolean selectorRefused;
    private static long selectorProbeAt = -1;
    private static boolean warnedAboutSelector;

    private LyricsDispatch() {
    }

    
    public static int commandBurst() {
        return Math.max(1, Math.min(MAX_COMMAND_BURST, Main.config.lyricsDmBurst));
    }

    
    public static List<AbstractClientPlayer> nearbyPlayers() {
        List<AbstractClientPlayer> result = playersInRadiusList();
        if (result.isEmpty()) return result;

        Minecraft client = Minecraft.getInstance();
        result.sort(Comparator.comparingDouble(client.player::distanceToSqr));

        
        
        
        int maximum = Math.max(1, Math.min(40, Math.min(Main.config.lyricsDmMaxTargets, commandBurst())));
        return result.size() > maximum ? new ArrayList<>(result.subList(0, maximum)) : result;
    }

    
    public static int playersInRadius() {
        return playersInRadiusList().size();
    }

    
    private static List<AbstractClientPlayer> playersInRadiusList() {
        Minecraft client = Minecraft.getInstance();
        List<AbstractClientPlayer> result = new ArrayList<>();
        if (client.player == null || client.level == null) return result;

        int radius = Math.max(1, Math.min(40, Main.config.lyricsDmRadius));
        double radiusSquared = (double) radius * radius;
        for (AbstractClientPlayer other : client.level.players()) {
            if (other == client.player) continue;
            if (client.player.distanceToSqr(other) <= radiusSquared) result.add(other);
        }
        return result;
    }

    public static void send(ClientPacketListener connection, String message) {
        List<AbstractClientPlayer> targets = nearbyPlayers();
        if (targets.isEmpty()) {
            
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                String name = client.player.getScoreboardName();
                if (name != null && !name.isEmpty()) {
                    String command = Main.config.lyricsCommand;
                    if (command == null || command.isBlank()) command = "msg";
                    connection.sendCommand(command + " " + name + " " + message);
                    return;
                }
            }
            return;   
        }

        String command = Main.config.lyricsCommand;
        if (command == null || command.isBlank()) command = "msg";

        if (useSelector()) {
            Minecraft client = Minecraft.getInstance();
            String self = client.player == null ? "" : client.player.getScoreboardName();
            int radius = Math.max(1, Math.min(40, Main.config.lyricsDmRadius));
            String selector = "@a[distance=.." + radius + (self.isEmpty() ? "" : ",name=!" + self) + "]";
            connection.sendCommand(command + " " + selector + " " + message);
            if (!selectorRefused && selectorProbeAt == -1) selectorProbeAt = System.currentTimeMillis();
            return;
        }

        // Without permission to use selectors vanilla can only address one name per /msg command, so
        
        
        
        if (!takeCommands(targets.size())) return;
        for (AbstractClientPlayer target : targets) {
            connection.sendCommand(command + " " + target.getScoreboardName() + " " + message);
        }
    }

    
    public static boolean useSelector() {
        return Main.config.lyricsUseSelector && !selectorRefused;
    }

    
    public static boolean awaitingSelectorProbe() {
        if (!useSelector() || selectorProbeAt == -1) return false;
        if (System.currentTimeMillis() - selectorProbeAt > SELECTOR_PROBE_MILLIS) {
            
            selectorProbeAt = -1;
            return false;
        }
        return true;
    }

    
    public static void onSelectorRefused() {
        if (selectorRefused) return;
        selectorRefused = true;
        selectorProbeAt = -1;
        if (warnedAboutSelector) return;
        warnedAboutSelector = true;
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable(
                    Main.MOD_ID + ".lyrics.selector_not_allowed"));
        }
    }

    
    private static synchronized boolean takeCommands(int commands) {
        int burst = commandBurst();
        if (availableCommands < 0 || availableCommands > burst) {
            
            
            availableCommands = burst;
            lastRefillAt = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        double refill = (now - lastRefillAt) / 1000.0 * COMMANDS_PER_SECOND;
        if (refill > 0) {
            availableCommands = Math.min(burst, availableCommands + refill);
            lastRefillAt = now;
        }
        if (commands > burst || availableCommands < commands) return false;
        availableCommands -= commands;
        return true;
    }

    
    public static int targetCount() {
        return nearbyPlayers().size();
    }
}
