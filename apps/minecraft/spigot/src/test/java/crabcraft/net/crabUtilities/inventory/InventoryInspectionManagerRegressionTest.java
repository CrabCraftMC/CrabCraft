package crabcraft.net.crabUtilities.inventory;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class InventoryInspectionManagerRegressionTest {

    public static void main(String[] args) throws Exception {
        distinguishesPaddingFromBottomInventoryAndOutsideSlots();
        protectsPaddingAndReadOnlyInventories();
        releasesOfflineSessionsAtBothLoginValidationPhases();
        leavesOtherPlayersAndLiveInventoriesOpen();
        closesLiveInventoriesOnDeathAndRespawn();
        archivesConflictsAndReleasesLocksBeforeClosingTheEditor();
        retriesTransientSaveFailuresWithoutDiscardingTheSession();
        retriesFailedRecoveryWithoutSavingTheStaleSnapshotAgain();
    }

    private static void distinguishesPaddingFromBottomInventoryAndOutsideSlots() {
        for (int slot = -1; slot <= 81; slot++) {
            check(InventoryInspectionManager.isPadding(slot, 41, 45) == (slot >= 41 && slot < 45),
                    "incorrect padding boundary for raw slot " + slot);
        }
        check(!InventoryInspectionManager.isPadding(-999, 41, 45),
                "outside-inventory clicks were mistaken for padding");
    }

    private static void protectsPaddingAndReadOnlyInventories() throws Exception {
        Harness harness = new Harness(false);
        InventoryClickEvent readOnly = harness.click(36, InventoryAction.PICKUP_ALL);
        harness.manager.onClick(readOnly);
        check(readOnly.isCancelled(), "view-only permission allowed armour extraction");
        check(!harness.dirty(), "a cancelled view-only click dirtied the inventory");

        harness.modify = true;
        for (int slot = 41; slot < 45; slot++) {
            InventoryClickEvent padding = harness.click(slot, InventoryAction.HOTBAR_SWAP);
            harness.manager.onClick(padding);
            check(padding.isCancelled(), "hotbar swapping could extract padding slot " + slot);
        }
        for (InventoryAction action : new InventoryAction[] {
                InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.COLLECT_TO_CURSOR }) {
            InventoryClickEvent indirect = harness.click(45, action);
            harness.manager.onClick(indirect);
            check(indirect.isCancelled(), "an indirect move could alter protected padding: " + action);
        }
        for (int slot : new int[] { 0, 35, 36, 39, 40 }) {
            InventoryClickEvent normal = harness.click(slot, InventoryAction.PICKUP_ALL);
            harness.manager.onClick(normal);
            check(!normal.isCancelled(), "an authorised normal edit was blocked in slot " + slot);
        }
        check(harness.dirty(), "authorised edits were not scheduled for saving");
    }

    private static void releasesOfflineSessionsAtBothLoginValidationPhases() throws Exception {
        for (boolean configuration : new boolean[] { false, true }) {
            Harness harness = new Harness(true);
            PlayerConnection connection = connection(harness.targetId, configuration);
            PlayerConnectionValidateLoginEvent event = new PlayerConnectionValidateLoginEvent(connection, null);
            harness.manager.onLogin(event);
            check(harness.closes == 1, "offline inspection stayed open during "
                    + (configuration ? "final configuration" : "initial login") + " validation");
            check(harness.sessions().isEmpty(), "a completed offline inspection retained its target lock");
            check(harness.viewers().isEmpty(), "a completed offline inspection retained its viewer lock");
            check(event.isAllowed(), "a clean offline inventory unnecessarily blocked login");
        }
    }

    private static void leavesOtherPlayersAndLiveInventoriesOpen() throws Exception {
        Harness unrelated = new Harness(true);
        unrelated.manager.onLogin(new PlayerConnectionValidateLoginEvent(connection(UUID.randomUUID(), true), null));
        check(unrelated.closes == 0 && unrelated.sessions().size() == 1,
                "another player's login closed an unrelated inspection");

        Harness live = new Harness(false);
        live.manager.onLogin(new PlayerConnectionValidateLoginEvent(connection(live.targetId, true), null));
        check(live.closes == 0 && live.sessions().size() == 1,
                "configuration validation closed a directly backed live inventory");
    }

    private static PlayerConnection connection(UUID targetId, boolean configuration) {
        PlayerProfile profile = proxy(PlayerProfile.class, (object, method, args) ->
                method.getName().equals("getId") ? targetId : defaultValue(method.getReturnType()));
        Class<? extends PlayerConnection> type = configuration
                ? PlayerConfigurationConnection.class : PlayerLoginConnection.class;
        return proxy(type, (object, method, args) -> switch (method.getName()) {
            case "getProfile", "getAuthenticatedProfile" -> profile;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static void closesLiveInventoriesOnDeathAndRespawn() throws Exception {
        for (boolean enderChest : new boolean[] { false, true }) {
            for (boolean respawn : new boolean[] { false, true }) {
                Harness harness = new Harness(false, enderChest);
                notifyDeathOrRespawn(harness.manager, UUID.randomUUID(), respawn);
                check(harness.closes == 0 && harness.sessions().size() == 1,
                        "another player's death or respawn closed an unrelated inspection");

                notifyDeathOrRespawn(harness.manager, harness.targetId, respawn);
                check(harness.closes == 1 && harness.sessions().isEmpty() && harness.viewers().isEmpty(),
                        "a live " + (enderChest ? "ender chest" : "inventory") + " remained open after "
                                + (respawn ? "respawn" : "death"));
            }
        }
    }

    private static void notifyDeathOrRespawn(InventoryInspectionManager manager, UUID targetId, boolean respawn) {
        Player player = proxy(Player.class, (object, method, args) ->
                method.getName().equals("getUniqueId") ? targetId : defaultValue(method.getReturnType()));
        if (respawn) {
            manager.onRespawn(new PlayerRespawnEvent(player, new Location(null, 0, 64, 0),
                    false, false, false, PlayerRespawnEvent.RespawnReason.DEATH));
        } else {
            DamageSource damage = proxy(DamageSource.class, (object, method, args) -> defaultValue(method.getReturnType()));
            manager.onDeath(new PlayerDeathEvent(player, damage, List.of(), 0, Component.empty(), false));
        }
    }

    private static void archivesConflictsAndReleasesLocksBeforeClosingTheEditor() throws Exception {
        for (boolean enderChest : new boolean[] { false, true }) {
            for (String trigger : List.of("timer", "login", "configuration")) {
                StubStore store = new StubStore();
                store.saveFailure = new OfflinePlayerInventoryStore.SnapshotConflictException("Player data changed");
                Harness harness = new Harness(true, enderChest, store);
                harness.edit();

                boolean saved = trigger.equals("timer") ? harness.persist()
                        : harness.login(trigger.equals("configuration")).isAllowed();
                check(saved, "a successfully archived conflict failed saving or blocked " + trigger);
                check(store.saves == 1 && store.recoveries == 1,
                        "synchronous close events retried or duplicated conflict recovery");
                check(harness.closes == 1 && harness.closeEvents == 1,
                        "a conflicted editor was not closed exactly once");
                check(harness.sessions().isEmpty() && harness.viewers().isEmpty(),
                        "a successfully archived conflict retained an inspection lock");
                check(!harness.dirty(), "a successfully archived conflict remained dirty");
                harness.checkRetainedEdits(store.recoveredContents);
                check(store.recoverySnapshot == harness.snapshot, "recovery used a different player-data snapshot");
                check(store.recoveryDirectory.equals(harness.recoveryDirectory), "recovery used the wrong directory");
                check(harness.logs.stream().anyMatch(message -> message.contains(store.recoveryPath.toString())),
                        "the recovery path was not logged for administrators");

                harness.checkLoginAllowedAtBothPhases();
                check(store.saves == 1 && store.recoveries == 1,
                        "a subsequent login retried the released conflict");
            }
        }
    }

    private static void retriesTransientSaveFailuresWithoutDiscardingTheSession() throws Exception {
        for (boolean enderChest : new boolean[] { false, true }) {
            StubStore store = new StubStore();
            store.saveFailure = new IOException("Storage temporarily unavailable");
            Harness harness = new Harness(true, enderChest, store);
            harness.edit();

            check(!harness.persist(), "a transient storage error was treated as a successful save");
            check(harness.dirty() && harness.sessions().size() == 1 && harness.viewers().size() == 1,
                    "a transient storage error discarded unsaved edits or their locks");
            check(harness.closes == 0 && store.recoveries == 0,
                    "a transient storage error unnecessarily archived or closed the editor");
            check(!harness.login(false).isAllowed(), "login ignored an unsaved transient storage failure");
            check(store.saves == 2, "synchronous close handling duplicated the failing save attempt");
            check(harness.dirty() && harness.sessions().size() == 1,
                    "closing a failing inspection released its unsaved target lock");

            store.saveFailure = null;
            check(harness.persist(), "a transient storage error was not retried after recovery");
            check(store.saves > 1 && store.recoveries == 0, "normal saving did not retry independently of recovery");
            check(store.savedSnapshot == harness.snapshot, "retrying normal saving replaced its snapshot");
            harness.checkRetainedEdits(store.savedContents);
            check(!harness.dirty() && harness.sessions().isEmpty() && harness.viewers().isEmpty(),
                    "a successful retry retained the closed inspection or its target lock");
            harness.checkLoginAllowedAtBothPhases();
        }
    }

    private static void retriesFailedRecoveryWithoutSavingTheStaleSnapshotAgain() throws Exception {
        for (boolean enderChest : new boolean[] { false, true }) {
            StubStore store = new StubStore();
            store.saveFailure = new OfflinePlayerInventoryStore.SnapshotConflictException("Player data changed");
            store.recoveryFailure = new IOException("Recovery storage temporarily unavailable");
            Harness harness = new Harness(true, enderChest, store);
            harness.edit();

            check(!harness.persist(), "an unarchived conflict was treated as a successful save");
            check(harness.dirty() && harness.sessions().size() == 1 && harness.viewers().size() == 1,
                    "a failed recovery discarded its unsaved edits or locks");
            harness.modify = true;
            InventoryClickEvent click = harness.click(0, InventoryAction.PICKUP_ALL);
            harness.manager.onClick(click);
            check(click.isCancelled(), "a conflicted inventory accepted new click edits before recovery");
            InventoryDragEvent drag = new InventoryDragEvent(harness.view, null, new MarkerItem(),
                    false, Map.of(0, new MarkerItem()));
            harness.manager.onDrag(drag);
            check(drag.isCancelled(), "a conflicted inventory accepted new drag edits before recovery");
            check(!harness.login(true).isAllowed(), "login ignored a conflict whose edits were not yet archived");
            check(store.saves == 1, "retrying conflict recovery attempted to save the stale snapshot again");
            check(store.recoveries == 2, "synchronous close handling duplicated a failing recovery attempt");
            int attempts = store.recoveries;
            store.recoveryFailure = null;

            check(harness.persist(), "failed recovery was not retried after storage recovered");
            check(store.saves == 1 && store.recoveries == attempts + 1,
                    "recovery retry repeated the stale save or duplicated its recovery archive");
            harness.checkRetainedEdits(store.recoveredContents);
            check(store.recoverySnapshot == harness.snapshot, "recovery retry discarded its original snapshot");
            check(!harness.dirty() && harness.sessions().isEmpty() && harness.viewers().isEmpty(),
                    "successful recovery retry retained an inspection lock");
            harness.checkLoginAllowedAtBothPhases();
            check(store.saves == 1 && store.recoveries == attempts + 1,
                    "subsequent login retried an already archived conflict");
        }
    }

    private static final class StubStore extends OfflinePlayerInventoryStore {
        IOException saveFailure;
        IOException recoveryFailure;
        int saves;
        int recoveries;
        Snapshot savedSnapshot;
        Snapshot recoverySnapshot;
        ItemStack[] savedContents;
        ItemStack[] recoveredContents;
        Path recoveryDirectory;
        Path recoveryPath;

        @Override
        public void save(Snapshot snapshot, ItemStack[] contents) throws IOException {
            saves++;
            savedSnapshot = snapshot;
            savedContents = contents.clone();
            if (saveFailure != null) throw saveFailure;
        }

        @Override
        public Path saveRecovery(Snapshot snapshot, ItemStack[] contents, Path directory) throws IOException {
            recoveries++;
            recoverySnapshot = snapshot;
            recoveredContents = contents.clone();
            recoveryDirectory = directory;
            if (recoveryFailure != null) throw recoveryFailure;
            recoveryPath = directory.resolve(snapshot.playerId() + "-recovered.dat");
            return recoveryPath;
        }
    }

    /** Identity markers avoid needing a live server to create real item components. */
    private static final class MarkerItem extends ItemStack {
    }

    private static final class Harness {
        final InventoryInspectionManager manager;
        final UUID targetId = UUID.randomUUID();
        final UUID viewerId = UUID.randomUUID();
        final Path recoveryDirectory = Path.of("/private/tmp", "inventory-recovery-regression-" + targetId);
        final List<String> logs = new ArrayList<>();
        final Inventory inventory;
        final Player viewer;
        final InventoryView view;
        final Object session;
        final OfflinePlayerInventoryStore.Snapshot snapshot;
        final ItemStack[] contents;
        final int slots;
        boolean modify;
        int closes;
        int closeEvents;

        Harness(boolean offline) throws Exception {
            this(offline, false);
        }

        Harness(boolean offline, boolean enderChest) throws Exception {
            this(offline, enderChest, new StubStore());
        }

        Harness(boolean offline, boolean enderChest, StubStore store) throws Exception {
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) { logs.add(record.getMessage()); }
                @Override
                public void flush() { }
                @Override
                public void close() { }
            });
            manager = new InventoryInspectionManager(null, store, logger, recoveryDirectory);
            slots = enderChest ? 27 : 41;
            contents = new ItemStack[enderChest ? 27 : 45];
            inventory = proxy(Inventory.class, (object, method, args) -> switch (method.getName()) {
                case "getSize" -> contents.length;
                case "getContents" -> contents.clone();
                case "equals" -> object == args[0];
                default -> defaultValue(method.getReturnType());
            });
            viewer = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> viewerId;
                case "hasPermission" -> modify;
                case "closeInventory" -> {
                    closes++;
                    fireCloseEvent();
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            });
            view = proxy(InventoryView.class, (object, method, args) -> switch (method.getName()) {
                case "getPlayer" -> viewer;
                case "getTopInventory", "getBottomInventory" -> inventory;
                case "convertSlot" -> args[0];
                default -> defaultValue(method.getReturnType());
            });

            Class<?> keyType = Class.forName(InventoryInspectionManager.class.getName() + "$Key");
            Constructor<?> keyConstructor = keyType.getDeclaredConstructor(UUID.class, boolean.class);
            keyConstructor.setAccessible(true);
            Object key = keyConstructor.newInstance(targetId, enderChest);
            if (offline) {
                Constructor<?> snapshotConstructor = OfflinePlayerInventoryStore.Snapshot.class.getDeclaredConstructors()[0];
                snapshotConstructor.setAccessible(true);
                snapshot = (OfflinePlayerInventoryStore.Snapshot) snapshotConstructor.newInstance(
                        targetId, "Target", enderChest, null, null, null, new ItemStack[slots]);
            } else {
                snapshot = null;
            }
            Class<?> sessionType = Class.forName(InventoryInspectionManager.class.getName() + "$Session");
            Constructor<?> sessionConstructor = sessionType.getDeclaredConstructors()[0];
            sessionConstructor.setAccessible(true);
            session = sessionConstructor.newInstance(key, viewer, inventory, slots,
                    enderChest ? "crabutilities.echest.modify" : "crabutilities.invsee.modify", snapshot);
            sessions().put(key, session);
            viewers().put(viewerId, session);
        }

        InventoryClickEvent click(int slot, InventoryAction action) {
            return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                    slot, ClickType.LEFT, action);
        }

        void edit() throws Exception {
            contents[0] = new MarkerItem();
            contents[slots - 1] = new MarkerItem();
            if (contents.length > slots) contents[slots] = new MarkerItem();
            field(session, "dirty").setBoolean(session, true);
        }

        void checkRetainedEdits(ItemStack[] retained) {
            check(retained.length == slots, "saving included padding or omitted editable slots");
            check(retained[0] == contents[0] && retained[slots - 1] == contents[slots - 1],
                    "saving lost pending edits in the first or last editable slot");
        }

        boolean persist() throws Exception {
            Method persist = InventoryInspectionManager.class.getDeclaredMethod("persist", session.getClass());
            persist.setAccessible(true);
            return (boolean) persist.invoke(manager, session);
        }

        PlayerConnectionValidateLoginEvent login(boolean configuration) {
            PlayerConnectionValidateLoginEvent event = new PlayerConnectionValidateLoginEvent(
                    connection(targetId, configuration), null);
            manager.onLogin(event);
            return event;
        }

        void checkLoginAllowedAtBothPhases() {
            check(login(false).isAllowed() && login(true).isAllowed(),
                    "a released inspection continued to block initial or final login validation");
        }

        private void fireCloseEvent() {
            closeEvents++;
            manager.onClose(new InventoryCloseEvent(view));
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> sessions() throws Exception {
            return (Map<Object, Object>) field(manager, "sessions").get(manager);
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> viewers() throws Exception {
            return (Map<Object, Object>) field(manager, "viewers").get(manager);
        }

        boolean dirty() throws Exception {
            return field(session, "dirty").getBoolean(session);
        }
    }

    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0D;
        if (type == float.class) return 0.0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        throw new AssertionError("Unsupported primitive: " + type);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
