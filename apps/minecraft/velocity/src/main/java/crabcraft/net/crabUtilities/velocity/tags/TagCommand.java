package crabcraft.net.crabUtilities.velocity.tags;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import crabcraft.net.crabUtilities.tags.TagBridgeProtocol.Status;
import crabcraft.net.crabUtilities.tags.TagDefinition;
import crabcraft.net.crabUtilities.tags.TagSnapshot;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Optional;
import java.util.UUID;

/** Minimal staff maintenance, separate from the player's /tags picker. */
public final class TagCommand {
    private static final int MAX_REASON_LENGTH = 512;
    private static final int MAX_INSPECT_TAGS = 20;

    private TagCommand() {}

    public static boolean hasAnyPermission(CommandSource source) {
        return source.hasPermission("crabutilities.tags.grant")
                || source.hasPermission("crabutilities.tags.revoke")
                || source.hasPermission("crabutilities.tags.inspect");
    }

    public static LiteralArgumentBuilder<CommandSource> tree(CrabUtilitiesVelocity plugin) {
        return LiteralArgumentBuilder.<CommandSource>literal("tags")
                .requires(TagCommand::hasAnyPermission)
                .then(mutation(plugin, "grant", true))
                .then(mutation(plugin, "revoke", false))
                .then(LiteralArgumentBuilder.<CommandSource>literal("inspect")
                        .requires(source -> source.hasPermission("crabutilities.tags.inspect"))
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                                .executes(ctx -> inspect(plugin, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
                .executes(ctx -> {
                    error(ctx.getSource(), "Usage: /crabutilitiesproxy tags <grant|revoke> <uuid|online-name> <tag-id> <reason...> or tags inspect <uuid|online-name>");
                    return 0;
                });
    }

    private static LiteralArgumentBuilder<CommandSource> mutation(
            CrabUtilitiesVelocity plugin, String name, boolean grant) {
        return LiteralArgumentBuilder.<CommandSource>literal(name)
                .requires(source -> source.hasPermission("crabutilities.tags." + name))
                .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("tag", StringArgumentType.word())
                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> mutate(plugin, ctx.getSource(), grant,
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "tag"),
                                                StringArgumentType.getString(ctx, "reason"))))));
    }

    private static int mutate(CrabUtilitiesVelocity plugin, CommandSource source, boolean grant,
                              String target, String tagId, String reason) {
        Optional<UUID> playerId = resolve(plugin.getServer(), target);
        if (playerId.isEmpty()) { error(source, "Use an exact online Minecraft name or a full UUID."); return 0; }
        if (!validReason(reason)) {
            error(source, "Provide a reason of 1–512 characters, without control characters.");
            return 0;
        }
        TagService service = plugin.getTagService();
        if (service == null) { unavailable(source); return 0; }
        String actor = source instanceof Player player ? player.getUniqueId().toString() : "console";
        service.mutate(playerId.get(), tagId, grant, actor, reason.strip(), result -> {
            if (result.status() != Status.OK) {
                error(source, "Tag operation rejected: " + result.status().name().toLowerCase(java.util.Locale.ROOT) + ".");
            } else {
                String outcome = result.changed() ? (grant ? "Granted" : "Revoked")
                        : (grant ? "Already owned" : "Already absent");
                source.sendMessage(Component.text(outcome + ": " + tagId + " for " + playerId.get() + ".", NamedTextColor.GREEN));
            }
        }, () -> unavailable(source));
        return 1;
    }

    private static int inspect(CrabUtilitiesVelocity plugin, CommandSource source, String target) {
        Optional<UUID> playerId = resolve(plugin.getServer(), target);
        if (playerId.isEmpty()) { error(source, "Use an exact online Minecraft name or a full UUID."); return 0; }
        TagService service = plugin.getTagService();
        if (service == null) { unavailable(source); return 0; }
        service.inspect(playerId.get(), snapshot -> showInspection(source, snapshot), () -> unavailable(source));
        return 1;
    }

    private static void showInspection(CommandSource source, TagSnapshot snapshot) {
        source.sendMessage(Component.text("Tags for " + snapshot.playerId() + " (revision " + snapshot.revision()
                + "), selected: " + (snapshot.selectedTagId() == null ? "None" : snapshot.selectedTagId()), NamedTextColor.GOLD));
        for (TagDefinition tag : snapshot.ownedTags().stream().limit(MAX_INSPECT_TAGS).toList()) {
            source.sendMessage(Component.text("- " + tag.name() + " [" + tag.id() + "]"
                    + (tag.enabled() ? "" : " (disabled)"), NamedTextColor.GRAY));
        }
        if (snapshot.ownedTags().isEmpty()) source.sendMessage(Component.text("No unlocked tags.", NamedTextColor.GRAY));
        if (snapshot.ownedTags().size() > MAX_INSPECT_TAGS) source.sendMessage(Component.text(
                "Showing " + MAX_INSPECT_TAGS + " of " + snapshot.ownedTags().size() + " unlocked tags.", NamedTextColor.GRAY));
    }

    static Optional<UUID> resolve(ProxyServer server, String target) {
        try {
            UUID id = UUID.fromString(target);
            if (id.toString().equalsIgnoreCase(target)) return Optional.of(id);
        } catch (IllegalArgumentException ignored) {}
        return server.getAllPlayers().stream().filter(player -> player.getUsername().equalsIgnoreCase(target))
                .map(Player::getUniqueId).findFirst();
    }

    static boolean validReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= MAX_REASON_LENGTH
                && reason.codePoints().noneMatch(Character::isISOControl);
    }

    private static void unavailable(CommandSource source) {
        error(source, "Tags are temporarily unavailable. Try again shortly.");
    }

    private static void error(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
