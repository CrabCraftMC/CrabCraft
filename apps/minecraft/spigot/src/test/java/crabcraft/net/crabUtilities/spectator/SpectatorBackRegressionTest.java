package crabcraft.net.crabUtilities.spectator;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SpectatorBackRegressionTest {

    public static void main(String[] args) {
        returnsToAnExactSnapshotOfTheStartingLocation();
        requiresSpectatorMode();
        forgetsTheLocationOnDisconnect();
    }

    private static void returnsToAnExactSnapshotOfTheStartingLocation() {
        World world = proxy(World.class, (method, methodArgs) -> defaultValue(method.getReturnType()));
        Location original = new Location(world, 12.25, 64.0, -8.75, 135.0F, -20.0F);
        PlayerStub stub = new PlayerStub(original);
        SpectatorBackCommand command = new SpectatorBackCommand();

        command.onGameModeChange(gameModeChange(stub.player));

        original.setX(999.0);
        stub.gameMode = GameMode.SPECTATOR;
        stub.location = new Location(world, 200.0, 100.0, 200.0);
        command.onCommand(stub.player, null, "specback", new String[0]);

        Location destination = stub.teleportedTo;
        check(destination != null, "the player was not teleported");
        check(destination.getWorld() == world, "the starting world was not preserved");
        check(destination.getX() == 12.25 && destination.getY() == 64.0 && destination.getZ() == -8.75,
                "the starting coordinates were not preserved");
        check(destination.getYaw() == 135.0F && destination.getPitch() == -20.0F,
                "the starting rotation was not preserved");
        check(stub.gameMode == GameMode.SPECTATOR, "the command changed the player's game mode");
    }

    private static void requiresSpectatorMode() {
        World world = proxy(World.class, (method, methodArgs) -> defaultValue(method.getReturnType()));
        PlayerStub stub = new PlayerStub(new Location(world, 1.0, 2.0, 3.0));
        SpectatorBackCommand command = new SpectatorBackCommand();

        command.onGameModeChange(gameModeChange(stub.player));
        command.onCommand(stub.player, null, "specback", new String[0]);

        check(stub.teleportedTo == null, "a non-spectator was teleported");
    }

    private static void forgetsTheLocationOnDisconnect() {
        World world = proxy(World.class, (method, methodArgs) -> defaultValue(method.getReturnType()));
        PlayerStub stub = new PlayerStub(new Location(world, 1.0, 2.0, 3.0));
        SpectatorBackCommand command = new SpectatorBackCommand();

        command.onGameModeChange(gameModeChange(stub.player));
        command.onPlayerQuit(new PlayerQuitEvent(
                stub.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        stub.gameMode = GameMode.SPECTATOR;
        command.onCommand(stub.player, null, "specback", new String[0]);

        check(stub.teleportedTo == null, "a disconnected player's location was retained");
    }

    private static PlayerGameModeChangeEvent gameModeChange(Player player) {
        return new PlayerGameModeChangeEvent(
                player,
                GameMode.SPECTATOR,
                PlayerGameModeChangeEvent.Cause.COMMAND,
                Component.empty());
    }

    private static final class PlayerStub {
        private final UUID uniqueId = UUID.randomUUID();
        private final Player player;
        private GameMode gameMode = GameMode.SURVIVAL;
        private Location location;
        private Location teleportedTo;

        private PlayerStub(Location location) {
            this.location = location;
            this.player = proxy(Player.class, (method, args) -> switch (method.getName()) {
                case "getUniqueId" -> uniqueId;
                case "getGameMode" -> gameMode;
                case "getLocation" -> this.location;
                case "isOnline" -> true;
                case "teleportAsync" -> {
                    teleportedTo = ((Location) args[0]).clone();
                    yield CompletableFuture.completedFuture(true);
                }
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] { type },
                (proxy, method, args) -> invocation.invoke(method, args)));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (!type.isPrimitive()) return null;
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
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
