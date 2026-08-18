package crabcraft.net.crabUtilities.model;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/** Implements reversible Nexo model merging for a helmet held beside its cosmetic token. */
public final class ModelCommand implements CommandExecutor, TabCompleter, Listener {

    private static final String MERGE_PERMISSION = "crabutilities.model.merge";
    private static final String SPLIT_PERMISSION = "crabutilities.model.split";

    private enum DestinationKind {
        MAIN_HAND,
        OFF_HAND,
        STORAGE
    }

    private record Destination(DestinationKind kind, int slot) {
        static Destination mainHand() {
            return new Destination(DestinationKind.MAIN_HAND, -1);
        }

        static Destination offHand() {
            return new Destination(DestinationKind.OFF_HAND, -1);
        }

        static Destination storage(int slot) {
            return new Destination(DestinationKind.STORAGE, slot);
        }
    }

    private record HeldPair(ItemStack target,
                            boolean targetInMainHand,
                            ItemStack cosmetic,
                            String cosmeticId) {
    }

    private static final class MergeInputException extends Exception {
        MergeInputException(String message) {
            super(message);
        }
    }

    private final JavaPlugin plugin;
    private final MergedModelCodec codec;
    private final @Nullable NexoItemLookup nexoItems;

    private ModelCommand(JavaPlugin plugin, @Nullable NexoItemLookup nexoItems) {
        this.plugin = plugin;
        this.codec = new MergedModelCodec(plugin);
        this.nexoItems = nexoItems;
    }

    /** Creates the command while keeping the optional Nexo API behind a guarded class boundary. */
    public static ModelCommand create(JavaPlugin plugin) {
        Plugin nexo = plugin.getServer().getPluginManager().getPlugin("Nexo");
        if (nexo == null || !nexo.isEnabled()) {
            plugin.getLogger().info("Nexo not detected — model merging is unavailable; splitting remains enabled.");
            return new ModelCommand(plugin, null);
        }
        try {
            NexoItemLookup lookup = NexoItemBridge.create();
            plugin.getLogger().info("Nexo detected — reversible model merging enabled.");
            return new ModelCommand(plugin, lookup);
        } catch (LinkageError error) {
            plugin.getLogger().warning(
                    "Nexo is present but its API could not be loaded; model merging is unavailable: "
                            + error.getMessage());
            return new ModelCommand(plugin, null);
        }
    }

    /** Returns the listener that protects embedded cosmetics across item transformations. */
    public Listener protectionListener() {
        return new MergedModelProtectionListener(codec);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(CrabMessages.error("Only players can use /model."));
            return true;
        }
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "merge" -> {
                if (args.length != 1) {
                    sendUsage(player);
                } else if (hasPermission(player, MERGE_PERMISSION)) {
                    merge(player);
                }
                yield true;
            }
            case "split" -> {
                if (args.length != 1) {
                    sendUsage(player);
                } else if (hasPermission(player, SPLIT_PERMISSION)) {
                    split(player);
                }
                yield true;
            }
            default -> {
                sendUsage(player);
                yield true;
            }
        };
    }

    private void merge(Player player) {
        HeldPair pair;
        try {
            pair = inspectHeldPair(player);
        } catch (MergeInputException exception) {
            player.sendMessage(CrabMessages.error(exception.getMessage()));
            return;
        }

        ItemStack merged;
        try {
            merged = codec.merge(pair.target(), pair.cosmetic(), pair.cosmeticId());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning(
                    "Could not merge items for " + player.getUniqueId() + ": " + exception.getMessage());
            player.sendMessage(CrabMessages.error("Merge failed; nothing changed."));
            return;
        }

        Component cosmeticName = itemName(pair.cosmetic());
        Component targetName = itemName(pair.target());
        ItemStack cosmeticRemainder = decrement(pair.cosmetic());
        PlayerInventory inventory = player.getInventory();
        ItemStack newMainHand = pair.targetInMainHand() ? merged : cosmeticRemainder;
        ItemStack newOffHand = pair.targetInMainHand() ? cosmeticRemainder : merged;
        if (!commitHands(player, inventory, newMainHand, newOffHand, "merge")) {
            player.sendMessage(CrabMessages.error("Merge failed; nothing changed."));
            return;
        }

        player.sendMessage(CrabMessages.success("Merged ")
                .append(cosmeticName)
                .append(CrabMessages.muted(" → "))
                .append(targetName)
                .append(CrabMessages.success(".")));
    }

    private void split(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack mainHand = inventory.getItemInMainHand();
        ItemStack offHand = inventory.getItemInOffHand();
        boolean mainMerged = codec.isMerged(mainHand);
        boolean offMerged = codec.isMerged(offHand);

        if (!mainMerged && !offMerged) {
            player.sendMessage(CrabMessages.error("Hold one merged helmet."));
            return;
        }
        if (mainMerged && offMerged) {
            player.sendMessage(CrabMessages.error("Hold only one merged helmet."));
            return;
        }

        boolean mergedInMainHand = mainMerged;
        ItemStack merged = mergedInMainHand ? mainHand : offHand;
        if (merged.getAmount() != 1) {
            player.sendMessage(CrabMessages.error("Split one merged helmet at a time."));
            return;
        }

        MergedModelCodec.StoredItems stored;
        ItemStack restored;
        try {
            stored = codec.read(merged);
            restored = codec.restoreTarget(merged, stored);
        } catch (MergedModelCodec.CorruptMergedItemException exception) {
            plugin.getLogger().warning(
                    "Could not read merged item held by " + player.getUniqueId()
                            + ": " + exception.getMessage());
            player.sendMessage(CrabMessages.error("Stored model data is damaged; nothing changed."));
            return;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning(
                    "Could not restore merged item held by " + player.getUniqueId()
                            + ": " + exception.getMessage());
            player.sendMessage(CrabMessages.error("Split failed; nothing changed."));
            return;
        }

        ItemStack cosmetic = stored.cosmetic().asOne();
        Destination destination = findDestination(inventory, mergedInMainHand, cosmetic);
        if (destination == null) {
            player.sendMessage(CrabMessages.error("Make room for the returned cosmetic."));
            return;
        }

        Component cosmeticName = itemName(cosmetic);
        Component targetName = itemName(restored);
        if (!commitSplit(player, inventory, mergedInMainHand, destination, cosmetic, restored)) {
            player.sendMessage(CrabMessages.error("Split failed; nothing changed."));
            return;
        }

        player.sendMessage(CrabMessages.success("Restored ")
                .append(targetName)
                .append(CrabMessages.muted(" + "))
                .append(cosmeticName)
                .append(CrabMessages.success(".")));
    }

    private HeldPair inspectHeldPair(Player player) throws MergeInputException {
        if (!isNexoAvailable()) {
            throw new MergeInputException("Custom-item support is unavailable.");
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack mainHand = inventory.getItemInMainHand();
        ItemStack offHand = inventory.getItemInOffHand();
        if (codec.isMerged(mainHand) || codec.isMerged(offHand)) {
            throw new MergeInputException("One item is already merged. Split it first.");
        }

        String mainId;
        String offId;
        try {
            mainId = mainHand.isEmpty() ? null : nexoItems.idFromItem(mainHand);
            offId = offHand.isEmpty() ? null : nexoItems.idFromItem(offHand);
        } catch (LinkageError | RuntimeException exception) {
            plugin.getLogger().warning("Nexo could not inspect held items: " + exception.getMessage());
            throw new MergeInputException("Couldn't inspect those custom items. Try again shortly.");
        }

        boolean mainIsNexo = mainId != null && !mainId.isBlank();
        boolean offIsNexo = offId != null && !offId.isBlank();
        if (mainIsNexo == offIsNexo) {
            throw new MergeInputException("Hold one custom cosmetic and one helmet, one in each hand.");
        }

        ItemStack cosmetic = mainIsNexo ? mainHand : offHand;
        ItemStack target = mainIsNexo ? offHand : mainHand;
        String cosmeticId = mainIsNexo ? mainId : offId;
        if (target.isEmpty()) {
            throw new MergeInputException("Hold a helmet in your other hand.");
        }
        if (target.getAmount() != 1) {
            throw new MergeInputException("Hold one helmet, not a stack.");
        }
        if (!codec.isHeadTarget(target)) {
            throw new MergeInputException("The other item must be head-slot equipment.");
        }
        if (!codec.hasApplicableModel(cosmetic)) {
            throw new MergeInputException("That custom item has no usable resource-pack model.");
        }
        if (!codec.hasCompatibleEquipmentModel(cosmetic)) {
            throw new MergeInputException("That custom item uses a different equipment slot.");
        }
        return new HeldPair(target, !mainIsNexo, cosmetic, cosmeticId);
    }

    private static ItemStack decrement(ItemStack item) {
        if (item.getAmount() == 1) {
            return ItemStack.empty();
        }
        ItemStack remainder = item.clone();
        remainder.setAmount(item.getAmount() - 1);
        return remainder;
    }

    private boolean commitHands(Player player,
                                PlayerInventory inventory,
                                ItemStack newMainHand,
                                ItemStack newOffHand,
                                String operation) {
        ItemStack oldMainHand = inventory.getItemInMainHand().clone();
        ItemStack oldOffHand = inventory.getItemInOffHand().clone();
        try {
            inventory.setItemInMainHand(newMainHand);
            inventory.setItemInOffHand(newOffHand);
            return true;
        } catch (RuntimeException exception) {
            rollbackHands(player, inventory, oldMainHand, oldOffHand, operation);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not commit model " + operation + " for " + player.getUniqueId(),
                    exception);
            return false;
        }
    }

    private boolean commitSplit(Player player,
                                PlayerInventory inventory,
                                boolean mergedInMainHand,
                                Destination destination,
                                ItemStack cosmetic,
                                ItemStack restored) {
        ItemStack oldMainHand = inventory.getItemInMainHand().clone();
        ItemStack oldOffHand = inventory.getItemInOffHand().clone();
        ItemStack oldStorage = destination.kind() == DestinationKind.STORAGE
                ? cloneOrNull(inventory.getItem(destination.slot()))
                : null;
        try {
            placeReturnedItem(inventory, destination, cosmetic);
            if (mergedInMainHand) {
                inventory.setItemInMainHand(restored);
            } else {
                inventory.setItemInOffHand(restored);
            }
            return true;
        } catch (RuntimeException exception) {
            if (destination.kind() == DestinationKind.STORAGE) {
                try {
                    inventory.setItem(destination.slot(), oldStorage);
                } catch (RuntimeException rollbackException) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Could not roll back model split storage for " + player.getUniqueId(),
                            rollbackException);
                }
            }
            rollbackHands(player, inventory, oldMainHand, oldOffHand, "split");
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not commit model split for " + player.getUniqueId(),
                    exception);
            return false;
        }
    }

    private void rollbackHands(Player player,
                               PlayerInventory inventory,
                               ItemStack oldMainHand,
                               ItemStack oldOffHand,
                               String operation) {
        try {
            inventory.setItemInMainHand(oldMainHand);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not roll back main hand after model " + operation
                            + " for " + player.getUniqueId(),
                    exception);
        }
        try {
            inventory.setItemInOffHand(oldOffHand);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not roll back off-hand after model " + operation
                            + " for " + player.getUniqueId(),
                    exception);
        }
    }

    private static @Nullable ItemStack cloneOrNull(@Nullable ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static @Nullable Destination findDestination(PlayerInventory inventory,
                                                         boolean mergedInMainHand,
                                                         ItemStack returned) {
        ItemStack otherHand = mergedInMainHand
                ? inventory.getItemInOffHand()
                : inventory.getItemInMainHand();
        if (canAccept(inventory, otherHand, returned)) {
            return mergedInMainHand ? Destination.offHand() : Destination.mainHand();
        }

        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (canAccept(inventory, storage[slot], returned)) {
                return Destination.storage(slot);
            }
        }
        return null;
    }

    private static boolean canAccept(PlayerInventory inventory,
                                     @Nullable ItemStack existing,
                                     ItemStack incoming) {
        if (existing == null || existing.isEmpty()) {
            return incoming.getAmount()
                    <= Math.min(inventory.getMaxStackSize(), incoming.getMaxStackSize());
        }
        if (!existing.isSimilar(incoming)) {
            return false;
        }
        int maximum = Math.min(
                inventory.getMaxStackSize(),
                Math.min(existing.getMaxStackSize(), incoming.getMaxStackSize()));
        return (long) existing.getAmount() + incoming.getAmount() <= maximum;
    }

    private static void placeReturnedItem(PlayerInventory inventory,
                                          Destination destination,
                                          ItemStack returned) {
        switch (destination.kind()) {
            case MAIN_HAND -> inventory.setItemInMainHand(combine(
                    inventory.getItemInMainHand(), returned));
            case OFF_HAND -> inventory.setItemInOffHand(combine(
                    inventory.getItemInOffHand(), returned));
            case STORAGE -> inventory.setItem(destination.slot(), combine(
                    inventory.getItem(destination.slot()), returned));
        }
    }

    private static ItemStack combine(@Nullable ItemStack existing, ItemStack incoming) {
        if (existing == null || existing.isEmpty()) {
            return incoming.asOne();
        }
        ItemStack combined = existing.clone();
        combined.setAmount(existing.getAmount() + incoming.getAmount());
        return combined;
    }

    private static Component itemName(ItemStack item) {
        return Component.text()
                .color(CrabMessages.HIGHLIGHT)
                .decoration(TextDecoration.ITALIC, false)
                .append(item.effectiveName())
                .hoverEvent(item.asHoverEvent(showItem -> showItem))
                .build();
    }

    private static boolean hasPermission(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        player.sendMessage(CrabMessages.error("You do not have permission to use that command."));
        return false;
    }

    private void sendUsage(Player player) {
        boolean sent = false;
        if (isNexoAvailable() && player.hasPermission(MERGE_PERMISSION)) {
            player.sendMessage(CrabMessages.highlight("/model merge")
                    .append(CrabMessages.muted(" — apply held custom model")));
            sent = true;
        }
        if (player.hasPermission(SPLIT_PERMISSION)) {
            player.sendMessage(CrabMessages.highlight("/model split")
                    .append(CrabMessages.muted(" — restore helmet and cosmetic")));
            sent = true;
        }
        if (!sent) {
            player.sendMessage(CrabMessages.error("You do not have permission to use this command."));
        }
    }

    private boolean isNexoAvailable() {
        Plugin nexo = plugin.getServer().getPluginManager().getPlugin("Nexo");
        return nexoItems != null && nexo != null && nexo.isEnabled();
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(2);
        if (isNexoAvailable()
                && sender.hasPermission(MERGE_PERMISSION)
                && "merge".startsWith(prefix)) {
            options.add("merge");
        }
        if (sender.hasPermission(SPLIT_PERMISSION) && "split".startsWith(prefix)) {
            options.add("split");
        }
        return options;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemBreak(PlayerItemBreakEvent event) {
        ItemStack broken = event.getBrokenItem();
        if (!codec.isMerged(broken)) {
            return;
        }

        ItemStack cosmetic;
        try {
            cosmetic = codec.read(broken).cosmetic().asOne();
        } catch (MergedModelCodec.CorruptMergedItemException | RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not recover the cosmetic from a broken merged item belonging to "
                            + event.getPlayer().getUniqueId(),
                    exception);
            event.getPlayer().sendMessage(CrabMessages.error(
                    "Helmet broke; cosmetic recovery failed."));
            return;
        }

        Player player = event.getPlayer();
        Destination destination = findStorageDestination(player.getInventory(), cosmetic);
        if (destination != null) {
            try {
                placeReturnedItem(player.getInventory(), destination, cosmetic);
                player.sendMessage(CrabMessages.warning("Recovered ")
                        .append(itemName(cosmetic))
                        .append(CrabMessages.warning(" from your broken helmet.")));
                return;
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not return the cosmetic from a broken item to " + player.getUniqueId(),
                        exception);
            }
        }

        player.getWorld().dropItemNaturally(player.getLocation(), cosmetic);
        player.sendMessage(CrabMessages.warning("Helmet broke; dropped ")
                .append(itemName(cosmetic))
                .append(CrabMessages.warning(" at your feet.")));
    }

    private static @Nullable Destination findStorageDestination(PlayerInventory inventory,
                                                                ItemStack returned) {
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (canAccept(inventory, storage[slot], returned)) {
                return Destination.storage(slot);
            }
        }
        return null;
    }
}
