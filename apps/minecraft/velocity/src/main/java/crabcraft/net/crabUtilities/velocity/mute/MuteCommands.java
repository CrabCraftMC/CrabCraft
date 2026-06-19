package crabcraft.net.crabUtilities.velocity.mute;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.messaging.PlayerLookup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Optional;
import java.util.UUID;

/**
 * Brigadier commands for the mute system: {@code /mute}, {@code /unmute},
 * {@code /muteinfo}. Permission node: {@code crabutilities.mute}.
 */
public final class MuteCommands {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String PERMISSION = "crabutilities.mute";
    private static final String EXEMPT_PERMISSION = "crabutilities.mute.exempt";

    private MuteCommands() {}

    public static void register(CrabUtilitiesVelocity plugin) {
        registerMute(plugin);
        registerUnmute(plugin);
        registerMuteInfo(plugin);
    }

    /** Resolves an online player by name/nickname, else an offline UUID by username. */
    private static Optional<UUID> resolveUuid(CrabUtilitiesVelocity plugin, String name) {
        Optional<Player> online = PlayerLookup.resolve(plugin, name);
        if (online.isPresent()) return Optional.of(online.get().getUniqueId());
        return plugin.getMuteService().lookupUuidByUsername(name);
    }

    private static void registerMute(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("mute")
                .requires(src -> src.hasPermission(PERMISSION))
                .then(BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.word())
                        .suggests(PlayerLookup.playerSuggestions(plugin))
                        .executes(ctx -> doMute(plugin, ctx.getSource(),
                                ctx.getArgument("target", String.class), null, null))
                        .then(BrigadierCommand.requiredArgumentBuilder("duration", StringArgumentType.word())
                                .executes(ctx -> doMute(plugin, ctx.getSource(),
                                        ctx.getArgument("target", String.class),
                                        ctx.getArgument("duration", String.class), null))
                                .then(BrigadierCommand.requiredArgumentBuilder("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> doMute(plugin, ctx.getSource(),
                                                ctx.getArgument("target", String.class),
                                                ctx.getArgument("duration", String.class),
                                                ctx.getArgument("reason", String.class)))
                                )
                        )
                )
                .executes(ctx -> {
                    ctx.getSource().sendMessage(
                            Component.text("Usage: /mute <player> [duration] [reason]", NamedTextColor.RED));
                    return 0;
                })
                .build();
        registerCommand(plugin, node);
    }

    private static int doMute(CrabUtilitiesVelocity plugin, CommandSource source,
                              String targetName, String durationArg, String reason) {
        Optional<UUID> targetUuid = resolveUuid(plugin, targetName);
        if (targetUuid.isEmpty()) {
            source.sendMessage(MINI.deserialize("<red>Player not found."));
            return 0;
        }
        UUID uuid = targetUuid.get();

        Optional<Player> online = PlayerLookup.resolve(plugin, targetName);
        if (online.isPresent() && online.get().hasPermission(EXEMPT_PERMISSION)) {
            source.sendMessage(MINI.deserialize("<red>That player is exempt from being muted."));
            return 0;
        }

        long durationMillis = 0L;
        if (durationArg != null) {
            try {
                durationMillis = DurationUtil.parse(durationArg);
            } catch (IllegalArgumentException e) {
                source.sendMessage(MINI.deserialize(
                        "<red>Invalid duration '<dur>'. Use e.g. 30m, 2h, 7d, 1w.",
                        Placeholder.unparsed("dur", durationArg)));
                return 0;
            }
        }

        String mutedBy = source instanceof Player p ? p.getUsername() : "Console";
        plugin.getMuteService().mute(uuid, durationMillis, reason, mutedBy);

        String durText = durationMillis <= 0 ? "permanently" : "for " + DurationUtil.humanize(durationMillis);
        String reasonSuffix = reason != null && !reason.isBlank() ? " (" + reason + ")" : "";
        source.sendMessage(MINI.deserialize(
                "<green>Muted <yellow><target></yellow> <dur><reason>.",
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("dur", durText),
                Placeholder.unparsed("reason", reasonSuffix)));

        online.ifPresent(player -> player.sendMessage(MINI.deserialize(
                "<red>You have been muted <span><reason>.",
                Placeholder.unparsed("span", durText),
                Placeholder.unparsed("reason", reasonSuffix))));
        return 1;
    }

    private static void registerUnmute(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("unmute")
                .requires(src -> src.hasPermission(PERMISSION))
                .then(BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.word())
                        .suggests(PlayerLookup.playerSuggestions(plugin))
                        .executes(ctx -> {
                            CommandSource source = ctx.getSource();
                            String targetName = ctx.getArgument("target", String.class);
                            Optional<UUID> targetUuid = resolveUuid(plugin, targetName);
                            if (targetUuid.isEmpty()) {
                                source.sendMessage(MINI.deserialize("<red>Player not found."));
                                return 0;
                            }
                            UUID uuid = targetUuid.get();
                            if (plugin.getMuteService().getMute(uuid) == null) {
                                source.sendMessage(MINI.deserialize(
                                        "<yellow><target></yellow> <red>is not muted.",
                                        Placeholder.unparsed("target", targetName)));
                                return 0;
                            }
                            plugin.getMuteService().unmute(uuid);
                            source.sendMessage(MINI.deserialize(
                                    "<green>Unmuted <yellow><target></yellow>.",
                                    Placeholder.unparsed("target", targetName)));
                            PlayerLookup.resolve(plugin, targetName).ifPresent(player ->
                                    player.sendMessage(MINI.deserialize("<green>You have been unmuted.")));
                            return 1;
                        })
                )
                .executes(ctx -> {
                    ctx.getSource().sendMessage(
                            Component.text("Usage: /unmute <player>", NamedTextColor.RED));
                    return 0;
                })
                .build();
        registerCommand(plugin, node);
    }

    private static void registerMuteInfo(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("muteinfo")
                .requires(src -> src.hasPermission(PERMISSION))
                .then(BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.word())
                        .suggests(PlayerLookup.playerSuggestions(plugin))
                        .executes(ctx -> {
                            CommandSource source = ctx.getSource();
                            String targetName = ctx.getArgument("target", String.class);
                            Optional<UUID> targetUuid = resolveUuid(plugin, targetName);
                            if (targetUuid.isEmpty()) {
                                source.sendMessage(MINI.deserialize("<red>Player not found."));
                                return 0;
                            }
                            MuteStore.Mute mute = plugin.getMuteService().getMute(targetUuid.get());
                            if (mute == null || MuteService.isExpired(mute)) {
                                source.sendMessage(MINI.deserialize(
                                        "<yellow><target></yellow> <gray>is not muted.",
                                        Placeholder.unparsed("target", targetName)));
                                return 1;
                            }
                            String remaining = mute.permanent()
                                    ? "permanent"
                                    : DurationUtil.humanize(mute.expiry() - System.currentTimeMillis()) + " remaining";
                            source.sendMessage(MINI.deserialize(
                                    "<gray>Mute for <yellow><target></yellow>: <white><span></white>"
                                            + " <gray>| reason: <white><reason></white>"
                                            + " <gray>| by: <white><by></white>",
                                    Placeholder.unparsed("target", targetName),
                                    Placeholder.unparsed("span", remaining),
                                    Placeholder.unparsed("reason", mute.reason() != null ? mute.reason() : "none"),
                                    Placeholder.unparsed("by", mute.mutedBy() != null ? mute.mutedBy() : "unknown")));
                            return 1;
                        })
                )
                .executes(ctx -> {
                    ctx.getSource().sendMessage(
                            Component.text("Usage: /muteinfo <player>", NamedTextColor.RED));
                    return 0;
                })
                .build();
        registerCommand(plugin, node, "muted");
    }

    private static void registerCommand(CrabUtilitiesVelocity plugin,
                                        LiteralCommandNode<CommandSource> node, String... aliases) {
        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .aliases(aliases)
                        .plugin(plugin)
                        .build(),
                command
        );
    }
}
