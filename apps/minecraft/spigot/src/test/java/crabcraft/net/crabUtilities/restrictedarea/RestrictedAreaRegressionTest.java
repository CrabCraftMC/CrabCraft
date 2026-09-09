package crabcraft.net.crabUtilities.restrictedarea;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.Foods;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumables;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class RestrictedAreaRegressionTest {

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Standalone tests do not load data packs; supply the vanilla components used by these item checks.
        Items.COOKED_BEEF.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                .set(DataComponents.FOOD, Foods.COOKED_BEEF)
                .set(DataComponents.CONSUMABLE, Consumables.DEFAULT_FOOD).build());
        Items.DIAMOND_HOE.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        verifyConfiguration();
        verifyPermissionChangesAreLive();
        verifyGriefProtectionInEveryWorld();
        verifyPersonalInventoryRemainsUsable();
        verifyNormalMovementAndCommands();
        verifyPassiveInteractionsStaySilent();
        VerificationReminderRegressionTest.main(args);
    }

    private static void verifyConfiguration() throws Exception {
        try (InputStream input = RestrictedAreaRegressionTest.class.getClassLoader()
                .getResourceAsStream("modules/gameplay.yml")) {
            check(input != null, "bundled gameplay.yml is missing");
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            check(!config.getBoolean("restricted-area.enabled", true), "protection is not opt-in");
            check(config.getString("restricted-area.bypass-permission", "")
                    .equals(RestrictedAreaSettings.DEFAULT_PERMISSION), "default permission changed");
            check(!config.contains("restricted-area.bounds") && !config.contains("restricted-area.world")
                    && !config.contains("restricted-area.return-location"), "obsolete settings remain in defaults");
        }
        YamlConfiguration config = enabledConfig();
        check(RestrictedAreaSettings.load(config).enabled(), "protection still requires area settings");
        config.set("restricted-area.world", "missing-world");
        config.set("restricted-area.bounds", "obsolete");
        config.set("restricted-area.return-location.y", 90D);
        check(RestrictedAreaSettings.load(config).enabled(), "legacy settings disable grief protection");
    }

    private static void verifyPermissionChangesAreLive() {
        RestrictedAreaSettings settings = RestrictedAreaSettings.load(enabledConfig());
        AtomicBoolean verified = new AtomicBoolean();
        AtomicBoolean operator = new AtomicBoolean();
        Player player = player(verified, operator, new AtomicInteger(), proxy(World.class, Map.of()));
        check(RestrictedAreaListener.isRestricted(player, settings), "unverified player was not restricted");
        verified.set(true);
        check(!RestrictedAreaListener.isRestricted(player, settings), "verification did not immediately grant access");
        verified.set(false);
        operator.set(true);
        check(!RestrictedAreaListener.isRestricted(player, settings), "operator was restricted");
        operator.set(false);
        check(RestrictedAreaListener.isRestricted(player, settings), "removing bypass did not restore protection");
        check(!RestrictedAreaListener.isRestricted(player, RestrictedAreaSettings.disabled()),
                "disabled feature still restricts players");
    }

    private static void verifyGriefProtectionInEveryWorld() {
        for (String name : new String[]{"world", "world_nether", "world_the_end", "custom_world"}) {
            RestrictedAreaListener listener = listener();
            AtomicBoolean verified = new AtomicBoolean();
            Player player = player(verified, new AtomicBoolean(), new AtomicInteger(),
                    proxy(World.class, Map.of("getName", name)));
            Block block = proxy(Block.class, Map.of());
            BlockBreakEvent breaking = new BlockBreakEvent(block, player);
            listener.onBlockBreak(breaking);
            check(breaking.isCancelled(), "block breaking was allowed in " + name);
            BlockPlaceEvent placing = new BlockPlaceEvent(block, proxy(BlockState.class, Map.of()),
                    block, null, player, true, EquipmentSlot.HAND);
            listener.onBlockPlace(placing);
            check(placing.isCancelled(), "block placement was allowed in " + name);
            for (Entity target : new Entity[]{proxy(Entity.class, Map.of()),
                    player(new AtomicBoolean(true), new AtomicBoolean(), new AtomicInteger(), player.getWorld())}) {
                PrePlayerAttackEntityEvent attack = new PrePlayerAttackEntityEvent(player, target, true);
                listener.onPreAttack(attack);
                check(attack.isCancelled(), "unverified player could damage an entity");
                PlayerInteractEntityEvent interaction = new PlayerInteractEntityEvent(player, target);
                listener.onInteractEntity(interaction);
                check(interaction.isCancelled(), "unverified player could alter an entity");
            }
            ProjectileLaunchEvent arrow = new ProjectileLaunchEvent(proxy(Arrow.class, Map.of("getShooter", player)));
            listener.onProjectileLaunch(arrow);
            check(arrow.isCancelled(), "unverified player could launch a damaging projectile");
            ProjectileLaunchEvent pearl = new ProjectileLaunchEvent(proxy(EnderPearl.class, Map.of("getShooter", player)));
            listener.onProjectileLaunch(pearl);
            check(!pearl.isCancelled(), "ordinary pearl travel was blocked");
            verified.set(true);
            BlockBreakEvent permitted = new BlockBreakEvent(block, player);
            listener.onBlockBreak(permitted);
            check(!permitted.isCancelled(), "verified player still cannot build");
        }
    }

    private static void verifyPersonalInventoryRemainsUsable() {
        RestrictedAreaListener listener = listener();
        Player player = player(new AtomicBoolean(), new AtomicBoolean(), new AtomicInteger(),
                proxy(World.class, Map.of()));
        for (InventoryType type : new InventoryType[]{InventoryType.CRAFTING, InventoryType.CREATIVE,
                InventoryType.PLAYER, InventoryType.CHEST, InventoryType.HOPPER, InventoryType.SHULKER_BOX}) {
            Inventory inventory = proxy(Inventory.class, Map.of("getType", type));
            InventoryView view = proxy(InventoryView.class,
                    Map.of("getType", type, "getPlayer", player, "getTopInventory", inventory));
            InventoryOpenEvent open = new InventoryOpenEvent(view);
            listener.onInventoryOpen(open);
            InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                    0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            listener.onInventoryClick(click);
            boolean personal = type == InventoryType.CRAFTING || type == InventoryType.CREATIVE
                    || type == InventoryType.PLAYER;
            check(open.isCancelled() != personal, "incorrect inventory access for " + type);
            check(click.isCancelled() != personal, "incorrect inventory manipulation for " + type);
        }
    }

    private static void verifyNormalMovementAndCommands() throws Exception {
        RestrictedAreaListener listener = listener();
        AtomicInteger messages = new AtomicInteger();
        World world = proxy(World.class, Map.of());
        Player player = player(new AtomicBoolean(), new AtomicBoolean(), messages, world);
        Location from = new Location(world, 0D, 64D, 0D);
        Location outside = new Location(world, -100D, 90D, 0D);
        Event[] events = {
                new PlayerMoveEvent(player, from, outside),
                new PlayerTeleportEvent(player, from, outside, PlayerTeleportEvent.TeleportCause.COMMAND),
                new PlayerTeleportEvent(player, from, outside, PlayerTeleportEvent.TeleportCause.ENDER_PEARL),
                new PlayerToggleFlightEvent(player, true),
                new FoodLevelChangeEvent(player, 10, null),
                new PlayerCommandPreprocessEvent(player, "/tp 0 90 0", Set.of()),
                new PlayerCommandPreprocessEvent(player, "/msg friend hello", Set.of())
        };
        for (Event event : events) {
            dispatch(listener, event);
            check(!((Cancellable) event).isCancelled(), "unrelated behaviour was restricted: " + event.getEventName());
        }
        check(messages.get() == 0, "normal movement or commands sent verification reminders");
        for (var method : RestrictedAreaListener.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(EventHandler.class)) {
                check(!method.getParameterTypes()[0].getSimpleName().contains("Chat"), "chat is still restricted");
            }
        }
    }

    private static void verifyPassiveInteractionsStaySilent() {
        RestrictedAreaListener listener = listener();
        AtomicInteger messages = new AtomicInteger();
        Player player = player(new AtomicBoolean(), new AtomicBoolean(), messages, proxy(World.class, Map.of()));
        PlayerInteractEvent physical = new PlayerInteractEvent(player, Action.PHYSICAL, null,
                proxy(Block.class, Map.of()), BlockFace.UP);
        listener.onInteract(physical);
        check(physical.useInteractedBlock() == Event.Result.DENY, "farmland/pressure plate protection was removed");
        PlayerInteractEvent air = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.UP);
        Event.Result originalItemUse = air.useItemInHand();
        listener.onInteract(air);
        check(air.useItemInHand() == originalItemUse, "personal item use in the air was blocked");
        check(messages.get() == 0, "passive movement or air interaction sent a reminder");
        PlayerInteractEvent breaking = new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK, null,
                proxy(Block.class, Map.of()), BlockFace.UP);
        listener.onInteract(breaking);
        check(messages.get() == 1, "obvious grief attempt did not send a reminder");
        PlayerInteractEvent chest = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
                proxy(Block.class, Map.of("getType", Material.CHEST)), BlockFace.UP);
        listener.onInteract(chest);
        check(chest.useInteractedBlock() == Event.Result.DENY, "opening a chest with an empty hand was allowed");
        check(RestrictedAreaListener.isPersonalItem(Material.COOKED_BEEF), "food use was classified as griefing");
        check(!RestrictedAreaListener.isPersonalItem(Material.DIAMOND_HOE), "world-altering tool was allowed");
        PlayerInteractEvent food = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
                proxy(Block.class, Map.of("getType", Material.STONE)), BlockFace.UP) {
            @Override
            public boolean hasItem() { return true; }
            @Override
            public Material getMaterial() { return Material.COOKED_BEEF; }
        };
        listener.onInteract(food);
        check(food.useItemInHand() != Event.Result.DENY, "eating while looking at a block was prevented");
    }

    private static void dispatch(RestrictedAreaListener listener, Event event) throws Exception {
        for (var method : RestrictedAreaListener.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(EventHandler.class)
                    && method.getParameterTypes()[0].isInstance(event)) {
                method.invoke(listener, event);
            }
        }
    }

    private static RestrictedAreaListener listener() {
        return new RestrictedAreaListener(null, RestrictedAreaSettings.load(enabledConfig()));
    }

    private static YamlConfiguration enabledConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("restricted-area.enabled", true);
        config.set("restricted-area.bypass-permission", "crabcraft.member");
        return config;
    }

    private static Player player(AtomicBoolean verified, AtomicBoolean operator, AtomicInteger messages, World world) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
                    case "hasPermission" -> verified.get();
                    case "isOp" -> operator.get();
                    case "sendMessage" -> { messages.incrementAndGet(); yield null; }
                    case "teleport" -> throw new AssertionError("protection attempted a corrective teleport");
                    default -> defaultValue(method.getReturnType());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> values.containsKey(method.getName())
                        ? values.get(method.getName()) : defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
