package crabcraft.net.crabUtilities.restrictedarea;

import crabcraft.net.crabUtilities.CrabUtilities;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerLecternPageChangeEvent;
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent;
import io.papermc.paper.event.player.PlayerToggleEntityAgeLockEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ArrowBodyCountChangeEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confines players without the configured permission to one cuboid and blocks
 * their non-combat actions. Permission checks are deliberately live so
 * LuckPerms changes take effect on the next attempted action without a cache
 * or reconnect.
 */
public final class RestrictedAreaListener implements Listener {

    private final CrabUtilities plugin;
    private final Set<UUID> correctiveTeleports = ConcurrentHashMap.newKeySet();
    private volatile RestrictedAreaSettings settings = RestrictedAreaSettings.disabled();

    public RestrictedAreaListener(final CrabUtilities plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        final RestrictedAreaSettings loaded;
        try {
            loaded = RestrictedAreaSettings.load(plugin.getConfig());
        } catch (IllegalArgumentException exception) {
            settings = RestrictedAreaSettings.disabled();
            plugin.getLogger().severe("Restricted area disabled: " + exception.getMessage());
            return;
        }

        if (loaded.enabled() && Bukkit.getWorld(loaded.area().world()) == null) {
            settings = RestrictedAreaSettings.disabled();
            plugin.getLogger().severe("Restricted area disabled: world '"
                    + loaded.area().world() + "' is not loaded");
            return;
        }
        settings = loaded;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Player player = event.getPlayer();
        final RestrictedAreaSettings current = settings;
        if (!isRestricted(player, current) || event.getTo() == null) {
            return;
        }

        if (player.isFlying()) {
            player.setFlying(false);
        }
        if (player.isGliding()) {
            player.setGliding(false);
        }

        final Location from = event.getFrom();
        final Location to = event.getTo();
        final RestrictedAreaSettings.MovementDecision decision = current.area().movementDecision(
                from.getWorld().getName(), from.getX(), from.getY(), from.getZ(),
                to.getWorld().getName(), to.getX(), to.getY(), to.getZ());
        if (decision == RestrictedAreaSettings.MovementDecision.ALLOW) {
            return;
        }
        if (decision == RestrictedAreaSettings.MovementDecision.BLOCK) {
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
                    to.getYaw(), to.getPitch()));
            return;
        }
        event.setTo(returnLocation(current));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (isRestricted(event.getPlayer())
                && !correctiveTeleports.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPortal(final PlayerPortalEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> ensureInside(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChanged(final PlayerChangedWorldEvent event) {
        ensureInside(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(final PlayerRespawnEvent event) {
        final RestrictedAreaSettings current = settings;
        if (isRestricted(event.getPlayer(), current)) {
            event.setRespawnLocation(returnLocation(current));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (!isRestricted(event.getPlayer())) {
            return;
        }
        if (isCombatItem(event.getMaterial())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.ALLOW);
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        if (isRestricted(event.getPlayer())
                || event.getRightClicked() instanceof Player target && isRestricted(target)) {
            event.setCancelled(true);
        }
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
        cancel(event, player(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(final InventoryClickEvent event) {
        cancel(event, player(event.getWhoClicked()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryDrag(final InventoryDragEvent event) {
        cancel(event, player(event.getWhoClicked()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreAttack(final PrePlayerAttackEntityEvent event) {
        if (isRestricted(event.getPlayer()) && !isPvpTarget(event.getAttacked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageByEntity(final EntityDamageByEntityEvent event) {
        if (isRestricted(responsiblePlayer(event.getDamager()))
                && !isPvpTarget(event.getEntity())) {
            event.setCancelled(true);
        }
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
        if (isRestricted(shooter) && !isCombatProjectile(event.getEntity())) {
            event.setCancelled(true);
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
    public void onResurrect(final EntityResurrectEvent event) {
        cancel(event, player(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArrowBodyCount(final ArrowBodyCountChangeEvent event) {
        cancel(event, player(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFoodLevel(final FoodLevelChangeEvent event) {
        cancel(event, player(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExhaustion(final EntityExhaustionEvent event) {
        cancel(event, player(event.getEntity()));
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleMove(final VehicleMoveEvent event) {
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player && isRestricted(player)) {
                event.getVehicle().removePassenger(player);
                ensureInside(player);
            }
        }
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
        cancel(event, player(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttemptPickup(final PlayerAttemptPickupItemEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupArrow(final PlayerPickupArrowEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        cancel(event, event.getPlayer());
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
        if (isRestricted(event.getPlayer())
                || event.getCaught() instanceof Player caught && isRestricted(caught)) {
            event.setCancelled(true);
        }
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
    public void onEditBook(final PlayerEditBookEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(final PlayerItemConsumeEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemMend(final PlayerItemMendEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onExperience(final PlayerExpChangeEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setAmount(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
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
    public void onRiptide(final PlayerRiptideEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStartFlying(final PlayerToggleFlightEvent event) {
        if (event.isFlying()) {
            cancel(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStartGliding(final EntityToggleGlideEvent event) {
        if (event.isGliding()) {
            cancel(event, player(event.getEntity()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        cancel(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
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
    public void onLecternPage(final PlayerLecternPageChangeEvent event) {
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
        return player != null && current.enabled() && !player.hasPermission(current.permission());
    }

    static boolean isCombatItem(final Material material) {
        return material == Material.BOW
                || material == Material.CROSSBOW
                || material == Material.TRIDENT
                || material == Material.SHIELD;
    }

    static boolean isPvpTarget(final Entity entity) {
        return entity instanceof Player;
    }

    private static boolean isCombatProjectile(final Projectile projectile) {
        return projectile instanceof AbstractArrow || projectile instanceof Firework;
    }

    private void cancel(final Cancellable event, final Player player) {
        if (isRestricted(player)) {
            event.setCancelled(true);
        }
    }

    private void ensureInside(final Player player) {
        final RestrictedAreaSettings current = settings;
        if (!isRestricted(player, current)
                || current.area().contains(
                        player.getWorld().getName(), player.getX(), player.getY(), player.getZ())) {
            return;
        }

        correctiveTeleports.add(player.getUniqueId());
        try {
            player.teleport(returnLocation(current), PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            correctiveTeleports.remove(player.getUniqueId());
        }
    }

    private static Location returnLocation(final RestrictedAreaSettings current) {
        final World world = Bukkit.getWorld(current.area().world());
        final RestrictedAreaSettings.ReturnPoint point = current.returnPoint();
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
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
