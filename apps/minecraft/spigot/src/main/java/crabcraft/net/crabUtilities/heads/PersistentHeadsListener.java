package crabcraft.net.crabUtilities.heads;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
import org.bukkit.entity.Item;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps a player head's custom name and lore when it is broken and placed back
 * down. Vanilla drops a plain head, discarding whatever name/lore the item had;
 * this stores the placed item in the skull block's persistent data and restores
 * it onto the drop.
 *
 * <p>Beyond the normal break path it also guards the common ways a head can be
 * destroyed without a {@link BlockDropItemEvent} — flowing water, a placed water
 * bucket, and explosions — so the name/lore can't be stripped by griefing.
 *
 * <p>Disabled by default; the config is read live so a reload toggles it
 * without re-registration.
 *
 * <p>Ported from PaperTweaks' {@code PersistentHeads} module (VanillaTweaks
 * datapack). The upstream stores name and lore as separate component values; to
 * avoid a bespoke component data type this stores the whole placed item's bytes
 * (Base64) and restores from that, which also preserves the head's texture.
 */
public class PersistentHeadsListener implements Listener {

    private final CrabUtilities plugin;
    private final NamespacedKey headItemKey;

    public PersistentHeadsListener(final CrabUtilities plugin) {
        this.plugin = plugin;
        this.headItemKey = new NamespacedKey(plugin, "head_item");
    }

    private boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("tweaks.persistent-heads.enabled", false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        final ItemStack hand = event.getItemInHand();
        if (hand.getType() != Material.PLAYER_HEAD) {
            return;
        }
        final ItemMeta meta = hand.getItemMeta();
        if (meta == null || (!meta.hasDisplayName() && !meta.hasLore())) {
            return; // nothing worth preserving
        }
        if (!(event.getBlockPlaced().getState(true) instanceof final TileState state)) {
            return;
        }
        final String encoded = Base64.getEncoder().encodeToString(hand.asOne().serializeAsBytes());
        state.getPersistentDataContainer().set(this.headItemKey, PersistentDataType.STRING, encoded);
        if (state.isSnapshot()) {
            state.update();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDropItem(final BlockDropItemEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        final Material type = event.getBlockState().getType();
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) {
            return;
        }
        if (!(event.getBlockState() instanceof final TileState state)) {
            return;
        }
        final @Nullable ItemStack stored = this.readStored(state);
        if (stored == null) {
            return;
        }
        for (final Item item : event.getItems()) {
            if (item.getItemStack().getType() == Material.PLAYER_HEAD) {
                item.setItemStack(stored.asOne());
            }
        }
    }

    /** Prevents stripping the head's data by emptying a water bucket onto it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (this.isEnabled()) {
            this.handleBlock(event.getBlock(), event, false);
        }
    }

    /** Prevents stripping the head's data with flowing water. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLiquidFlow(final BlockFromToEvent event) {
        if (this.isEnabled()) {
            this.handleBlock(event.getToBlock(), event, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        if (this.isEnabled()) {
            this.handleExplosion(event.blockList(), event.getYield());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        if (this.isEnabled()) {
            this.handleExplosion(event.blockList(), event.getYield());
        }
    }

    private void handleExplosion(final List<Block> blocks, final float yield) {
        final Iterator<Block> iterator = blocks.iterator();
        while (iterator.hasNext()) {
            final Block block = iterator.next();
            // Cheap type check before touching block.getState() (an allocation).
            if (isPlayerHead(block.getType()) && ThreadLocalRandom.current().nextFloat() <= yield) {
                // Drop the restored head ourselves and take the block out of the
                // explosion so vanilla doesn't also drop a stripped one.
                this.handleBlock(block, null, false);
                iterator.remove();
            }
        }
    }

    /**
     * If {@code block} is a player-head skull carrying our stored item, remove
     * it and re-drop the preserved head a tick later, optionally cancelling the
     * triggering event.
     */
    private void handleBlock(final Block block, final @Nullable Cancellable event, final boolean cancelEvent) {
        // Guard on the block type first: this runs from onLiquidFlow, which
        // fires on every liquid-spread event, so we must not allocate a
        // BlockState snapshot for the (overwhelmingly common) non-head case.
        if (!isPlayerHead(block.getType())) {
            return;
        }
        if (!(block.getState() instanceof final Skull skull)) {
            return;
        }
        final @Nullable ItemStack stored = this.readStored(skull);
        if (stored == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin,
                () -> block.getWorld().dropItemNaturally(block.getLocation(), stored.asOne()), 1L);
        block.setType(Material.AIR);
        if (cancelEvent && event != null) {
            event.setCancelled(true);
        }
    }

    private static boolean isPlayerHead(final Material type) {
        return type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD;
    }

    private @Nullable ItemStack readStored(final TileState state) {
        final @Nullable String encoded =
                state.getPersistentDataContainer().get(this.headItemKey, PersistentDataType.STRING);
        if (encoded == null) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (final RuntimeException ex) {
            // Corrupt data, or bytes written by an incompatible item format
            // (e.g. after a Minecraft version upgrade) — treat as "no data"
            // rather than letting it escape the event handler.
            this.plugin.getLogger().warning("Failed to read stored head data: " + ex.getMessage());
            return null;
        }
    }
}
