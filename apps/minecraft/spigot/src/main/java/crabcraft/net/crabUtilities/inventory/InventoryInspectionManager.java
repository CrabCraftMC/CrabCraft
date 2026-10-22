package crabcraft.net.crabUtilities.inventory;

import crabcraft.net.crabUtilities.CrabMessages;
import crabcraft.net.crabUtilities.CrabUtilities;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Logger;

/** Inventory inspection stays on the main thread, including offline file writes and login handover. */
public final class InventoryInspectionManager implements Listener, CommandExecutor, TabCompleter {
    private final CrabUtilities plugin;
    private final RegisteredPlayerDirectory directory;
    private final OfflinePlayerInventoryStore store;
    private final Logger logger;
    private final Path recoveryDirectory;
    private final Map<UUID, Session> viewers = new HashMap<>();
    private final Map<Key, Session> sessions = new HashMap<>();
    private BukkitTask saveTask;

    public InventoryInspectionManager(CrabUtilities plugin) {
        this(plugin, new OfflinePlayerInventoryStore(), plugin.getLogger(),
                plugin.getDataFolder().toPath().resolve("inventory-recovery"));
    }

    InventoryInspectionManager(CrabUtilities plugin, OfflinePlayerInventoryStore store,
                               Logger logger, Path recoveryDirectory) {
        this.plugin = plugin;
        this.directory = new RegisteredPlayerDirectory(plugin);
        this.store = store;
        this.logger = logger;
        this.recoveryDirectory = recoveryDirectory;
    }

    public void start() {
        directory.start();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Session session : List.copyOf(sessions.values())) {
                if (session.snapshot != null && session.dirty) persist(session);
            }
        }, 1L, 1L);
    }

    public void shutdown() {
        for (Session session : List.copyOf(sessions.values())) close(session);
        if (saveTask != null) saveTask.cancel();
        directory.shutdown();
        HandlerList.unregisterAll(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(CrabMessages.error("Open inventories in-game."));
            return true;
        }
        boolean enderChest = command.getName().equals("echest");
        String permission = enderChest ? "crabutilities.echest" : "crabutilities.invsee";
        if (!viewer.hasPermission(permission)) {
            viewer.sendMessage(CrabMessages.error("You do not have permission to use this command."));
            return true;
        }
        if (args.length > 1 || (!enderChest && args.length == 0)) {
            viewer.sendMessage(CrabMessages.error("Usage: /" + label + (enderChest ? " [player]" : " <player>")));
            return true;
        }
        RegisteredPlayerDirectory.Entry target = args.length == 0
                ? new RegisteredPlayerDirectory.Entry(viewer.getUniqueId(), viewer.getName())
                : resolve(args[0]);
        if (target == null) {
            viewer.sendMessage(CrabMessages.error("No registered or locally known player has that name."));
            return true;
        }
        boolean self = target.uuid().equals(viewer.getUniqueId());
        if (enderChest && !self && !viewer.hasPermission("crabutilities.echest.others")) {
            viewer.sendMessage(CrabMessages.error("You do not have permission to open another player's ender chest."));
            return true;
        }
        if (self) {
            if (enderChest) viewer.openInventory(viewer.getEnderChest());
            else viewer.sendMessage(CrabMessages.error("Use your inventory key to open your own inventory."));
            return true;
        }
        Key key = new Key(target.uuid(), enderChest);
        Session existing = sessions.values().stream()
                .filter(session -> session.key.playerId.equals(target.uuid())).findFirst().orElse(null);
        if (existing != null && existing.viewer != viewer) {
            viewer.sendMessage(CrabMessages.error("That inventory is already being inspected."));
            return true;
        }
        viewer.closeInventory();
        // An unsaved offline session keeps its lock until its write succeeds.
        if (sessions.values().stream().anyMatch(session -> session.key.playerId.equals(target.uuid()))) {
            viewer.sendMessage(CrabMessages.error("That inventory has unsaved changes. Try again once they have been saved."));
            return true;
        }
        try {
            Player online = Bukkit.getPlayer(target.uuid());
            OfflinePlayerInventoryStore.Snapshot snapshot = null;
            Inventory inventory;
            int slots = enderChest ? 27 : 41;
            if (online != null) {
                inventory = enderChest ? online.getEnderChest()
                        : new CraftInventory(new InspectionContainer(((CraftPlayer) online).getHandle().getInventory(), slots));
            } else {
                snapshot = store.load(target.uuid(), target.name(), enderChest);
                inventory = Bukkit.createInventory(null, enderChest ? 27 : 45,
                        CrabMessages.text((enderChest ? "Ender chest: " : "Inventory: ") + target.name()));
                ItemStack[] contents = snapshot.contents();
                for (int slot = 0; slot < slots; slot++) inventory.setItem(slot, contents[slot]);
            }
            if (!enderChest) {
                ItemStack padding = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                padding.editMeta(meta -> meta.displayName(CrabMessages.muted("Unused slot")));
                for (int slot = slots; slot < inventory.getSize(); slot++) inventory.setItem(slot, padding);
                viewer.sendMessage(CrabMessages.muted("Bottom row: boots, leggings, chestplate, helmet, offhand. Use normal clicks or dragging to edit."));
            }
            Session session = new Session(key, viewer, inventory, slots, permission + ".modify", snapshot);
            sessions.put(key, session);
            viewers.put(viewer.getUniqueId(), session);
            if (viewer.openInventory(inventory) == null) {
                release(session);
            } else if (online != null) {
                viewer.getOpenInventory().setTitle((enderChest ? "Ender chest: " : "Inventory: ") + target.name());
            }
        } catch (IOException e) {
            viewer.sendMessage(CrabMessages.error("Could not open that player's saved inventory: " + e.getMessage()));
            logger.warning("Inventory inspection failed for " + target.uuid() + ": " + e.getMessage());
        }
        return true;
    }

    private RegisteredPlayerDirectory.Entry resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new RegisteredPlayerDirectory.Entry(online.getUniqueId(), online.getName());
        var registered = directory.find(name);
        if (registered.isPresent()) return registered.get();
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null || cached.getName() == null ? null
                : new RegisteredPlayerDirectory.Entry(cached.getUniqueId(), cached.getName());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String permission = command.getName().equals("echest") ? "crabutilities.echest.others" : "crabutilities.invsee";
        if (args.length != 1 || !sender.hasPermission(permission)) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(directory.names(prefix));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(player.getName());
        }
        return new ArrayList<>(names);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Session session = viewers.get(event.getWhoClicked().getUniqueId());
        if (session == null || !event.getView().getTopInventory().equals(session.inventory)) return;
        if (session.conflicted || !event.getWhoClicked().hasPermission(session.modifyPermission)
                || isPadding(event.getRawSlot(), session.slots, session.inventory.getSize())
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlot() >= 0 && event.getRawSlot() < session.slots) session.dirty = true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Session session = viewers.get(event.getWhoClicked().getUniqueId());
        if (session == null || !event.getView().getTopInventory().equals(session.inventory)) return;
        if (session.conflicted || !event.getWhoClicked().hasPermission(session.modifyPermission)
                || event.getRawSlots().stream().anyMatch(slot -> isPadding(slot, session.slots, session.inventory.getSize()))) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < session.slots)) session.dirty = true;
    }

    static boolean isPadding(int rawSlot, int editableSlots, int size) {
        return rawSlot >= editableSlots && rawSlot < size;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Session session = viewers.get(event.getPlayer().getUniqueId());
        if (session == null || !event.getInventory().equals(session.inventory)) return;
        viewers.remove(event.getPlayer().getUniqueId());
        if (persist(session)) release(session);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerConnectionValidateLoginEvent event) {
        UUID playerId;
        if (event.getConnection() instanceof PlayerLoginConnection connection) {
            playerId = connection.getAuthenticatedProfile().getId();
        } else if (event.getConnection() instanceof PlayerConfigurationConnection connection) {
            playerId = connection.getProfile().getId();
        } else {
            return;
        }
        // Paper validates both login and the end of configuration. The latter
        // closes sessions opened while the player was configuring, before the
        // server reloads their inventory from disk and adds them online.
        for (Session session : List.copyOf(sessions.values())) {
            if (!session.key.playerId.equals(playerId) || session.snapshot == null) continue;
            close(session);
            if (sessions.containsKey(session.key)) {
                event.kickMessage(CrabMessages.error(
                        "Your inventory is being saved by a moderator. Please try again shortly."));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        closeTarget(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRespawn(PlayerRespawnEvent event) {
        closeTarget(event.getPlayer().getUniqueId());
    }

    private void closeTarget(UUID playerId) {
        for (Session session : List.copyOf(sessions.values())) {
            if (session.key.playerId.equals(playerId)) close(session);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        for (Session session : List.copyOf(sessions.values())) {
            if (session.key.playerId.equals(event.getPlayer().getUniqueId()) || session.viewer == event.getPlayer()) close(session);
        }
    }

    private boolean persist(Session session) {
        if (session.snapshot == null || !session.dirty) return true;
        if (session.conflicted) return recoverConflict(session);
        try {
            store.save(session.snapshot, Arrays.copyOf(session.inventory.getContents(), session.slots));
            session.dirty = false;
            session.writeFailed = false;
            if (!viewers.containsValue(session)) release(session);
            return true;
        } catch (OfflinePlayerInventoryStore.SnapshotConflictException e) {
            session.conflicted = true;
            session.writeFailed = false;
            return recoverConflict(session);
        } catch (IOException e) {
            if (!session.writeFailed) {
                session.viewer.sendMessage(CrabMessages.error("Could not save the inspected inventory. Changes are retained and saving will be retried."));
                logger.severe("Cannot save inventory for " + session.key.playerId + ": " + e.getMessage());
                session.writeFailed = true;
            }
            return false;
        }
    }

    private boolean recoverConflict(Session session) {
        try {
            Path recovery = store.saveRecovery(session.snapshot,
                    Arrays.copyOf(session.inventory.getContents(), session.slots), recoveryDirectory);
            session.dirty = false;
            boolean open = viewers.get(session.viewer.getUniqueId()) == session;
            release(session);
            if (open) session.viewer.closeInventory();
            session.viewer.sendMessage(CrabMessages.error("The player's saved data changed. Inspection closed; pending edits were saved for recovery. Reopen to load the current data."));
            logger.warning("Inventory conflict for " + session.key.playerId
                    + ": current player data preserved; pending edits saved to " + recovery);
            return true;
        } catch (IOException e) {
            if (!session.writeFailed) {
                session.viewer.sendMessage(CrabMessages.error("Could not save the conflicting inventory edits for recovery. Changes are retained and recovery saving will be retried."));
                logger.severe("Cannot save inventory recovery for " + session.key.playerId + ": " + e.getMessage());
                session.writeFailed = true;
            }
            return false;
        }
    }

    private void close(Session session) {
        if (viewers.remove(session.viewer.getUniqueId(), session)) session.viewer.closeInventory();
        if (persist(session)) release(session);
    }

    private void release(Session session) {
        viewers.remove(session.viewer.getUniqueId(), session);
        sessions.remove(session.key, session);
    }

    private record Key(UUID playerId, boolean enderChest) {}

    private static final class Session {
        final Key key;
        final Player viewer;
        final Inventory inventory;
        final int slots;
        final String modifyPermission;
        final OfflinePlayerInventoryStore.Snapshot snapshot;
        boolean dirty;
        boolean writeFailed;
        boolean conflicted;

        Session(Key key, Player viewer, Inventory inventory, int slots, String modifyPermission,
                OfflinePlayerInventoryStore.Snapshot snapshot) {
            this.key = key;
            this.viewer = viewer;
            this.inventory = inventory;
            this.slots = slots;
            this.modifyPermission = modifyPermission;
            this.snapshot = snapshot;
        }
    }
}
