package crabcraft.net.crabUtilities.restrictedarea;

import crabcraft.net.crabUtilities.CrabUtilities;
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent;
import io.papermc.paper.event.player.PlayerToggleEntityAgeLockEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Material;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Protects blocks, containers, and entities from unverified players in every world.
 * Permission checks are live so verification takes effect without reconnecting.
 */
public final class RestrictedAreaListener implements Listener {

    private final CrabUtilities plugin;
    private final VerificationReminder reminder = new VerificationReminder();
    private volatile RestrictedAreaSettings settings = RestrictedAreaSettings.disabled();

    public RestrictedAreaListener(final CrabUtilities plugin) {
        this(plugin, RestrictedAreaSettings.disabled());
        refresh();
    }

    RestrictedAreaListener(final CrabUtilities plugin, final RestrictedAreaSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void refresh() {
        final RestrictedAreaSettings loaded;
        try {
            loaded = RestrictedAreaSettings.load(plugin.getConfig());
        } catch (IllegalArgumentException exception) {
            settings = RestrictedAreaSettings.disabled();
            plugin.getLogger().severe("Unverified-player protection disabled: " + exception.getMessage());
            return;
        }

        settings = loaded;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        reminder.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (!isRestricted(event.getPlayer())) {
            return;
        }
        final Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            // Prevent pressure plates and farmland trampling without spamming people who walk over them.
            event.setUseInteractedBlock(Event.Result.DENY);
            return;
        }
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        final boolean blockedItem = event.hasItem() && !isPersonalItem(event.getMaterial());
        if (action == Action.RIGHT_CLICK_BLOCK && blockedItem) {
            event.setUseItemInHand(Event.Result.DENY);
        }
        if (action == Action.LEFT_CLICK_BLOCK || blockedItem
                || event.getClickedBlock() != null && event.getClickedBlock().getType().isInteractable()) {
            remind(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(final PlayerInteractAtEntityEvent event) {
        onInteractEntity(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDamage(final BlockDamageEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockFertilise(final BlockFertilizeEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockIgnite(final BlockIgniteEvent event) {
        cancel(event, responsiblePlayer(event.getIgnitingEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(final SignChangeEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(final InventoryOpenEvent event) {
        if (!isPersonalInventory(event.getInventory().getType())) {
            cancel(event, player(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        if (!isPersonalInventory(event.getView().getType())) {
            cancel(event, player(event.getWhoClicked()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (!isPersonalInventory(event.getView().getType())) {
            cancel(event, player(event.getWhoClicked()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreAttack(final PrePlayerAttackEntityEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageByEntity(final EntityDamageByEntityEvent event) {
        cancel(event, responsiblePlayer(event.getDamager()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(final EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectileLaunch(final ProjectileLaunchEvent event) {
        final Player shooter = responsiblePlayer(event.getEntity());
        if (isRestricted(shooter) && !(event.getEntity() instanceof EnderPearl)) {
            event.setCancelled(true);
            remind(shooter);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPotionSplash(final PotionSplashEvent event) {
        if (isRestricted(responsiblePlayer(event.getPotion()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAreaEffectCloud(final AreaEffectCloudApplyEvent event) {
        final AreaEffectCloud cloud = event.getEntity();
        if (isRestricted(responsiblePlayer(cloud.getSource()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPlace(final EntityPlaceEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        cancel(event, responsiblePlayer(event.getRemover()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTame(final EntityTameEvent event) {
        cancel(event, player(event.getOwner()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMount(final EntityMountEvent event) {
        cancel(event, player(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(final VehicleEnterEvent event) {
        cancel(event, player(event.getEntered()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleDamage(final VehicleDamageEvent event) {
        cancel(event, responsiblePlayer(event.getAttacker()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleDestroy(final VehicleDestroyEvent event) {
        cancel(event, responsiblePlayer(event.getAttacker()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeash(final PlayerLeashEntityEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onUnleash(final PlayerUnleashEntityEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        cancelSilently(event, player(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttemptPickup(final PlayerAttemptPickupItemEvent event) {
        cancelSilently(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupArrow(final PlayerPickupArrowEvent event) {
        cancelSilently(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmourStand(final PlayerArmorStandManipulateEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEntity(final PlayerBucketEntityEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(final PlayerFishEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShear(final PlayerShearEntityEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHarvest(final PlayerHarvestBlockEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTakeLecternBook(final PlayerTakeLecternBookEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBedEnter(final PlayerBedEnterEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemFrameChange(final PlayerItemFrameChangeEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFlowerPot(final PlayerFlowerPotManipulateEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLecternInsert(final PlayerInsertLecternBookEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLoomSelect(final PlayerLoomPatternSelectEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStonecutterSelect(final PlayerStonecutterRecipeSelectEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPurchase(final PlayerPurchaseEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTradeSelect(final TradeSelectEvent event) {
        cancel(event, player(event.getWhoClicked()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBeaconEffect(final PlayerChangeBeaconEffectEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onNameEntity(final PlayerNameEntityEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapEquipment(final PlayerSwapWithEquipmentSlotEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onToggleAgeLock(final PlayerToggleEntityAgeLockEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpenSign(final PlayerOpenSignEvent event) {
        cancel(event, event.getPlayer());
    }

    private boolean isRestricted(final Player player) {
        return isRestricted(player, settings);
    }

    static boolean isRestricted(
            final Player player,
            final RestrictedAreaSettings current
    ) {
        return player != null && current.enabled()
                && !player.isOp() && !player.hasPermission(current.permission());
    }

    static boolean isPersonalItem(final Material material) {
        return material.isEdible() || material == Material.POTION || material == Material.MILK_BUCKET
                || material == Material.SHIELD || material == Material.ENDER_PEARL;
    }

    static boolean isPersonalInventory(final InventoryType type) {
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE || type == InventoryType.PLAYER;
    }

    private void cancel(final Cancellable event, final Player player) {
        if (isRestricted(player)) {
            event.setCancelled(true);
            remind(player);
        }
    }

    private void cancelSilently(final Cancellable event, final Player player) {
        if (isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    private void remind(final Player player) {
        reminder.send(player.getUniqueId(), player);
    }

    private static Player responsiblePlayer(final Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile) {
            return responsiblePlayer(projectile.getShooter());
        }
        if (entity instanceof Tameable tameable) {
            return player(tameable.getOwner());
        }
        return null;
    }

    private static Player responsiblePlayer(final ProjectileSource source) {
        if (source instanceof Entity entity) {
            return responsiblePlayer(entity);
        }
        return null;
    }

    private static Player player(final Object value) {
        return value instanceof Player player ? player : null;
    }
}
