package crabcraft.net.crabUtilities.gamemode;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GameModeCommandRegressionTest {

    public static void main(String[] args) {
        supportsShortcutsAndModeArguments();
        permissionsAreIndependentOfModeAndAlias();
        changingAnotherPlayerRequiresBothPermissions();
        completionRespectsModePermissionsAndVisibility();
        cancelledChangesDoNotReportSuccess();
        invalidArgumentsDoNotChangeGameMode();
    }

    private static void supportsShortcutsAndModeArguments() {
        Map<String, GameMode> shortcuts = Map.of(
                "gmc", GameMode.CREATIVE, "creative", GameMode.CREATIVE,
                "gms", GameMode.SURVIVAL, "survival", GameMode.SURVIVAL,
                "gma", GameMode.ADVENTURE, "adventure", GameMode.ADVENTURE,
                "gmsp", GameMode.SPECTATOR, "spectator", GameMode.SPECTATOR);
        PlayerStub player = new PlayerStub("Moderator");
        player.grantAllModes();
        GameModeCommand handler = new GameModeCommand(() -> List.of(player.player));
        shortcuts.forEach((alias, mode) -> {
            handler.onCommand(player.player, command(alias), alias, new String[0]);
            check(player.mode == mode, "shortcut /" + alias + " selected the wrong mode");
        });

        Map<String, GameMode> modes = Map.ofEntries(
                Map.entry("creative", GameMode.CREATIVE), Map.entry("c", GameMode.CREATIVE),
                Map.entry("1", GameMode.CREATIVE), Map.entry("survival", GameMode.SURVIVAL),
                Map.entry("s", GameMode.SURVIVAL), Map.entry("0", GameMode.SURVIVAL),
                Map.entry("adventure", GameMode.ADVENTURE), Map.entry("a", GameMode.ADVENTURE),
                Map.entry("2", GameMode.ADVENTURE), Map.entry("spectator", GameMode.SPECTATOR),
                Map.entry("sp", GameMode.SPECTATOR), Map.entry("3", GameMode.SPECTATOR));
        modes.forEach((value, mode) -> {
            handler.onCommand(player.player, command("gamemode"), "gm", new String[] { value });
            check(player.mode == mode, "mode argument " + value + " selected the wrong mode");
        });
    }

    private static void permissionsAreIndependentOfModeAndAlias() {
        PlayerStub player = new PlayerStub("Moderator");
        player.permissions.add("crabutilities.gamemode.spectator");
        GameModeCommand handler = new GameModeCommand(() -> List.of(player.player));

        handler.onCommand(player.player, command("gmsp"), "gmsp", new String[0]);
        check(player.mode == GameMode.SPECTATOR, "spectator permission did not allow spectator");
        int changes = player.modeChanges;
        for (String alias : List.of("gmc", "creative", "gms", "survival", "gma", "adventure")) {
            handler.onCommand(player.player, command(alias), alias, new String[0]);
        }
        for (String value : List.of("creative", "1", "survival", "0", "adventure", "2")) {
            handler.onCommand(player.player, command("gamemode"), "gamemode", new String[] { value });
        }
        check(player.modeChanges == changes, "a shortcut or mode argument bypassed its mode permission");
    }

    private static void changingAnotherPlayerRequiresBothPermissions() {
        PlayerStub sender = new PlayerStub("Moderator");
        PlayerStub target = new PlayerStub("Builder");
        sender.permissions.add("crabutilities.gamemode.spectator");
        GameModeCommand handler = new GameModeCommand(() -> List.of(sender.player, target.player));

        handler.onCommand(sender.player, command("gmsp"), "gmsp", new String[] { "Builder" });
        check(target.modeChanges == 0, "mode permission alone changed another player's game mode");
        sender.permissions.add("crabutilities.gamemode.others");
        handler.onCommand(sender.player, command("gmc"), "gmc", new String[] { "Builder" });
        check(target.modeChanges == 0, "others permission bypassed the creative permission");
        handler.onCommand(sender.player, command("gamemode"), "gamemode",
                new String[] { "spectator", "bUiLdEr" });
        check(target.mode == GameMode.SPECTATOR, "permitted case-insensitive target was not changed");
        check(sender.lastMessage().contains("Builder"), "the sender did not receive target confirmation");
        check(target.lastMessage().contains("spectator"), "the target did not receive confirmation");

        sender.permissions.remove("crabutilities.gamemode.others");
        handler.onCommand(sender.player, command("gmsp"), "gmsp", new String[] { "Moderator" });
        check(sender.mode == GameMode.SPECTATOR, "an explicit self-target incorrectly required others permission");
    }

    private static void completionRespectsModePermissionsAndVisibility() {
        PlayerStub sender = new PlayerStub("Moderator");
        PlayerStub visible = new PlayerStub("Builder");
        PlayerStub hidden = new PlayerStub("HiddenAdmin");
        sender.permissions.add("crabutilities.gamemode.spectator");
        sender.hidden.add(hidden.id);
        GameModeCommand handler = new GameModeCommand(
                () -> List.of(sender.player, visible.player, hidden.player));

        check(handler.onTabComplete(sender.player, command("gamemode"), "gamemode",
                new String[] { "" }).equals(List.of("spectator")),
                "mode completion advertised unpermitted game modes");
        check(handler.onTabComplete(sender.player, command("gmc"), "gmc",
                new String[] { "" }).isEmpty(), "creative shortcut completed without creative permission");
        check(handler.onTabComplete(sender.player, command("gmsp"), "gmsp",
                new String[] { "" }).equals(List.of("Moderator")),
                "target completion advertised other players without others permission");

        sender.permissions.add("crabutilities.gamemode.others");
        check(handler.onTabComplete(sender.player, command("gamemode"), "gamemode",
                new String[] { "spectator", "bU" }).equals(List.of("Builder")),
                "target completion did not filter case-insensitively");
        check(!handler.onTabComplete(sender.player, command("gmsp"), "gmsp",
                new String[] { "" }).contains("HiddenAdmin"), "completion exposed a hidden player");
        handler.onCommand(sender.player, command("gmsp"), "gmsp", new String[] { "HiddenAdmin" });
        check(hidden.modeChanges == 0, "command targeted a player hidden from the sender");
    }

    private static void cancelledChangesDoNotReportSuccess() {
        PlayerStub player = new PlayerStub("Moderator");
        player.permissions.add("crabutilities.gamemode.spectator");
        player.cancelChange = true;
        GameModeCommand handler = new GameModeCommand(() -> List.of(player.player));

        handler.onCommand(player.player, command("gmsp"), "gmsp", new String[0]);
        check(player.mode == GameMode.SURVIVAL, "a cancelled change still changed the game mode");
        check(player.lastMessage().contains("cancelled"), "a cancelled change reported success");
    }

    private static void invalidArgumentsDoNotChangeGameMode() {
        PlayerStub player = new PlayerStub("Moderator");
        player.grantAllModes();
        GameModeCommand handler = new GameModeCommand(() -> List.of(player.player));

        for (String[] args : List.of(new String[0], new String[] { "invalid" },
                new String[] { "creative", "Moderator", "extra" },
                new String[] { "creative", "Missing" })) {
            handler.onCommand(player.player, command("gamemode"), "gamemode", args);
        }
        handler.onCommand(player.player, command("gmc"), "gmc", new String[] { "Moderator", "extra" });
        check(player.modeChanges == 0, "invalid arguments changed a game mode");
    }

    private static Command command(String name) {
        return new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final class PlayerStub {
        private final UUID id = UUID.randomUUID();
        private final Set<String> permissions = new HashSet<>();
        private final Set<UUID> hidden = new HashSet<>();
        private final List<Component> messages = new ArrayList<>();
        private final Player player;
        private GameMode mode = GameMode.SURVIVAL;
        private int modeChanges;
        private boolean cancelChange;

        private PlayerStub(String name) {
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[] { Player.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> name;
                        case "getUniqueId" -> id;
                        case "displayName" -> Component.text(name);
                        case "hasPermission" -> permissions.contains(args[0]);
                        case "canSee" -> !hidden.contains(((Player) args[0]).getUniqueId());
                        case "getGameMode" -> mode;
                        case "setGameMode" -> {
                            modeChanges++;
                            if (!cancelChange) mode = (GameMode) args[0];
                            yield null;
                        }
                        case "sendMessage" -> {
                            if (args.length == 1 && args[0] instanceof Component message) messages.add(message);
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private void grantAllModes() {
            for (String mode : List.of("survival", "creative", "adventure", "spectator")) {
                permissions.add("crabutilities.gamemode." + mode);
            }
        }

        private String lastMessage() {
            return PlainTextComponentSerializer.plainText().serialize(messages.getLast());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        throw new AssertionError("Unsupported primitive: " + type);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
