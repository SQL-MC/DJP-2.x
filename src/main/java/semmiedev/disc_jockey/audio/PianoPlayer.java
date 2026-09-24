package semmiedev.disc_jockey.audio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;


public final class PianoPlayer {
    
    private static Class<?> clazzResourceLocation;
    private static Method methodFromNamespaceAndPath;
    private static Class<?> clazzSoundEvent;
    private static Field fieldSoundEventEmpty;
    private static Class<?> clazzBuiltInRegistries;
    private static Method methodGetOptional;
    private static Class<?> clazzMinecraft;
    private static Method methodGetInstance;
    private static Method methodGetLevel;
    private static Method methodGetPlayer;
    private static Class<?> clazzLocalPlayer;
    private static Method methodBlockPosition;
    private static Class<?> clazzLevel;
    private static Method methodPlayLocalSound;
    private static Object soundSourceRecords;

    
    private static boolean initialized = false;
    private static boolean available = false;
    private static String diagnostic = "not initialized";
    private static final Map<Integer, Object> soundCache = new HashMap<>();
    private static final String[] NOTE_NAMES = {"a","a#","b","c","c#","d","d#","e","f","f#","g","g#"};

    private PianoPlayer() {}

    
    public static void init() {
        if (initialized) return;
        try {
            
            clazzResourceLocation = Class.forName("net.minecraft.resources.ResourceLocation");
            try {
                methodFromNamespaceAndPath = clazzResourceLocation.getMethod("fromNamespaceAndPath", String.class, String.class);
            } catch (NoSuchMethodException e) {
                methodFromNamespaceAndPath = clazzResourceLocation.getMethod("fromNamespaceAndPath", String.class, String.class);
            }

            
            clazzSoundEvent = Class.forName("net.minecraft.sounds.SoundEvent");
            fieldSoundEventEmpty = clazzSoundEvent.getField("EMPTY");
            fieldSoundEventEmpty.setAccessible(true); 

            
            clazzBuiltInRegistries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            methodGetOptional = clazzBuiltInRegistries.getMethod("getOptional", 
                Class.forName("net.minecraft.core.Registry"), Object.class);

            
            clazzMinecraft = Class.forName("net.minecraft.client.Minecraft");
            methodGetInstance = clazzMinecraft.getMethod("getInstance");
            try {
                methodGetLevel = clazzMinecraft.getMethod("getLevel");
            } catch (NoSuchMethodException e) {
                methodGetLevel = clazzMinecraft.getMethod("level");
            }
            try {
                methodGetPlayer = clazzMinecraft.getMethod("getPlayer");
            } catch (NoSuchMethodException e) {
                methodGetPlayer = clazzMinecraft.getMethod("player");
            }

            
            clazzLocalPlayer = Class.forName("net.minecraft.client.player.LocalPlayer");
            methodBlockPosition = clazzLocalPlayer.getMethod("blockPosition");

            
            clazzLevel = Class.forName("net.minecraft.world.level.Level");
            Method found = null;
            try {
                found = clazzLevel.getMethod("playLocalSound",
                    Class.forName("net.minecraft.core.BlockPos"),
                    clazzSoundEvent,
                    Class.forName("net.minecraft.sounds.SoundSource"),
                    float.class, float.class, boolean.class);
            } catch (NoSuchMethodException ignored) {}
            if (found == null) {
                try {
                    found = clazzLevel.getMethod("playLocalSound",
                        Class.forName("net.minecraft.core.BlockPos"),
                        clazzSoundEvent,
                        Class.forName("net.minecraft.sounds.SoundSource"),
                        float.class, float.class);
                } catch (NoSuchMethodException e) {
                    throw new NoSuchMethodException("playLocalSound not found in Level");
                }
            }
            methodPlayLocalSound = found;

            
            Class<?> clazzSoundSource = Class.forName("net.minecraft.sounds.SoundSource");
            soundSourceRecords = clazzSoundSource.getField("RECORDS").get(null);

            
            scanSounds();

            available = true;
            diagnostic = "OK (found " + soundCache.size() + " sounds, playLocalSound: " 
                + (methodPlayLocalSound.getParameterTypes().length == 6 ? "6-param" : "5-param") + ")";
            System.out.println("[PianoPlayer] ✅ 初始化成功: " + diagnostic);
        } catch (Throwable t) {
            available = false;
            diagnostic = t.getClass().getSimpleName() + ": " + t.getMessage();
            System.out.println("[PianoPlayer] ❌ 初始化失败: " + diagnostic);
            t.printStackTrace();
        } finally {
            initialized = true;
        }
    }

    
    private static void scanSounds() {
        soundCache.clear();
        try {
            Object registry = clazzBuiltInRegistries.getField("SOUND_EVENT").get(null);
            
            for (int octave = 0; octave <= 8; octave++) {
                for (int i = 0; i < 12; i++) {
                    String name = NOTE_NAMES[i] + octave;
                    int noteId = nameToNoteId(name);
                    if (noteId < -39 || noteId > 48) continue;
                    Object rl = methodFromNamespaceAndPath.invoke(null, "disc_jockey", "piano." + name);
                    Object optional = methodGetOptional.invoke(null, registry, rl);
                    if ((boolean) optional.getClass().getMethod("isPresent").invoke(optional)) {
                        soundCache.put(noteId, optional.getClass().getMethod("get").invoke(optional));
                    }
                }
            }
            
            for (int key = 0; key <= 87; key++) {
                int noteId = key - 39; 
                if (noteId < -39 || noteId > 48 || soundCache.containsKey(noteId)) continue;
                Object rl = methodFromNamespaceAndPath.invoke(null, "pianolib", "key_" + key);
                Object optional = methodGetOptional.invoke(null, registry, rl);
                if ((boolean) optional.getClass().getMethod("isPresent").invoke(optional)) {
                    soundCache.put(noteId, optional.getClass().getMethod("get").invoke(optional));
                }
            }
        } catch (Throwable t) {
            System.out.println("[PianoPlayer] ⚠ 音效扫描失败: " + t.getMessage());
        }
        System.out.println("[PianoPlayer] 扫描到 " + soundCache.size() + " 个有效钢琴音效");
    }

    
    private static int nameToNoteId(String name) {
        int oct = 0;
        int i = 0;
        while (i < name.length() && !Character.isDigit(name.charAt(i))) i++;
        if (i < name.length()) oct = Integer.parseInt(name.substring(i));
        String notePart = name.substring(0, i).toLowerCase();
        int noteIdx = 0;
        for (int n = 0; n < 12; n++) {
            if (NOTE_NAMES[n].equals(notePart)) {
                noteIdx = n;
                break;
            }
        }
        int midi = oct * 12 + noteIdx + 21; 
        return midi - 60; 
    }

    
    public static boolean play(int noteId) {
        if (!initialized) init();
        if (!available) {
            System.out.println("[PianoPlayer] ⚠ 不可用: " + diagnostic);
            return false;
        }
        noteId = Math.max(-39, Math.min(48, noteId));
        Object evt = soundCache.get(noteId);
        if (evt == null) {
            System.out.println("[PianoPlayer] ⚠ 无对应音效: noteId=" + noteId + " (" + noteIdToLabel(noteId) + ")");
            return false;
        }
        
        
        try {
            if (evt == fieldSoundEventEmpty.get(null)) {
                System.out.println("[PianoPlayer] ⚠ 音效为EMPTY: noteId=" + noteId + " (" + noteIdToLabel(noteId) + ")");
                return false;
            }
        } catch (IllegalAccessException e) {
            System.out.println("[PianoPlayer] ⚠ 无法访问SoundEvent.EMPTY: " + e.getMessage());
            
        }
        
        try {
            Object mc = methodGetInstance.invoke(null);
            Object level = methodGetLevel.invoke(mc);
            if (level == null) return false;
            Object player = methodGetPlayer.invoke(mc);
            if (player == null) return false;
            Object pos = methodBlockPosition.invoke(player);

            
            Class<?>[] params = methodPlayLocalSound.getParameterTypes();
            if (params.length == 6 && params[5] == boolean.class) {
                methodPlayLocalSound.invoke(level, pos, evt, soundSourceRecords, 2.0f, 1.0f, true);
            } else {
                methodPlayLocalSound.invoke(level, pos, evt, soundSourceRecords, 2.0f, 1.0f);
            }
            System.out.println("[PianoPlayer] ✅ 播放成功: noteId=" + noteId + " (" + noteIdToLabel(noteId) + ")");
            return true;
        } catch (Throwable t) {
            System.out.println("[PianoPlayer] ⚠ 播放失败: " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    
    public static String noteIdToLabel(int noteId) {
        int shifted = noteId + 39; 
        int idx = ((shifted % 12) + 12) % 12;
        int midi = shifted + 21;
        int oct = (midi / 12) - 1; 
        return NOTE_NAMES[idx].toUpperCase() + oct;
    }

    public static boolean isAvailable() {
        if (!initialized) init();
        return available;
    }

    public static String getDiagnostic() {
        if (!initialized) init();
        return diagnostic;
    }
}