package crabcraft.net.crabUtilities.endportals;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EndPortalBlockerListenerRegressionTest {

    private EndPortalBlockerListenerRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        AtomicBoolean preventEntry = new AtomicBoolean();
        EndPortalBlockerListener listener = new EndPortalBlockerListener(preventEntry::get);

        assertNotCancelled(listener, portalEvent(Player.class, PortalType.ENDER),
                "End portal entry was blocked while the tweak was disabled");

        preventEntry.set(true);
        assertCancelled(listener, portalEvent(Player.class, PortalType.ENDER),
                "player End portal entry was not blocked");
        assertCancelled(listener, portalEvent(Entity.class, PortalType.ENDER),
                "non-player End portal entry was not blocked");
        assertNotCancelled(listener, portalEvent(Player.class, PortalType.NETHER),
                "Nether portal entry was blocked");
        assertNotCancelled(listener, portalEvent(Player.class, PortalType.END_GATEWAY),
                "End gateway entry was blocked");
        for (World.Environment environment : new World.Environment[]{World.Environment.NORMAL, World.Environment.THE_END}) {
            PlayerInteractEvent interaction = portalInteraction(
                    Material.END_PORTAL, environment, GameMode.SPECTATOR, Action.RIGHT_CLICK_BLOCK);
            interaction.setUseItemInHand(Event.Result.ALLOW);
            listener.onSpectatorInteract(interaction);
            check(interaction.useInteractedBlock() == Event.Result.DENY,
                    "spectator End portal interaction bypassed the blocker in " + environment);
            check(interaction.useItemInHand() == Event.Result.ALLOW,
                    "blocking a portal changed the separate item-use result");
        }
        for (Material material : new Material[]{Material.NETHER_PORTAL, Material.END_GATEWAY, Material.STONE}) {
            assertInteractionAllowed(listener, portalInteraction(
                    material, World.Environment.NORMAL, GameMode.SPECTATOR, Action.RIGHT_CLICK_BLOCK),
                    "unrelated spectator interaction was blocked: " + material);
        }
        assertInteractionAllowed(listener, portalInteraction(
                Material.END_PORTAL, World.Environment.NORMAL, GameMode.SURVIVAL, Action.RIGHT_CLICK_BLOCK),
                "non-spectator interaction was changed");
        assertInteractionAllowed(listener, portalInteraction(
                Material.END_PORTAL, World.Environment.THE_END, GameMode.SPECTATOR, Action.LEFT_CLICK_BLOCK),
                "left-click interaction was changed");
        assertInteractionAllowed(listener, portalInteraction(
                null, World.Environment.NORMAL, GameMode.SPECTATOR, Action.RIGHT_CLICK_AIR),
                "air interaction was changed");

        preventEntry.set(false);
        assertNotCancelled(listener, portalEvent(Player.class, PortalType.ENDER),
                "End portal entry remained blocked after the live toggle was disabled");
        assertInteractionAllowed(listener, portalInteraction(
                Material.END_PORTAL, World.Environment.THE_END, GameMode.SPECTATOR, Action.RIGHT_CLICK_BLOCK),
                "spectator End portal interaction remained blocked after disabling the tweak");

        YamlConfiguration config = new YamlConfiguration();
        try (var input = EndPortalBlockerListenerRegressionTest.class.getClassLoader()
                .getResourceAsStream("modules/tweaks.yml")) {
            check(input != null, "bundled tweaks.yml is missing");
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        check(config.contains(EndPortalBlockerListener.CONFIG_PATH),
                "End portal blocker config is missing");
        check(!config.getBoolean(EndPortalBlockerListener.CONFIG_PATH, true),
                "End portal blocker is not disabled by default");
    }

    private static PlayerInteractEvent portalInteraction(
            Material material, World.Environment environment, GameMode mode, Action action) {
        World world = proxy(World.class, Map.of("getEnvironment", environment));
        Player player = proxy(Player.class, Map.of("getGameMode", mode, "getWorld", world));
        Block block = material == null ? null : proxy(Block.class, Map.of("getType", material, "getWorld", world));
        return new PlayerInteractEvent(player, action, null, block, BlockFace.UP);
    }

    private static void assertInteractionAllowed(
            EndPortalBlockerListener listener, PlayerInteractEvent event, String message) {
        Event.Result original = event.useInteractedBlock();
        listener.onSpectatorInteract(event);
        check(event.useInteractedBlock() == original, message);
    }

    private static <T extends Entity> EntityPortalEnterEvent portalEvent(
            final Class<T> entityType,
            final PortalType portalType
    ) {
        return new EntityPortalEnterEvent(proxy(entityType), new Location(null, 0D, 0D, 0D), portalType);
    }

    private static void assertCancelled(
            final EndPortalBlockerListener listener,
            final EntityPortalEnterEvent event,
            final String message
    ) {
        listener.onEntityPortalEnter(event);
        check(event.isCancelled(), message);
    }

    private static void assertNotCancelled(
            final EndPortalBlockerListener listener,
            final EntityPortalEnterEvent event,
            final String message
    ) {
        listener.onEntityPortalEnter(event);
        check(!event.isCancelled(), message);
    }

    private static <T> T proxy(final Class<T> type) {
        return proxy(type, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + " proxy";
                    default -> values.getOrDefault(method.getName(), defaultValue(method.getReturnType()));
                });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
