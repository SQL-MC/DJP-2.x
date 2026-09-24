package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.jetbrains.annotations.Nullable;
import semmiedev.disc_jockey.gui.screen.DiscJockeyScreen;
import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import java.lang.reflect.Field;
import java.util.*;

import java.net.HttpURLConnection;
import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static net.minecraft.commands.SharedSuggestionProvider.suggest;

public class DiscjockeyCommand {

    

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        final List<String> instrumentNames = new ArrayList<>();
        for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
            instrumentNames.add(instrument.toString().toLowerCase());
        }

        final List<String> instrumentNamesAndAll = new ArrayList<>(instrumentNames);
        instrumentNamesAndAll.add("all");

        final List<String> instrumentNamesAndNothing = new ArrayList<>(instrumentNames);
        instrumentNamesAndNothing.add("nothing");

        List<String> spectrumStyles = Arrays.stream(SpectrumRendererManager.Style.values())
                .map(s -> s.name().toLowerCase())
                .toList();

        
        dispatcher.register(literal("discjockey")
                        .executes(ctx -> {
                            if (isLoading(ctx)) return 0;
                            try {
                                Main.openScreenOnNextTick(new DiscJockeyScreen(null));
                                return 1;
                            } catch (Throwable t) {
                                ctx.getSource().sendError(
                                        Component.literal("§c[Disc Jockey] Failed to open GUI: " + t.getMessage())
                                );
                                Main.LOGGER.error("DJP020002: Failed to open DiscJockey GUI from command", t);
                                return 0;
                            }
                        })

                        
                        .then(literal("help")
                                .executes(ctx -> {
                                    FabricClientCommandSource src = ctx.getSource();

                                    src.sendFeedback(
                                            Component.translatable("disc_jockey.command.help.title")
                                                    .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD)
                                    );

                                    src.sendFeedback(
                                            Component.translatable(
                                                    "disc_jockey.command.help.version",
                                                    Main.VERSION
                                            ).withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD)
                                    );

                                    src.sendFeedback(
                                            Component.translatable("disc_jockey.command.help.author")
                                                    .withStyle(net.minecraft.ChatFormatting.GRAY)
                                    );

                                    src.sendFeedback(
                                            Component.translatable("disc_jockey.command.help.cmd")
                                                    .withStyle(net.minecraft.ChatFormatting.YELLOW)
                                    );

                                    src.sendFeedback(
                                            Component.translatable("disc_jockey.command.help.keybind")
                                                    .withStyle(net.minecraft.ChatFormatting.YELLOW)
                                    );

                                    src.sendFeedback(
                                            Component.translatable("disc_jockey.command.help.rightclick")
                                                    .withStyle(net.minecraft.ChatFormatting.YELLOW)
                                    );

                                    src.sendFeedback(
                                            Component.translatable("disc_jockey.command.help.config")
                                                    .withStyle(net.minecraft.ChatFormatting.YELLOW)
                                    );

                                    
                                    src.sendFeedback(
                                            Component.literal("§7[Disc Jockey] §f依赖库 §ePianoLib§f：为 DJP 提供 88 个钢琴音符")
                                                    .withStyle(net.minecraft.ChatFormatting.GRAY)
                                    );
                                    src.sendFeedback(
                                            Component.literal("§7→ §nhttps://www.curseforge.com/minecraft/mc-mods/pianolib")
                                                    .withStyle(net.minecraft.ChatFormatting.GRAY)
                                    );

                                    return 1;
                                })
                        )
                        
                        .then(literal("update")
                                .executes(ctx -> {
                                    checkForUpdates(ctx.getSource());
                                    return 1;
                                })
                        )
                        
                        .then(literal("clamp")
                                .executes(ctx -> {
                                    if (isLoading(ctx)) return 0;
                                    int total = 0, needsFold = 0;
                                    for (Song song : SongLoader.SONGS) {
                                        total++;
                                        if (NoteClamper.checkSong(song) != 0) {
                                            needsFold++;
                                        }
                                    }
                                    if (needsFold == 0) {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.clamp.all_ok", total)
                                        );
                                    } else {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.clamp.needs_fold", total, needsFold)
                                        );
                                    }
                                    return 1;
                                })
                                .then(literal("all")
                                        .executes(ctx -> {
                                            if (isLoading(ctx)) return 0;
                                            int total = 0, folded = 0;
                                            for (Song s : SongLoader.SONGS) {
                                                total++;
                                                if (NoteClamper.checkSong(s) != 0) {
                                                    NoteClamper.buildFoldedNotes(s, Main.SONG_PLAYER.transpose);
                                                    folded++;
                                                }
                                            }
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.clamp.runtime_applied", total, folded)
                                            );
                                            return 1;
                                        })
                                )
                                .then(argument("song", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) ->
                                                suggest(SongLoader.SONG_SUGGESTIONS, builder))
                                        .executes(ctx -> {
                                            if (isLoading(ctx)) return 0;
                                            String name = StringArgumentType.getString(ctx, "song");
                                            Song song = SongLoader.SONGS.stream()
                                                    .filter(s -> s.fileName.equalsIgnoreCase(name)
                                                            || (s.displayName != null && s.displayName.equalsIgnoreCase(name)))
                                                    .findFirst()
                                                    .orElse(null);
                                            if (song == null) {
                                                ctx.getSource().sendError(
                                                        Component.translatable("disc_jockey.clamp.not_found", name)
                                                );
                                                return 0;
                                            }
                                            if (NoteClamper.checkSong(song) == 0) {
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.clamp.no_change", song.displayName)
                                                );
                                            } else {
                                                NoteClamper.buildFoldedNotes(song, Main.SONG_PLAYER.transpose);
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.clamp.runtime_success", song.displayName)
                                                );
                                            }
                                            return 1;
                                        })
                                )
                        )

                        
                        .then(literal("unclamp")
                                .executes(ctx -> {
                                    if (Main.SONG_PLAYER.song == null) {
                                        ctx.getSource().sendError(
                                                Component.translatable("disc_jockey.not_playing")
                                        );
                                        return 0;
                                    }
                                    NoteClamper.clearFoldedNotes(Main.SONG_PLAYER.song);
                                    ctx.getSource().sendFeedback(
                                            Component.translatable("disc_jockey.clamp.cleared", Main.SONG_PLAYER.song.displayName)
                                    );
                                    return 1;
                                })
                        )

                        
                        .then(literal("clampstatus")
                                .executes(ctx -> {
                                    if (Main.SONG_PLAYER.song == null) {
                                        ctx.getSource().sendError(
                                                Component.translatable("disc_jockey.not_playing")
                                        );
                                        return 0;
                                    }
                                    if (NoteClamper.hasFoldedNotes(Main.SONG_PLAYER.song)) {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.clamp.status_active", Main.SONG_PLAYER.song.displayName)
                                        );
                                    } else {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.clamp.status_inactive", Main.SONG_PLAYER.song.displayName)
                                        );
                                    }
                                    return 1;
                                })
                        )

                        
                        .then(literal("fold")
                                .then(literal("info")
                                        .then(argument("song", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) ->
                                                        suggest(SongLoader.SONG_SUGGESTIONS, builder))
                                                .executes(ctx -> {
                                                    if (isLoading(ctx)) return 0;
                                                    String name = StringArgumentType.getString(ctx, "song");
                                                    Song s = SongLoader.SONGS.stream()
                                                            .filter(x -> x.displayName.equalsIgnoreCase(name)
                                                                    || x.fileName.equalsIgnoreCase(name))
                                                            .findFirst()
                                                            .orElse(null);
                                                    if (s == null) {
                                                        ctx.getSource().sendError(
                                                                Component.translatable("disc_jockey.clamp.not_found", name)
                                                        );
                                                        return 0;
                                                    }

                                                    var r = FoldAnalyzer.analyze(s);

                                                    ctx.getSource().sendFeedback(
                                                            Component.literal(
                                                                    "🎼 " + r.songName() + "\n" +
                                                                    "音域: " + r.minNote() + " ~ " + r.maxNote() + "\n" +
                                                                    (r.needsFold()
                                                                            ? "⚠ 需要运行时折叠"
                                                                            : "✅ 音域正常") +
                                                                    (r.severelyOutOfRange()
                                                                            ? "（严重超界）"
                                                                            : "")
                                                            )
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                                .then(literal("scan")
                                        .executes(ctx -> {
                                            if (isLoading(ctx)) return 0;
                                            int need = FoldAnalyzer.countSongsNeedingFold(SongLoader.SONGS);
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.fold.scan_result", need, SongLoader.SONGS.size())
                                            );
                                            return 1;
                                        })
                                )
                                .then(literal("status")
                                        .executes(ctx -> {
                                            if (Main.SONG_PLAYER == null || Main.SONG_PLAYER.song == null) {
                                                ctx.getSource().sendError(
                                                        Component.translatable("disc_jockey.not_playing")
                                                );
                                                return 0;
                                            }
                                            if (NoteClamper.hasFoldedNotes(Main.SONG_PLAYER.song)) {
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.fold.status_active", Main.SONG_PLAYER.song.displayName)
                                                );
                                            } else {
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.fold.status_inactive", Main.SONG_PLAYER.song.displayName)
                                                );
                                            }
                                            return 1;
                                        })
                                )
                                .then(literal("rebuild")
                                        .executes(ctx -> {
                                            if (Main.SONG_PLAYER == null || Main.SONG_PLAYER.song == null) {
                                                ctx.getSource().sendError(
                                                        Component.translatable("disc_jockey.not_playing")
                                                );
                                                return 0;
                                            }
                                            NoteClamper.buildFoldedNotes(Main.SONG_PLAYER.song, Main.SONG_PLAYER.transpose);
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.fold.rebuilt", Main.SONG_PLAYER.song.displayName)
                                            );
                                            return 1;
                                        })
                                )
                                .then(literal("auto")
                                        .executes(ctx -> {
                                            if (isLoading(ctx)) return 0;
                                            int total = 0, folded = 0;
                                            for (Song s : SongLoader.SONGS) {
                                                total++;
                                                if (NoteClamper.checkSong(s) != 0) {
                                                    NoteClamper.buildFoldedNotes(s, Main.SONG_PLAYER.transpose);
                                                    folded++;
                                                }
                                            }
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.clamp.runtime_applied", total, folded)
                                            );
                                            return 1;
                                        })
                                )
                        )

                        
                        .then(literal("transpose")
                                .then(argument("semitones", StringArgumentType.string())
                                        .suggests((ctx, builder) -> suggest(Arrays.asList("-24","-12","-6","-3","0","3","6","12","24"), builder))
                                        .executes(ctx -> {
                                            if (isLoading(ctx)) return 0;
                                            String arg = StringArgumentType.getString(ctx, "semitones");
                                            int val;
                                            try {
                                                val = Integer.parseInt(arg);
                                            } catch (NumberFormatException e) {
                                                ctx.getSource().sendError(
                                                        Component.translatable("disc_jockey.transpose.invalid", arg)
                                                );
                                                return 0;
                                            }
                                            if (val < -24 || val > 24) {
                                                ctx.getSource().sendError(
                                                        Component.translatable("disc_jockey.transpose.range")
                                                );
                                                return 0;
                                            }

                                            Main.SONG_PLAYER.transpose = val;

                                            if (Main.SONG_PLAYER.song != null) {
                                                NoteClamper.buildFoldedNotes(Main.SONG_PLAYER.song, val);
                                            }

                                            if (Main.SONG_PLAYER.song != null && Main.SONG_PLAYER.running) {
                                                Main.SONG_PLAYER.tuner.reset();
                                            }

                                            String d = String.format("%+d", val).replace("+0", "0");
                                            if (val == 0) {
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.transpose.off")
                                                );
                                            } else {
                                                ctx.getSource().sendFeedback(
                                                        Component.literal("§b[Disc Jockey] §fTranspose §e" + d)
                                                );
                                            }

                                            if (Main.SONG_PLAYER.song == null) {
                                                ctx.getSource().sendFeedback(
                                                        Component.literal("§7[Disc Jockey] §fTranspose set. Will apply on next play/preview.")
                                                );
                                            }

                                            return 1;
                                        })
                                )
                        )

                        
                        .then(literal("spectrum")
                                .executes(ctx -> {
                                    Main.SPECTRUM.setEnabled(!Main.SPECTRUM.isEnabled());
                                    ctx.getSource().sendFeedback(
                                            Main.SPECTRUM.isEnabled()
                                                    ? Component.translatable("disc_jockey.spectrum.on")
                                                    : Component.translatable("disc_jockey.spectrum.off")
                                    );
                                    return 1;
                                })
                                .then(argument("style", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                suggest(
                                                        java.util.stream.Stream.concat(
                                                                spectrumStyles.stream(),
                                                                java.util.stream.Stream.of("next")
                                                        ).toList(),
                                                        builder
                                                ))
                                        .executes(ctx -> {
                                            String style = StringArgumentType.getString(ctx, "style").toUpperCase(Locale.ROOT);

                                            if ("NEXT".equals(style)) {
                                                SpectrumRendererManager.next();
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable(
                                                                "disc_jockey.spectrum.style_set",
                                                                SpectrumRendererManager.getCurrentStyle().displayName
                                                        )
                                                );
                                                return 1;
                                            }

                                            try {
                                                SpectrumRendererManager.Style s =
                                                        SpectrumRendererManager.Style.valueOf(style);
                                                for (int i = 0; i < SpectrumRendererManager.Style.values().length; i++) {
                                                    if (SpectrumRendererManager.Style.values()[i] == s) {
                                                        setCurrentRendererIndex(i);
                                                        break;
                                                    }
                                                }
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.spectrum.style_set", s.displayName)
                                                );
                                            } catch (IllegalArgumentException e) {
                                                ctx.getSource().sendError(
                                                        Component.translatable("disc_jockey.spectrum.style_invalid", style)
                                                );
                                            }
                                            return 1;
                                        })
                                )
                        )

                        
                        .then(literal("autoplay")
                                .executes(ctx -> {
                                    Main.SONG_PLAYER.autoPlay = !Main.SONG_PLAYER.autoPlay;
                                    if (Main.SONG_PLAYER.autoPlay) {
                                        Main.SONG_PLAYER.loopSong = false;
                                    }
                                    ctx.getSource().sendFeedback(
                                            Main.SONG_PLAYER.autoPlay
                                                    ? Component.translatable("disc_jockey.autoplay.on")
                                                    : Component.translatable("disc_jockey.autoplay.off")
                                    );
                                    return 1;
                                })
                        )

                        
                        .then(literal("shuffle")
                                .executes(ctx -> {
                                    Main.SONG_PLAYER.shuffle = !Main.SONG_PLAYER.shuffle;
                                    if (Main.SONG_PLAYER.shuffle) {
                                        Main.SONG_PLAYER.autoPlay = true;
                                        Main.SONG_PLAYER.shuffleQueue.clear();
                                    }
                                    ctx.getSource().sendFeedback(
                                            Main.SONG_PLAYER.shuffle
                                                    ? Component.translatable("disc_jockey.shuffle.on")
                                                    : Component.translatable("disc_jockey.shuffle.off")
                                    );
                                    return 1;
                                })
                        )

                        
                        .then(literal("pause")
                                .executes(ctx -> {
                                    if (!Main.SONG_PLAYER.running) {
                                        ctx.getSource().sendError(
                                                Component.translatable("disc_jockey.pause.already_stopped")
                                        );
                                        return 0;
                                    }
                                    Main.SONG_PLAYER.togglePause();
                                    ctx.getSource().sendFeedback(
                                            Main.SONG_PLAYER.paused
                                                    ? Component.translatable("disc_jockey.pause.paused", Main.SONG_PLAYER.song.displayName)
                                                    : Component.translatable("disc_jockey.pause.resumed", Main.SONG_PLAYER.song.displayName)
                                    );
                                    return 1;
                                })
                        )

                        
                        .then(literal("resume")
                                .executes(ctx -> {
                                    if (!Main.SONG_PLAYER.running) {
                                        ctx.getSource().sendError(
                                                Component.translatable("disc_jockey.pause.already_stopped")
                                        );
                                        return 0;
                                    }
                                    if (!Main.SONG_PLAYER.paused) {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.pause.already_playing")
                                        );
                                        return 1;
                                    }
                                    Main.SONG_PLAYER.togglePause();
                                    ctx.getSource().sendFeedback(
                                            Component.translatable("disc_jockey.pause.resumed", Main.SONG_PLAYER.song.displayName)
                                    );
                                    return 1;
                                })
                        )

                        
                        .then(literal("sleep")
                                .then(argument("minutes", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                suggest(Arrays.asList("5","10","15","30","45","60","90","120"), builder))
                                        .executes(ctx -> {
                                            String arg = StringArgumentType.getString(ctx, "minutes");
                                            int mins;
                                            try {
                                                mins = Integer.parseInt(arg);
                                            } catch (NumberFormatException e) {
                                                ctx.getSource().sendError(
                                                        Component.translatable("disc_jockey.invalid_number", arg)
                                                );
                                                return 0;
                                            }
                                            if (mins <= 0) {
                                                Main.SONG_PLAYER.setSleepTimer(0);
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.sleep.cancel")
                                                );
                                            } else {
                                                Main.SONG_PLAYER.setSleepTimer(mins);
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.sleep.set", mins)
                                                );
                                            }
                                            return 1;
                                        })
                                )
                                .executes(ctx -> {
                                    Main.SONG_PLAYER.setSleepTimer(0);
                                    ctx.getSource().sendFeedback(
                                            Component.translatable("disc_jockey.sleep.cancel")
                                    );
                                    return 1;
                                })
                        )

                        
                        .then(literal("reload")
                                .executes(ctx -> {
                                    if (isLoading(ctx)) return 0;
                                    ctx.getSource().sendFeedback(
                                            Component.translatable("disc_jockey.reload")
                                    );
                                    SongLoader.loadSongs();
                                    return 1;
                                })
                        )

                        
                        .then(literal("play")
                                .then(argument("song", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) ->
                                                suggest(SongLoader.SONG_SUGGESTIONS, builder))
                                        .executes(ctx -> {
                                            if (isLoading(ctx)) return 0;
                                            String name = StringArgumentType.getString(ctx, "song");
                                            Optional<Song> song = SongLoader.SONGS.stream()
                                                    .filter(s -> s.displayName.equals(name))
                                                    .findAny();
                                            if (song.isPresent()) {
                                                Main.SONG_PLAYER.start(song.get());
                                                return 1;
                                            }
                                            ctx.getSource().sendError(
                                                    Component.translatable("disc_jockey.song_not_found", name)
                                            );
                                            return 0;
                                        })
                                )
                        )

                        
                        .then(literal("random")
                                .executes(ctx -> {
                                    if (isLoading(ctx) || SongLoader.SONGS.isEmpty()) {
                                        ctx.getSource().sendError(
                                                Component.translatable("disc_jockey.no_songs")
                                        );
                                        return 0;
                                    }
                                    Song song = SongLoader.SONGS.get(new Random().nextInt(SongLoader.SONGS.size()));
                                    Main.SONG_PLAYER.start(song);
                                    return 1;
                                })
                        )

                        
                        .then(literal("stop")
                                .executes(ctx -> {
                                    if (!Main.SONG_PLAYER.running) {
                                        ctx.getSource().sendError(
                                                Component.translatable("disc_jockey.not_playing")
                                        );
                                        return 0;
                                    }
                                    String name = Main.SONG_PLAYER.song.displayName;
                                    Main.SONG_PLAYER.stop();
                                    ctx.getSource().sendFeedback(
                                            Component.translatable("disc_jockey.stopped_playing", name)
                                    );
                                    return 1;
                                })
                        )

                        
                        .then(literal("speed")
                                .then(argument("speed", FloatArgumentType.floatArg(0.0001F, 15.0F))
                                        .suggests((ctx, builder) ->
                                                suggest(Arrays.asList("0.5","0.75","1","1.25","1.5","2"), builder))
                                        .executes(ctx -> {
                                            float sp = FloatArgumentType.getFloat(ctx, "speed");
                                            Main.SONG_PLAYER.speed = sp;
                                            Main.PREVIEW_SPEED = sp;
                                            setPreviewerSpeedCap(sp);
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.speed.changed", sp)
                                            );
                                            return 1;
                                        })
                                )
                        )

                        
                        .then(literal("info")
                                .executes(ctx -> {
                                    if (!Main.SONG_PLAYER.running) {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.info.not_running", Main.SONG_PLAYER.speed)
                                        );
                                        return 0;
                                    }
                                    if (!Main.SONG_PLAYER.tuner.isTuned()) {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.info.tuning", Main.SONG_PLAYER.speed)
                                        );
                                        return 0;
                                    }
                                    if (!Main.SONG_PLAYER.didSongReachEnd) {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable(
                                                        "disc_jockey.info.playing",
                                                        formatTimestamp((int) Main.SONG_PLAYER.getSongElapsedSeconds()),
                                                        formatTimestamp((int) Main.SONG_PLAYER.song.getLengthInSeconds()),
                                                        Main.SONG_PLAYER.song.displayName,
                                                        Main.SONG_PLAYER.speed
                                                )
                                        );
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(
                                            Component.translatable(
                                                    "disc_jockey.info.finished",
                                                    Main.SONG_PLAYER.song.displayName,
                                                    Main.SONG_PLAYER.speed
                                            )
                                    );
                                    return 0;
                                })
                        )

                        
                        .then(literal("remapInstruments")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(
                                            Component.translatable("disc_jockey.instrument.info")
                                    );
                                    return 1;
                                })
                                .then(literal("map")
                                        .then(argument("originalInstrument", StringArgumentType.word())
                                                .suggests((ctx, builder) ->
                                                        suggest(instrumentNamesAndAll, builder))
                                                .then(argument("newInstrument", StringArgumentType.word())
                                                        .suggests((ctx, builder) ->
                                                                suggest(instrumentNamesAndNothing, builder))
                                                        .executes(ctx -> {
                                                            String orig = StringArgumentType.getString(ctx, "originalInstrument");
                                                            String neu = StringArgumentType.getString(ctx, "newInstrument");

                                                            NoteBlockInstrument original = null, newInst = null;
                                                            for (NoteBlockInstrument i : NoteBlockInstrument.values()) {
                                                                if (i.toString().equalsIgnoreCase(orig)) original = i;
                                                                if (i.toString().equalsIgnoreCase(neu)) newInst = i;
                                                            }

                                                            if (original == null && !orig.equalsIgnoreCase("all")) {
                                                                ctx.getSource().sendFeedback(
                                                                        Component.translatable("disc_jockey.instrument.invalid", orig)
                                                                );
                                                                return 0;
                                                            }
                                                            if (newInst == null && !neu.equalsIgnoreCase("nothing")) {
                                                                ctx.getSource().sendFeedback(
                                                                        Component.translatable("disc_jockey.instrument.invalid", neu)
                                                                );
                                                                return 0;
                                                            }

                                                            if (orig.equalsIgnoreCase("all")) {
                                                                for (NoteBlockInstrument i : NoteBlockInstrument.values()) {
                                                                    Main.SONG_PLAYER.tuner.instrumentMap.put(i, newInst);
                                                                }
                                                                ctx.getSource().sendFeedback(
                                                                        Component.translatable("disc_jockey.instrument.mapped_all", neu)
                                                                );
                                                            } else {
                                                                Main.SONG_PLAYER.tuner.instrumentMap.put(original, newInst);
                                                                ctx.getSource().sendFeedback(
                                                                        Component.translatable("disc_jockey.instrument.mapped", orig, neu)
                                                                );
                                                            }
                                                            return 1;
                                                        })
                                                )
                                        )
                                )
                                .then(literal("unmap")
                                        .then(argument("instrument", StringArgumentType.word())
                                                .suggests((ctx, builder) ->
                                                        suggest(instrumentNames, builder))
                                                .executes(ctx -> {
                                                    String inst = StringArgumentType.getString(ctx, "instrument");
                                                    NoteBlockInstrument instrument = null;
                                                    for (NoteBlockInstrument i : NoteBlockInstrument.values()) {
                                                        if (i.toString().equalsIgnoreCase(inst)) {
                                                            instrument = i;
                                                            break;
                                                        }
                                                    }
                                                    if (instrument == null) {
                                                        ctx.getSource().sendFeedback(
                                                                Component.translatable("disc_jockey.instrument.invalid", inst)
                                                        );
                                                        return 0;
                                                    }
                                                    Main.SONG_PLAYER.tuner.instrumentMap.remove(instrument);
                                                    ctx.getSource().sendFeedback(
                                                            Component.translatable("disc_jockey.instrument.unmapped", inst)
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                                .then(literal("show")
                                        .executes(ctx -> {
                                            if (Main.SONG_PLAYER.tuner.instrumentMap.isEmpty()) {
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.instrument.no_mappings")
                                                );
                                                return 1;
                                            }
                                            StringBuilder sb = new StringBuilder();
                                            for (var e : Main.SONG_PLAYER.tuner.instrumentMap.entrySet()) {
                                                if (!sb.isEmpty()) sb.append(", ");
                                                sb.append(e.getKey().toString().toLowerCase())
                                                        .append("->")
                                                        .append(e.getValue() == null
                                                                ? Component.translatable("disc_jockey.instrument.none")
                                                                : e.getValue().toString().toLowerCase());
                                            }
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.instrument.list", sb)
                                            );
                                            return 1;
                                        })
                                )
                                .then(literal("clear")
                                        .executes(ctx -> {
                                            Main.SONG_PLAYER.tuner.instrumentMap.clear();
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.instrument.cleared")
                                            );
                                            return 1;
                                        })
                        )
                        .then(literal("loop")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(
                                            Component.translatable("disc_jockey.loop.status",
                                                    Main.SONG_PLAYER.loopSong ? "yes" : "no")
                                    );
                                    return 1;
                                })
                                .then(literal("yes")
                                        .executes(ctx -> {
                                            Main.SONG_PLAYER.loopSong = true;
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.loop.enabled")
                                            );
                                            return 1;
                                        })
                                )
                                .then(literal("no")
                                        .executes(ctx -> {
                                            Main.SONG_PLAYER.loopSong = false;
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.loop.disabled")
                                            );
                                            return 1;
                                        })
                                )
                        )

                        
                        .then(literal("preview")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(
                                            Component.translatable(
                                                    "disc_jockey.preview.status",
                                                    Previewer.running
                                                            ? Component.translatable("disc_jockey.preview.playing")
                                                            : Component.translatable("disc_jockey.preview.stopped"),
                                                    Previewer.previewMute
                                                            ? Component.translatable("disc_jockey.preview.muted")
                                                            : Component.translatable("disc_jockey.preview.unmuted"),
                                                    String.format("%.2f", Previewer.previewVolume)
                                            )
                                    );
                                    return 1;
                                })
                                .then(literal("stop")
                                        .executes(ctx -> {
                                            Previewer.stop();
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.preview.stopped")
                                            );
                                            return 1;
                                        })
                                )
                        )

                        
                        .then(literal("instrument")
                                .then(argument("name", StringArgumentType.word())
                                      .suggests((ctx, builder) ->
                                              suggest(instrumentNames, builder))
                                      .executes(ctx -> {
                                          String name = StringArgumentType.getString(ctx, "name");
                                          for (NoteBlockInstrument inst : NoteBlockInstrument.values()) {
                                              if (inst.toString().equalsIgnoreCase(name)) {
                                                  var block = Note.INSTRUMENT_BLOCKS.get(inst);
                                                  ctx.getSource().sendFeedback(
                                                          Component.translatable(
                                                                  "disc_jockey.instrument.info_detail",
                                                                  name.toLowerCase(),
                                                                  block == null ? "?" : block.toString().toLowerCase()
                                                          )
                                                  );
                                                  return 1;
                                              }
                                          }
                                          ctx.getSource().sendError(
                                                  Component.translatable("disc_jockey.instrument.invalid", name)
                                          );
                                          return 0;
                                      })
                                )
                        )

                        
                        .then(literal("progress")
                                .executes(ctx -> {
                                    if (!Main.SONG_PLAYER.running || Main.SONG_PLAYER.song == null) {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.progress.not_playing")
                                        );
                                        return 0;
                                    }
                                    int cur = (int) Main.SONG_PLAYER.getSongElapsedSeconds();
                                    int len = (int) Main.SONG_PLAYER.song.getLengthInSeconds();
                                    int percent = (int) ((cur / (float) len) * 100);
                                    ctx.getSource().sendFeedback(
                                            Component.translatable(
                                                    "disc_jockey.progress.text",
                                                    formatTimestamp(cur),
                                                    formatTimestamp(len),
                                                    percent
                                            )
                                    );
                                    return 1;
                                })
                        )

                        
                        .then(literal("now")
                                .executes(ctx -> {
                                    if (Main.SONG_PLAYER.song == null) {
                                        ctx.getSource().sendFeedback(
                                                Component.translatable("disc_jockey.not_playing")
                                        );
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(
                                            Component.translatable("disc_jockey.now_playing", Main.SONG_PLAYER.song.displayName)
                                    );
                                    return 1;
                                })
                        )
        ));
    }

    
    private static void setCurrentRendererIndex(int index) {
        try {
            var field = SpectrumRendererManager.class.getDeclaredField("currentIndex");
            field.setAccessible(true);
            field.set(null, index);
        } catch (Exception ignored) {}
    }

    
    private static void setPreviewerSpeedCap(float speed) {
        try {
            Field f = Previewer.class.getDeclaredField("notesPerFrameCap");
            f.setAccessible(true);
            int cap;
            if (speed <= 0f) cap = 1;
            else if (speed <= 1.0f) cap = 1;
            else cap = (int) Math.ceil(speed * 1.5f);
            f.set(null, cap);
        } catch (Throwable t) {
        }
    }

    
    private static void checkForUpdates(FabricClientCommandSource source) {
        
        String projectId = (Main.config != null && Main.config.modrinthProjectId != null && !Main.config.modrinthProjectId.isEmpty())
                ? Main.config.modrinthProjectId
                : "disc-jockey-plus";

        source.sendFeedback(Component.literal("§7[Disc Jockey] §f正在检查 Modrinth 更新..."));

        CompletableFuture.runAsync(() -> {
            HttpURLConnection conn = null;
            try {
                String apiUrl = "https://api.modrinth.com/v2/project/" + projectId + "/version?version_type=release";
                conn = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int code = conn.getResponseCode();
                if (code == 404) {
                    Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[Disc Jockey] §f未找到 Modrinth 项目（404）：§7" + projectId)));
                    return;
                }
                if (code != 200) {
                    Minecraft.getInstance().execute(() ->
                        source.sendError(Component.literal("§c[Disc Jockey] §f检查更新失败（HTTP " + code + "）。")));
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line);
                }

                // 解析首个 "version_number"（数组 [0] 的那条）
                String json = sb.toString();
                String latest = null;
                int idx = json.indexOf("\"version_number\"");
                if (idx >= 0) {
                    int colon = json.indexOf(':', idx);
                    if (colon > 0) {
                        int q1 = json.indexOf('"', colon);
                        if (q1 > 0) { int q2 = json.indexOf('"', q1 + 1); if (q2 > q1) latest = json.substring(q1 + 1, q2); }
                    }
                }

                final String finalLatest = latest;
                Minecraft.getInstance().execute(() -> {
                    if (finalLatest == null) {
                        source.sendError(Component.literal("§c[Disc Jockey] §f解析 Modrinth 响应失败（未找到 version_number）。"));
                        return;
                    }
                    String cur = stripV(Main.VERSION);
                    String lat = stripV(finalLatest);
                    int cmp = compareSemver(lat, cur);
                    if (cmp > 0) {
                        
                        source.sendFeedback(Component.literal("§e[Disc Jockey] §f发现新版本！§7 当前：§c" + cur + " §7最新：§a" + lat));
                        source.sendFeedback(Component.literal("§7前往下载：§nhttps://modrinth.com/mod/" + projectId));
                    } else if (cmp < 0) {
                        
                        source.sendFeedback(Component.literal("§a[Disc Jockey] §f本地版本（§a" + cur + "§f）已领先于 Modrinth 最新版（§7" + lat + "§f）。"));
                        source.sendFeedback(Component.literal("§7Modrinth 版本可能仍在审核中；Disc Jockey 最新稳定版 / PianoLib 依赖请前往 CurseForge 查看："));
                        source.sendFeedback(Component.literal("§nhttps://www.curseforge.com/minecraft/mc-mods/disc-jockey-plus"));
                        source.sendFeedback(Component.literal("§7PianoLib（DJP 依赖库）：§nhttps://www.curseforge.com/minecraft/mc-mods/pianolib"));
                    } else {
                        
                        source.sendFeedback(Component.literal("§a[Disc Jockey] §f已是最新版本（§a" + cur + "§f）。"));
                    }
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() ->
                    source.sendError(Component.literal("§c[Disc Jockey] §f检查更新出错：§7" + e.getMessage())));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    
    private static String stripV(String v) {
        if (v == null) return "";
        String s = v.strip();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    
    private static int compareSemver(String v1, String v2) {
        String a = v1.split("-")[0];
        String b = v2.split("-")[0];
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int n2 = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (n1 != n2) return n1 - n2;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9].*$", "")); }
        catch (Exception e) { return 0; }
    }

    private static boolean isLoading(CommandContext<FabricClientCommandSource> ctx) {
        if (SongLoader.loadingSongs) {
            ctx.getSource().sendError(
                    Component.translatable("disc_jockey.still_loading")
            );
            SongLoader.showToast = true;
            return true;
        }
        return false;
    }

    private static String padZeroes(int number, int length) {
        StringBuilder sb = new StringBuilder(String.valueOf(number));
        while (sb.length() < length) sb.insert(0, '0');
        return sb.toString();
    }

    private static String formatTimestamp(int seconds) {
        return padZeroes(seconds / 60, 2) + ":" + padZeroes(seconds % 60, 2);
    }
}