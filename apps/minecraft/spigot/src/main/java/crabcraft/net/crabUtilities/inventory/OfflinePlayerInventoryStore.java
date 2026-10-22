package crabcraft.net.crabUtilities.inventory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.ItemStackWithSlot;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/** Reads and changes offline player inventories without constructing a temporary player entity. */
public class OfflinePlayerInventoryStore {
    private static final String[] EQUIPMENT_KEYS = {"feet", "legs", "chest", "head", "offhand"};

    public Snapshot load(UUID playerId, String playerName, boolean enderChest) throws IOException {
        requireOffline(playerId);
        MinecraftServer server = MinecraftServer.getServer();
        Path path = server.getPlayerList().playerIo.getPlayerDir().toPath().resolve(playerId + ".dat");
        byte[] original = Files.exists(path) ? Files.readAllBytes(path) : null;
        // Use Paper's normal player data conversion and .dat_old recovery before decoding items.
        CompoundTag root = server.getPlayerList().playerIo.load(new NameAndId(playerId, playerName))
                .orElseThrow(() -> new IOException("No readable player data is stored on this server."));
        requireUnchanged(path, original);
        int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        if (root.getIntOr("DataVersion", -1) > currentVersion) {
            throw new IOException("Player data was saved by a newer Minecraft version.");
        }
        root.putInt("DataVersion", currentVersion);
        return new Snapshot(playerId, playerName, enderChest, path, original, root,
                readContents(root, enderChest, operations()));
    }

    public void save(Snapshot snapshot, ItemStack[] contents) throws IOException {
        requireOffline(snapshot.playerId);
        CompoundTag updated = editedData(snapshot, contents);
        requireOffline(snapshot.playerId);
        byte[] saved = replaceFile(snapshot.path, snapshot.original, updated);
        snapshot.root = updated;
        snapshot.original = saved;
        snapshot.contents = copyItems(contents);
    }

    public Path saveRecovery(Snapshot snapshot, ItemStack[] contents, Path directory) throws IOException {
        return writeRecovery(directory, snapshot.playerId, snapshot.enderChest, editedData(snapshot, contents));
    }

    private static CompoundTag editedData(Snapshot snapshot, ItemStack[] contents) throws IOException {
        if (contents.length != (snapshot.enderChest ? 27 : 41)) {
            throw new IOException("Unexpected inventory size.");
        }
        CompoundTag updated = snapshot.root.copy();
        writeContents(updated, snapshot.enderChest, contents, operations());
        return updated;
    }

    private static RegistryOps<Tag> operations() {
        return RegistryOps.create(NbtOps.INSTANCE, MinecraftServer.getServer().registryAccess());
    }

    private static void requireOffline(UUID playerId) throws IOException {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Offline inventories must be accessed on the server thread.");
        }
        if (Bukkit.getPlayer(playerId) != null) {
            throw new IOException("The player has come online; reopen their inventory.");
        }
    }

    static ItemStack[] readContents(CompoundTag root, boolean enderChest, RegistryOps<Tag> operations)
            throws IOException {
        ItemStack[] contents = new ItemStack[enderChest ? 27 : 41];
        boolean[] seen = new boolean[enderChest ? 27 : 36];
        for (Tag entry : slotList(root, enderChest ? "EnderItems" : "Inventory")) {
            ItemStackWithSlot saved = decode(ItemStackWithSlot.CODEC, operations, entry);
            int slot = inventorySlot(saved.slot(), enderChest);
            if (seen[slot]) throw new IOException("Player inventory contains a duplicate slot: " + slot);
            seen[slot] = true;
            contents[slot] = CraftItemStack.asBukkitCopy(saved.stack());
        }
        if (!enderChest) {
            CompoundTag equipment = equipment(root);
            for (int index = 0; index < EQUIPMENT_KEYS.length; index++) {
                Tag saved = equipment.get(EQUIPMENT_KEYS[index]);
                if (saved != null) {
                    contents[36 + index] = CraftItemStack.asBukkitCopy(
                            decode(net.minecraft.world.item.ItemStack.CODEC, operations, saved));
                }
            }
        }
        return contents;
    }

    static void writeContents(CompoundTag root, boolean enderChest, ItemStack[] contents,
                              RegistryOps<Tag> operations) throws IOException {
        ListTag inventory = new ListTag();
        for (int slot = 0; slot < (enderChest ? 27 : 36); slot++) {
            net.minecraft.world.item.ItemStack item = CraftItemStack.asNMSCopy(contents[slot]);
            if (!item.isEmpty()) inventory.add(encode(ItemStackWithSlot.CODEC, operations,
                    new ItemStackWithSlot(slot, item)));
        }
        root.put(enderChest ? "EnderItems" : "Inventory", inventory);
        if (!enderChest) {
            CompoundTag equipment = equipment(root).copy();
            for (int index = 0; index < EQUIPMENT_KEYS.length; index++) {
                net.minecraft.world.item.ItemStack item = CraftItemStack.asNMSCopy(contents[36 + index]);
                if (item.isEmpty()) equipment.remove(EQUIPMENT_KEYS[index]);
                else equipment.put(EQUIPMENT_KEYS[index],
                        encode(net.minecraft.world.item.ItemStack.CODEC, operations, item));
            }
            if (equipment.isEmpty()) root.remove("equipment");
            else root.put("equipment", equipment);
        }
    }

    private static CompoundTag equipment(CompoundTag root) throws IOException {
        Tag equipment = root.get("equipment");
        if (equipment == null) return new CompoundTag();
        if (equipment instanceof CompoundTag compound) return compound;
        throw new IOException("Player equipment data is not a compound.");
    }

    static ListTag slotList(CompoundTag root, String key) throws IOException {
        Tag saved = root.get(key);
        if (saved == null) return new ListTag();
        if (!(saved instanceof ListTag list)) throw new IOException(key + " is not a list.");
        for (Tag entry : list) {
            if (!(entry instanceof CompoundTag)) throw new IOException(key + " contains invalid item data.");
        }
        return list;
    }

    static int inventorySlot(int savedSlot, boolean enderChest) throws IOException {
        if (savedSlot < 0 || savedSlot >= (enderChest ? 27 : 36)) {
            throw new IOException("Player inventory contains an unsupported slot: " + savedSlot);
        }
        return savedSlot;
    }

    private static <T> T decode(Codec<T> codec, RegistryOps<Tag> operations, Tag tag) throws IOException {
        return checked(codec.parse(operations, tag));
    }

    private static <T> Tag encode(Codec<T> codec, RegistryOps<Tag> operations, T item) throws IOException {
        return checked(codec.encodeStart(operations, item));
    }

    private static <T> T checked(DataResult<T> result) throws IOException {
        // Partial codec results can omit items or components, so accepting them would lose player data.
        if (result.error().isPresent()) throw new IOException("Cannot read or write an item: "
                + result.error().get().message());
        return result.result().orElseThrow(() -> new IOException("Cannot read or write an item."));
    }

    static byte[] replaceFile(Path target, byte[] expectedBytes, CompoundTag updated) throws IOException {
        requireUnchanged(target, expectedBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(updated, output);
        byte[] saved = output.toByteArray();
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".crab-", ".tmp");
        Path backupTemporary = null;
        try {
            writeAndSync(temporary, saved);
            if (expectedBytes != null) {
                backupTemporary = Files.createTempFile(target.getParent(), target.getFileName() + ".crab-", ".tmp");
                writeAndSync(backupTemporary, expectedBytes);
                requireUnchanged(target, expectedBytes);
                Files.move(backupTemporary, target.resolveSibling(target.getFileName() + ".crab-inventory-backup"),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            requireUnchanged(target, expectedBytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return saved;
        } finally {
            Files.deleteIfExists(temporary);
            if (backupTemporary != null) Files.deleteIfExists(backupTemporary);
        }
    }

    private static void writeAndSync(Path file, byte[] contents) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(contents);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    static Path writeRecovery(Path directory, UUID playerId, boolean enderChest, CompoundTag edited) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(edited, output);
        Files.createDirectories(directory);
        Path recovery = Files.createTempFile(directory,
                playerId + (enderChest ? "-enderchest-" : "-inventory-"), ".dat");
        try {
            writeAndSync(recovery, output.toByteArray());
            return recovery;
        } catch (IOException e) {
            Files.deleteIfExists(recovery);
            throw e;
        }
    }

    private static void requireUnchanged(Path target, byte[] expectedBytes) throws IOException {
        byte[] current;
        try {
            current = Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            current = null;
        }
        if (!Arrays.equals(expectedBytes, current)) {
            throw new SnapshotConflictException("Player data changed while the inventory was open; reopen it before editing.");
        }
    }

    public static final class SnapshotConflictException extends IOException {
        public SnapshotConflictException(String message) {
            super(message);
        }
    }

    private static ItemStack[] copyItems(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    public static final class Snapshot {
        private final UUID playerId;
        private final String playerName;
        private final boolean enderChest;
        private final Path path;
        private byte[] original;
        private CompoundTag root;
        private ItemStack[] contents;

        private Snapshot(UUID playerId, String playerName, boolean enderChest, Path path, byte[] original,
                         CompoundTag root, ItemStack[] contents) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.enderChest = enderChest;
            this.path = path;
            this.original = original;
            this.root = root;
            this.contents = contents;
        }

        public UUID playerId() { return playerId; }
        public String playerName() { return playerName; }
        public boolean enderChest() { return enderChest; }
        public ItemStack[] contents() { return copyItems(contents); }
    }
}
