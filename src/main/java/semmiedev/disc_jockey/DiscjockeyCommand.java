package semmiedev.disc_jockey;
// 在已有的 import 区域加上：
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.jetbrains.annotations.Nullable;
import semmiedev.disc_jockey.gui.screen.DiscJockeyScreen;
import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import org.jetbrains.annotations.Nullable;
import semmiedev.disc_jockey.gui.screen.DiscJockeyScreen;
import semmiedev.disc_jockey.gui.screen.spectrum.SpectrumRendererManager;

import java.util.*;

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

        dispatcher.register(
                literal("discjockey")

                        /* ===============================
                           ✅ /discjockey help 介绍（唯一修正）
                           =============================== */
                        .then(literal("help")
                                .executes(ctx -> {
                                    FabricClientCommandSource src = ctx.getSource();

                                    src.sendFeedback(
                                            Component.translatable("disc_jockey.command.help.title")
                                                    .withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD)
                                    );

                                    /* ✅ 唯一改动：传入 Main.VERSION */
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

                                    return 1;
                                })
                        )

                        .executes(ctx -> {
                            if (!isLoading(ctx)) {
                                ctx.getSource().getClient().setScreenAndShow(new DiscJockeyScreen());
                                return 1;
                            }
                            return 0;
                        })

                        /* ===============================
                           ✅ /discjockey clamp
                           =============================== */
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

                        /* ===============================
                           ✅ /discjockey unclamp
                           =============================== */
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

                        /* ===============================
                           ✅ /discjockey clampstatus
                           =============================== */
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

                        /* ===============================
                           ✅ /discjockey fold
                           =============================== */
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
                                            if (Main.SONG_PLAYER.song == null) {
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
                                            if (Main.SONG_PLAYER.song == null) {
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

                        /* ===============================
                           ✅ /discjockey transpose（已扩展 ±24）
                           =============================== */
                        /* ===============================
                           ✅ /discjockey transpose（已扩展 ±24）
                           =============================== */
                        .then(literal("transpose")
                                .then(argument("semitones", StringArgumentType.string())
                                        .suggests((ctx, builder) ->
                                                suggest(Arrays.asList("-24","-12","-6","-3","0","3","6","12","24"), builder))
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
                                                NoteClamper.buildFoldedNotes(Main.SONG_PLAYER.song, Main.SONG_PLAYER.transpose);
                                            }

                                            // ✅ DJP000016：强制 Tuner 重新选歌
                                            // 原因：transpose 后 foldedNotes 变了
                                            //       Tuner 必须重新扫描并用 transposed noteIds 绑定方块
                                            //       否则播放时查 transposed noteId → 旧绑定里没有 → 静音
                                            // 条件：只在正在播放时 reset（静止时无需操作）
                                            if (Main.SONG_PLAYER.song != null && Main.SONG_PLAYER.running) {
                                                Main.SONG_PLAYER.tuner.reset();
                                            }

                                            if (val == 0) {
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.transpose.off")
                                                );
                                            } else {
                                                ctx.getSource().sendFeedback(
                                                        Component.translatable("disc_jockey.transpose.set", val)
                                                );
                                            }
                                            return 1;
                                        })
                                )
                        )

                        /* ===============================
                           ✅ /discjockey spectrum <style>
                           =============================== */
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

                                            // ✅ next 逻辑（唯一新增）
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
                                                // 切换索引
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

                        /* ===============================
                           ✅ 其余命令全部保留（未改动）
                           =============================== */
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
                                            Main.SONG_PLAYER.speed = FloatArgumentType.getFloat(ctx, "speed");
                                            ctx.getSource().sendFeedback(
                                                    Component.translatable("disc_jockey.speed.changed", Main.SONG_PLAYER.speed)
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
        );
    }

    /* ===============================
       ✅ 辅助：设置当前 renderer 索引
       =============================== */
    private static void setCurrentRendererIndex(int index) {
        try {
            var field = SpectrumRendererManager.class.getDeclaredField("currentIndex");
            field.setAccessible(true);
            field.set(null, index);
        } catch (Exception ignored) {}
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