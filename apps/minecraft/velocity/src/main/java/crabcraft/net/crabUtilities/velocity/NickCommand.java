package crabcraft.net.crabUtilities.velocity;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter.NicknamePlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class NickCommand {

    public static final String PERMISSION = "crabutilities.nick";
    public static final String OTHERS_PERMISSION = "crabutilities.nick.others";
    private static final List<String> COMMAND_NAMES = List.of("nick", "nickname");
    private final CrabUtilitiesVelocity plugin;

    private NickCommand(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    public static void register(CrabUtilitiesVelocity plugin) {
        NickCommand listener = new NickCommand(plugin);
        BrigadierCommand command = new BrigadierCommand(listener.buildNode("nick"));
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .aliases("nickname").plugin(plugin).build(), command);
        plugin.getServer().getEventManager().register(plugin, listener);
    }

    private LiteralCommandNode<CommandSource> buildNode(String name) {
        return BrigadierCommand.literalArgumentBuilder(name)
                .then(BrigadierCommand.requiredArgumentBuilder("nickname-or-player", StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggest(context.getSource(), builder))
                        .executes(context -> execute(context.getSource(),
                                context.getArgument("nickname-or-player", String.class))))
                .executes(context -> {
                    error(context.getSource(), "Usage: /nick <nickname|off> or /nick <player> <nickname|off>");
                    return 0;
                }).build();
    }

    private int execute(CommandSource source, String input) {
        String[] arguments = input.strip().split("\\s+", 3);
        if (arguments.length < 1 || arguments.length > 2) {
            error(source, "Usage: /nick <nickname|off> or /nick <player> <nickname|off>");
            return 0;
        }
        boolean other = arguments.length == 2;
        if (!canChange(source, other)) {
            error(source, "You do not have permission to change " + (other ? "other players' nicknames." : "your nickname."));
            return 0;
        }
        if (!other && !(source instanceof Player)) {
            error(source, "Console usage: /nick <player> <nickname|off>");
            return 0;
        }
        String raw = arguments[arguments.length - 1];
        String nickname = isReset(raw) ? "" : raw;
        if (!nickname.isEmpty() && !isValidNickname(nickname)) {
            error(source, "Nicknames must contain 1–32 visible characters without spaces or control characters.");
            return 0;
        }
        boolean queued = plugin.runDatabaseTask("nickname-command", () -> {
            try {
                NicknamePlayer target;
                if (other) {
                    Player online = plugin.getServer().getPlayer(arguments[0]).orElse(null);
                    target = online == null ? plugin.getPgWriter().findNicknamePlayer(arguments[0])
                            : new NicknamePlayer(online.getUniqueId(), online.getUsername());
                } else {
                    Player player = (Player) source;
                    target = new NicknamePlayer(player.getUniqueId(), player.getUsername());
                }
                if (target == null) {
                    error(source, "No registered player was found with that account name.");
                    return;
                }
                if (!plugin.getNicknameListener().changeNickname(target.uuid(), nickname)) {
                    error(source, "The nickname could not be saved. Check the player's linked profile and try again.");
                    return;
                }
                Component confirmation = confirmation(nickname);
                if (source instanceof Player player && player.getUniqueId().equals(target.uuid())) {
                    source.sendMessage(confirmation);
                } else {
                    source.sendMessage(Component.text(target.username() + ": ", NamedTextColor.GREEN)
                            .append(confirmation));
                    plugin.getServer().getPlayer(target.uuid()).ifPresent(player -> player.sendMessage(confirmation));
                }
            } catch (Exception e) {
                plugin.getLogger().error("Nickname command failed", e);
                error(source, "The nickname could not be saved. Please try again.");
            }
        });
        if (!queued) error(source, "Nickname changes are temporarily unavailable. Please try again.");
        return queued ? 1 : 0;
    }

    private CompletableFuture<Suggestions> suggest(CommandSource source, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int separator = remaining.lastIndexOf(' ');
        SuggestionsBuilder current = separator < 0 ? builder : builder.createOffset(builder.getStart() + separator + 1);
        String prefix = current.getRemainingLowerCase();
        if (canChange(source, separator >= 0) && "off".startsWith(prefix)) current.suggest("off");
        if (separator >= 0 || !source.hasPermission(OTHERS_PERMISSION)) return current.buildFuture();
        CompletableFuture<Suggestions> result = new CompletableFuture<>();
        if (!plugin.runDatabaseTask("nickname-suggestions", () -> {
            try {
                for (String name : plugin.getPgWriter().nicknamePlayerNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) current.suggest(name);
                }
            } catch (Exception e) {
                plugin.getLogger().debug("Nickname suggestions unavailable", e);
            }
            result.complete(current.build());
        })) result.complete(current.build());
        return result;
    }

    static boolean canChange(CommandSource source, boolean other) {
        return other ? source.hasPermission(OTHERS_PERMISSION)
                : source.getPermissionValue(PERMISSION) != Tristate.FALSE;
    }

    static boolean isValidNickname(String raw) {
        if (raw == null || raw.length() > 256 || raw.codePoints().anyMatch(NickCommand::isInvisible)) return false;
        String plain = NicknameComponentParser.plain(raw);
        return plain != null && !plain.isBlank() && plain.codePointCount(0, plain.length()) <= 32
                && plain.codePoints().noneMatch(NickCommand::isInvisible);
    }

    private static boolean isInvisible(int character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character)
                || Character.isISOControl(character) || Character.getType(character) == Character.FORMAT;
    }

    private static boolean isReset(String value) {
        return value.equalsIgnoreCase("off") || value.equalsIgnoreCase("reset");
    }

    private static Component confirmation(String nickname) {
        return nickname.isEmpty() ? Component.text("Your nickname has been reset.", NamedTextColor.GREEN)
                : Component.text("Your nickname is now ", NamedTextColor.GREEN)
                        .append(NicknameComponentParser.parse(nickname)).append(Component.text(".", NamedTextColor.GREEN));
    }

    private static void error(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPlayerAvailableCommands(PlayerAvailableCommandsEvent event) {
        @SuppressWarnings("unchecked")
        RootCommandNode<CommandSource> root = (RootCommandNode<CommandSource>) event.getRootNode();
        for (String name : COMMAND_NAMES) {
            root.removeChildByName(name);
            root.addChild(buildNode(name));
        }
    }
}
