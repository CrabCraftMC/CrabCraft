package crabcraft.net.crabUtilities.inventory;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

final class OfflinePlayerInventoryStoreRegressionTest {

    public static void main(String[] args) throws Exception {
        validatesStoredLists();
        validatesStoredSlots();
        preservesPlayerDataAndBackup();
        roundTripsItemsAndEquipment();
    }

    private static void validatesStoredLists() throws IOException {
        CompoundTag root = new CompoundTag();
        check(OfflinePlayerInventoryStore.slotList(root, "Inventory").isEmpty(),
                "an absent inventory should be treated as empty");
        check(!root.contains("Inventory"), "reading an absent inventory must not mutate player data");

        ListTag empty = new ListTag();
        root.put("Inventory", empty);
        check(OfflinePlayerInventoryStore.slotList(root, "Inventory").isEmpty(),
                "an empty inventory list should be accepted");

        ListTag inventory = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putByte("Slot", (byte) 5);
        inventory.add(item);
        root.put("Inventory", inventory);
        check(OfflinePlayerInventoryStore.slotList(root, "Inventory").equals(inventory),
                "a compound inventory list should be preserved");

        root.putString("Inventory", "corrupt");
        expectFailure(() -> OfflinePlayerInventoryStore.slotList(root, "Inventory"),
                "a non-list inventory must not silently become empty");
        ListTag malformed = new ListTag();
        malformed.add(IntTag.valueOf(1));
        root.put("Inventory", malformed);
        expectFailure(() -> OfflinePlayerInventoryStore.slotList(root, "Inventory"),
                "non-compound inventory entries must be rejected");
    }

    private static void validatesStoredSlots() throws IOException {
        for (int slot = 0; slot < 36; slot++) {
            check(OfflinePlayerInventoryStore.inventorySlot(slot, false) == slot,
                    "a valid inventory slot should keep its index");
        }
        for (int slot = 0; slot < 27; slot++) {
            check(OfflinePlayerInventoryStore.inventorySlot(slot, true) == slot,
                    "a valid ender chest slot should keep its index");
        }
        for (int slot : new int[]{-1, 36, 100, 103, 150, 255}) {
            expectFailure(() -> OfflinePlayerInventoryStore.inventorySlot(slot, false),
                    "unsupported inventory slots must be rejected: " + slot);
        }
        for (int slot : new int[]{-1, 27, 35, 100, 150, 255}) {
            expectFailure(() -> OfflinePlayerInventoryStore.inventorySlot(slot, true),
                    "unsupported ender chest slots must be rejected: " + slot);
        }
    }

    private static void preservesPlayerDataAndBackup() throws Exception {
        var directory = Files.createTempDirectory("offline-inventory-regression-");
        try {
            var target = directory.resolve("player.dat");
            var backup = directory.resolve("player.dat.crab-inventory-backup");
            CompoundTag original = new CompoundTag();
            original.putString("Dimension", "minecraft:the_nether");
            original.putInt("XpLevel", 42);
            CompoundTag persistentData = new CompoundTag();
            persistentData.putString("crabutilities:unrelated", "preserve me");
            original.put("BukkitValues", persistentData);
            original.put("EnderItems", new ListTag());
            NbtIo.writeCompressed(original, target);
            byte[] originalBytes = Files.readAllBytes(target);

            CompoundTag updated = original.copy();
            ListTag inventory = new ListTag();
            CompoundTag item = new CompoundTag();
            item.putByte("Slot", (byte) 8);
            item.putString("id", "minecraft:stone");
            inventory.add(item);
            updated.put("Inventory", inventory);
            byte[] savedBytes = OfflinePlayerInventoryStore.replaceFile(target, originalBytes, updated);

            check(Arrays.equals(savedBytes, Files.readAllBytes(target)),
                    "the saved byte snapshot must match the resulting player file");
            check(Arrays.equals(originalBytes, Files.readAllBytes(backup)),
                    "the backup must contain the exact original player file");
            CompoundTag saved = NbtIo.readCompressed(target, NbtAccounter.defaultQuota());
            check(saved.equals(updated), "saving inventory must preserve all unrelated player data");

            CompoundTag externallyChanged = saved.copy();
            externallyChanged.putInt("XpLevel", 43);
            NbtIo.writeCompressed(externallyChanged, target);
            byte[] externalBytes = Files.readAllBytes(target);
            expectConflict(() -> OfflinePlayerInventoryStore.replaceFile(target, savedBytes, updated),
                    "saving a stale snapshot must not overwrite an external player-data change");
            check(Arrays.equals(externalBytes, Files.readAllBytes(target)),
                    "refusing a stale save must preserve the externally changed player file");
            check(Arrays.equals(originalBytes, Files.readAllBytes(backup)),
                    "refusing a stale save must preserve the existing original-file backup");
            try (var files = Files.list(directory)) {
                check(files.count() == 2, "saving or refusing a save must not leave temporary files behind");
            }

            UUID playerId = UUID.randomUUID();
            var recoveryDirectory = directory.resolve("inventory-recovery");
            var recovery = OfflinePlayerInventoryStore.writeRecovery(recoveryDirectory, playerId, false, updated);
            check(NbtIo.readCompressed(recovery, NbtAccounter.defaultQuota()).equals(updated),
                    "recovery must retain the edited inventory and original player metadata");
            check(recovery.getFileName().toString().startsWith(playerId + "-inventory-"),
                    "a recovery file must identify the player and inventory type");
            var secondRecovery = OfflinePlayerInventoryStore.writeRecovery(recoveryDirectory, playerId, true, externallyChanged);
            check(!secondRecovery.equals(recovery)
                            && secondRecovery.getFileName().toString().startsWith(playerId + "-enderchest-"),
                    "each recovery must have its own file and identify the ender chest");
            check(NbtIo.readCompressed(recovery, NbtAccounter.defaultQuota()).equals(updated),
                    "a subsequent recovery must not overwrite earlier pending edits");
            check(Arrays.equals(externalBytes, Files.readAllBytes(target)),
                    "archiving pending edits must preserve the externally changed player file");
            check(Arrays.equals(originalBytes, Files.readAllBytes(backup)),
                    "archiving pending edits must preserve the existing backup");

            expectConflict(() -> OfflinePlayerInventoryStore.replaceFile(target, null, updated),
                    "an unexpectedly created player file must be a snapshot conflict");
            Files.delete(target);
            expectConflict(() -> OfflinePlayerInventoryStore.replaceFile(target, externalBytes, updated),
                    "a deleted player file must be a snapshot conflict rather than a retryable read failure");
            check(!Files.exists(target), "conflict detection must not recreate an externally deleted file");
            try {
                OfflinePlayerInventoryStore.replaceFile(directory, externalBytes, updated);
                throw new AssertionError("an unreadable player file must fail");
            } catch (OfflinePlayerInventoryStore.SnapshotConflictException e) {
                throw new AssertionError("ordinary read failures must remain retryable", e);
            } catch (IOException expected) {
                // Reading a directory fails without proving that snapshot contents changed.
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void roundTripsItemsAndEquipment() throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(components -> components.apply());
        RegistryOps<Tag> operations = RegistryOps.create(NbtOps.INSTANCE, registries);
        var customItem = new net.minecraft.world.item.ItemStack(Items.DIAMOND_SWORD);
        customItem.set(DataComponents.CUSTOM_NAME, Component.literal("Moderator's test sword"));
        customItem.set(DataComponents.DAMAGE, 17);
        CompoundTag customData = new CompoundTag();
        customData.putString("crabutilities:test", "keep custom components");
        customData.putIntArray("values", new int[]{1, 7, 42});
        customItem.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));
        var equipmentItems = new net.minecraft.world.item.ItemStack[]{
                new net.minecraft.world.item.ItemStack(Items.DIAMOND_BOOTS),
                new net.minecraft.world.item.ItemStack(Items.DIAMOND_LEGGINGS),
                new net.minecraft.world.item.ItemStack(Items.DIAMOND_CHESTPLATE),
                new net.minecraft.world.item.ItemStack(Items.DIAMOND_HELMET),
                new net.minecraft.world.item.ItemStack(Items.TOTEM_OF_UNDYING)
        };
        String[] equipmentKeys = {"feet", "legs", "chest", "head", "offhand"};
        CompoundTag original = new CompoundTag();
        original.putString("Dimension", "minecraft:the_end");
        original.putInt("XpLevel", 73);
        CompoundTag persistentData = new CompoundTag();
        persistentData.putString("crabutilities:unrelated", "keep persistent data");
        original.put("BukkitValues", persistentData);
        ListTag inventory = new ListTag();
        inventory.add(ItemStackWithSlot.CODEC.encodeStart(operations,
                new ItemStackWithSlot(0, new net.minecraft.world.item.ItemStack(Items.DIAMOND, 23))).getOrThrow());
        inventory.add(ItemStackWithSlot.CODEC.encodeStart(operations,
                new ItemStackWithSlot(35, customItem)).getOrThrow());
        original.put("Inventory", inventory);
        CompoundTag equipment = new CompoundTag();
        for (int index = 0; index < equipmentKeys.length; index++) {
            equipment.put(equipmentKeys[index], encodeItem(equipmentItems[index], operations));
        }
        equipment.put("body", encodeItem(new net.minecraft.world.item.ItemStack(Items.WOLF_ARMOR), operations));
        equipment.put("saddle", encodeItem(new net.minecraft.world.item.ItemStack(Items.SADDLE), operations));
        original.put("equipment", equipment);
        ListTag enderItems = new ListTag();
        enderItems.add(ItemStackWithSlot.CODEC.encodeStart(operations,
                new ItemStackWithSlot(0, customItem)).getOrThrow());
        enderItems.add(ItemStackWithSlot.CODEC.encodeStart(operations,
                new ItemStackWithSlot(26, new net.minecraft.world.item.ItemStack(Items.EMERALD, 41))).getOrThrow());
        original.put("EnderItems", enderItems);

        ItemStack[] contents = OfflinePlayerInventoryStore.readContents(original, false, operations);
        check(contents.length == 41, "the editable inventory must include armour and offhand slots");
        checkItem(contents[0], new net.minecraft.world.item.ItemStack(Items.DIAMOND, 23), "first storage slot");
        checkItem(contents[35], customItem, "last storage slot and custom components");
        check(contents[1] == null && contents[34] == null, "empty storage slots must stay empty");
        for (int index = 0; index < equipmentItems.length; index++) {
            checkItem(contents[36 + index], equipmentItems[index], equipmentKeys[index] + " equipment slot");
        }
        CompoundTag updated = original.copy();
        OfflinePlayerInventoryStore.writeContents(updated, false, contents, operations);
        check(updated.equals(original), "an inventory round-trip must preserve item components and unrelated data");

        contents[0] = null;
        contents[36] = null;
        contents[40] = CraftItemStack.asBukkitCopy(customItem);
        OfflinePlayerInventoryStore.writeContents(updated, false, contents, operations);
        ItemStack[] edited = OfflinePlayerInventoryStore.readContents(updated, false, operations);
        check(edited[0] == null && edited[36] == null, "clearing storage or armour must remove the saved item");
        checkItem(edited[40], customItem, "edited offhand and custom components");
        CompoundTag editedEquipment = updated.getCompound("equipment").orElseThrow();
        check(!editedEquipment.contains("feet"), "clearing armour must remove its equipment key");
        check(equipment.get("body").equals(editedEquipment.get("body"))
                        && equipment.get("saddle").equals(editedEquipment.get("saddle")),
                "editing player equipment must preserve body and saddle equipment");
        check(updated.get("EnderItems").equals(original.get("EnderItems")),
                "editing the inventory must preserve the ender chest");

        ItemStack[] enderContents = OfflinePlayerInventoryStore.readContents(original, true, operations);
        check(enderContents.length == 27, "the ender chest must have 27 editable slots");
        checkItem(enderContents[0], customItem, "first ender chest slot and custom components");
        checkItem(enderContents[26], new net.minecraft.world.item.ItemStack(Items.EMERALD, 41),
                "last ender chest slot");
        CompoundTag enderUpdated = original.copy();
        OfflinePlayerInventoryStore.writeContents(enderUpdated, true, enderContents, operations);
        check(enderUpdated.equals(original), "an ender chest round-trip must preserve inventory and equipment");
        enderContents[0] = null;
        OfflinePlayerInventoryStore.writeContents(enderUpdated, true, enderContents, operations);
        check(OfflinePlayerInventoryStore.readContents(enderUpdated, true, operations)[0] == null,
                "clearing an ender chest slot must remove the saved item");
        check(enderUpdated.get("Inventory").equals(original.get("Inventory"))
                        && enderUpdated.get("equipment").equals(equipment),
                "editing the ender chest must preserve storage and all equipment");

        CompoundTag duplicateSlots = original.copy();
        ListTag duplicates = inventory.copy();
        duplicates.add(inventory.get(0).copy());
        duplicateSlots.put("Inventory", duplicates);
        expectFailure(() -> OfflinePlayerInventoryStore.readContents(duplicateSlots, false, operations),
                "duplicate saved slots must not silently discard an item");
        CompoundTag malformedEquipment = original.copy();
        malformedEquipment.putString("equipment", "corrupt");
        expectFailure(() -> OfflinePlayerInventoryStore.readContents(malformedEquipment, false, operations),
                "invalid equipment must not silently discard armour and offhand items");
        CompoundTag malformedItem = original.copy();
        ListTag invalidItems = inventory.copy();
        CompoundTag invalidItem = (CompoundTag) invalidItems.get(1);
        invalidItem.getCompound("components").orElseThrow().putString("minecraft:damage", "corrupt");
        malformedItem.put("Inventory", invalidItems);
        expectFailure(() -> OfflinePlayerInventoryStore.readContents(malformedItem, false, operations),
                "invalid item components must reject the inventory instead of accepting a partial codec result");
    }

    private static Tag encodeItem(net.minecraft.world.item.ItemStack item, RegistryOps<Tag> operations) {
        return net.minecraft.world.item.ItemStack.CODEC.encodeStart(operations, item).getOrThrow();
    }

    private static void checkItem(ItemStack actual, net.minecraft.world.item.ItemStack expected, String description) {
        check(net.minecraft.world.item.ItemStack.matches(CraftItemStack.asNMSCopy(actual), expected),
                "item round-trip changed the " + description);
    }

    private static void expectFailure(IoAction action, String message) throws IOException {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectConflict(IoAction action, String message) throws IOException {
        try {
            action.run();
        } catch (OfflinePlayerInventoryStore.SnapshotConflictException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface IoAction {
        void run() throws IOException;
    }
}
