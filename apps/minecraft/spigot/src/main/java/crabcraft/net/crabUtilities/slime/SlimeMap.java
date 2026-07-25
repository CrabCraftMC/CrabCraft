package crabcraft.net.crabUtilities.slime;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** A six-row slime-chunk map oriented in the direction the player is facing. */
final class SlimeMap implements InventoryHolder {

    private static final int WIDTH = 9;
    private static final int SIZE = 54;
    private static final int CENTER_COLUMN = 4;
    private static final int CENTER_ROW = 3;
    private static final int[] X_ROTATION = { 1, 0, -1, 0 };
    private static final int[] Z_ROTATION = { 0, 1, 0, -1 };

    private final Inventory inventory;

    private SlimeMap(Player player) {
        this.inventory = Bukkit.createInventory(
                this, SIZE, CrabMessages.accent("Slime Chunks"));
        populate(player);
    }

    static void open(Player player) {
        SlimeMap map = new SlimeMap(player);
        player.openInventory(map.inventory);
    }

    private void populate(Player player) {
        Location location = player.getLocation();
        World world = player.getWorld();
        int centerChunkX = location.getBlockX() >> 4;
        int centerChunkZ = location.getBlockZ() >> 4;

        for (int slot = 0; slot < SIZE; slot++) {
            ChunkOffset offset = chunkOffsetAt(location.getYaw(), slot);
            int chunkX = centerChunkX + offset.x();
            int chunkZ = centerChunkZ + offset.z();
            boolean currentChunk = offset.x() == 0 && offset.z() == 0;
            boolean slimeChunk = world.getChunkAt(chunkX, chunkZ, false).isSlimeChunk();
            inventory.setItem(slot, createMapItem(chunkX, chunkZ, currentChunk, slimeChunk));
        }
    }

    private static ItemStack createMapItem(int chunkX, int chunkZ, boolean currentChunk, boolean slimeChunk) {
        Material material = currentChunk
                ? Material.BLUE_STAINED_GLASS_PANE
                : slimeChunk ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        String position = "(" + blockX + ", " + blockZ + ")";
        meta.displayName(plain(currentChunk
                ? CrabMessages.highlight("You are here ")
                        .append(CrabMessages.text(position))
                : CrabMessages.text(position)));
        meta.lore(List.of(
                plain(slimeChunk
                        ? CrabMessages.success("Slime chunk")
                        : CrabMessages.error("Not a slime chunk")),
                plain(CrabMessages.muted("Chunk " + chunkX + ", " + chunkZ))));
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /** Converts one GUI slot into a world chunk offset using the nearest cardinal yaw. */
    static ChunkOffset chunkOffsetAt(float yaw, int slot) {
        if (slot < 0 || slot >= SIZE) {
            throw new IllegalArgumentException("slot must be between 0 and " + (SIZE - 1));
        }

        int screenX = slot % WIDTH - CENTER_COLUMN;
        int screenZ = slot / WIDTH - CENTER_ROW;
        int direction = Math.floorMod(Math.round(yaw / 90.0F) + 2, X_ROTATION.length);
        int xRotation = X_ROTATION[direction];
        int zRotation = Z_ROTATION[direction];

        int worldX = screenX * xRotation - screenZ * zRotation;
        int worldZ = screenX * zRotation + screenZ * xRotation;
        return new ChunkOffset(worldX, worldZ);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    record ChunkOffset(int x, int z) {}
}
