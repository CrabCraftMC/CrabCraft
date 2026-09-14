package crabcraft.net.crabUtilities.restrictedarea

import crabcraft.net.crabUtilities.CrabUtilities
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent
import io.papermc.paper.event.player.PlayerLoomPatternSelectEvent
import io.papermc.paper.event.player.PlayerNameEntityEvent
import io.papermc.paper.event.player.PlayerOpenSignEvent
import io.papermc.paper.event.player.PlayerPurchaseEvent
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent
import io.papermc.paper.event.player.PlayerSwapWithEquipmentSlotEvent
import io.papermc.paper.event.player.PlayerToggleEntityAgeLockEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Material
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Entity
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Tameable
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.TradeSelectEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketEntityEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerHarvestBlockEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPickupArrowEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.event.player.PlayerTakeLecternBookEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.projectiles.ProjectileSource

/** Protects blocks, containers and entities from unverified players in every world. */
class RestrictedAreaListener(private val plugin: CrabUtilities?, initialSettings: RestrictedAreaSettings) : Listener {
    private val reminder = VerificationReminder()
    @Volatile private var settings = initialSettings

    constructor(plugin: CrabUtilities) : this(plugin, RestrictedAreaSettings.disabled()) {
        refresh()
    }

    fun refresh() {
        val loaded = try {
            RestrictedAreaSettings.load(plugin!!.getConfig())
        } catch (exception: IllegalArgumentException) {
            settings = RestrictedAreaSettings.disabled()
            plugin!!.getLogger().severe("Unverified-player protection disabled: " + exception.message)
            return
        }
        settings = loaded
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        reminder.forget(event.getPlayer().getUniqueId())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (!isRestricted(event.getPlayer())) {
            return
        }
        val action = event.getAction()
        if (action == Action.PHYSICAL) {
            // Prevent pressure plates and farmland trampling without spamming people who walk over them.
            event.setUseInteractedBlock(Event.Result.DENY)
            return
        }
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return
        }
        event.setUseInteractedBlock(Event.Result.DENY)
        val blockedItem = event.hasItem() && !isPersonalItem(event.getMaterial())
        if (action == Action.RIGHT_CLICK_BLOCK && blockedItem) {
            event.setUseItemInHand(Event.Result.DENY)
        }
        if (action == Action.LEFT_CLICK_BLOCK || blockedItem
                || event.getClickedBlock()?.getType()?.isInteractable() == true) {
            remind(event.getPlayer())
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        onInteractEntity(event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockDamage(event: BlockDamageEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockFertilise(event: BlockFertilizeEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        cancel(event, responsiblePlayer(event.getIgnitingEntity()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        if (!isPersonalInventory(event.getInventory().getType())) {
            cancel(event, player(event.getPlayer()))
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!isPersonalInventory(event.getView().getType())) {
            cancel(event, player(event.getWhoClicked()))
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (!isPersonalInventory(event.getView().getType())) {
            cancel(event, player(event.getWhoClicked()))
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPreAttack(event: PrePlayerAttackEntityEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        cancel(event, responsiblePlayer(event.getDamager()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        if ((event.getTarget() as? Player)?.let { isRestricted(it) } == true) {
            event.setCancelled(true)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val shooter = responsiblePlayer(event.getEntity())
        if (isRestricted(shooter) && event.getEntity() !is EnderPearl) {
            event.setCancelled(true)
            remind(shooter!!)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPotionSplash(event: PotionSplashEvent) {
        if (isRestricted(responsiblePlayer(event.getPotion()))) {
            event.setCancelled(true)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onAreaEffectCloud(event: AreaEffectCloudApplyEvent) {
        val cloud = event.getEntity()
        if (isRestricted(responsiblePlayer(cloud.getSource()))) {
            event.setCancelled(true)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityPlace(event: EntityPlaceEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        cancel(event, responsiblePlayer(event.getRemover()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        cancel(event, player(event.getOwner()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onMount(event: EntityMountEvent) {
        cancel(event, player(event.getEntity()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleEnter(event: VehicleEnterEvent) {
        cancel(event, player(event.getEntered()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleDamage(event: VehicleDamageEvent) {
        cancel(event, responsiblePlayer(event.getAttacker()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        cancel(event, responsiblePlayer(event.getAttacker()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLeash(event: PlayerLeashEntityEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onUnleash(event: PlayerUnleashEntityEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        cancelSilently(event, player(event.getEntity()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onAttemptPickup(event: PlayerAttemptPickupItemEvent) {
        cancelSilently(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPickupArrow(event: PlayerPickupArrowEvent) {
        cancelSilently(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onArmourStand(event: PlayerArmorStandManipulateEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBucketEntity(event: PlayerBucketEntityEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onShear(event: PlayerShearEntityEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHarvest(event: PlayerHarvestBlockEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTakeLecternBook(event: PlayerTakeLecternBookEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBedEnter(event: PlayerBedEnterEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onItemFrameChange(event: PlayerItemFrameChangeEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFlowerPot(event: PlayerFlowerPotManipulateEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLecternInsert(event: PlayerInsertLecternBookEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLoomSelect(event: PlayerLoomPatternSelectEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onStonecutterSelect(event: PlayerStonecutterRecipeSelectEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPurchase(event: PlayerPurchaseEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTradeSelect(event: TradeSelectEvent) {
        cancel(event, player(event.getWhoClicked()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBeaconEffect(event: PlayerChangeBeaconEffectEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onNameEntity(event: PlayerNameEntityEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onSwapEquipment(event: PlayerSwapWithEquipmentSlotEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onToggleAgeLock(event: PlayerToggleEntityAgeLockEvent) {
        cancel(event, event.getPlayer())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onOpenSign(event: PlayerOpenSignEvent) {
        cancel(event, event.getPlayer())
    }

    private fun isRestricted(player: Player?): Boolean = isRestricted(player, settings)

    private fun cancel(event: Cancellable, player: Player?) {
        if (isRestricted(player)) {
            event.setCancelled(true)
            remind(player!!)
        }
    }

    private fun cancelSilently(event: Cancellable, player: Player?) {
        if (isRestricted(player)) event.setCancelled(true)
    }

    private fun remind(player: Player) { reminder.send(player.getUniqueId(), player) }

    companion object {
        @JvmStatic
        fun isRestricted(player: Player?, current: RestrictedAreaSettings): Boolean = player != null && current.enabled() &&
            !player.isOp() && !player.hasPermission(current.permission())

        @JvmStatic
        fun isPersonalItem(material: Material): Boolean = material.isEdible() || material == Material.POTION ||
            material == Material.MILK_BUCKET || material == Material.SHIELD || material == Material.ENDER_PEARL

        @JvmStatic
        fun isPersonalInventory(type: InventoryType): Boolean = type == InventoryType.CRAFTING ||
            type == InventoryType.CREATIVE || type == InventoryType.PLAYER

        private fun responsiblePlayer(entity: Entity?): Player? {
            if (entity is Player) return entity
            if (entity is Projectile) return responsiblePlayer(entity.getShooter())
            if (entity is Tameable) return player(entity.getOwner())
            return null
        }

        private fun responsiblePlayer(source: ProjectileSource?): Player? =
            if (source is Entity) responsiblePlayer(source as Entity) else null

        private fun player(value: Any?): Player? = value as? Player
    }
}
