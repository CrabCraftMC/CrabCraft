package crabcraft.net.crabUtilities.restrictedarea

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponents
import net.minecraft.world.food.Foods
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.Consumables
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Entity
import org.bukkit.entity.Arrow
import org.bukkit.entity.EnderPearl
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryView

import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object RestrictedAreaRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        // Standalone tests do not load data packs; supply components used by these item checks.
        Items.COOKED_BEEF.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
            .set(DataComponents.FOOD, Foods.COOKED_BEEF)
            .set(DataComponents.CONSUMABLE, Consumables.DEFAULT_FOOD).build())
        Items.DIAMOND_HOE.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY)
        verifyConfiguration()
        verifyPermissionChangesAreLive()
        verifyGriefProtectionInEveryWorld()
        verifyPersonalInventoryRemainsUsable()
        verifyNormalMovementAndCommands()
        verifyPassiveInteractionsStaySilent()
        VerificationReminderRegressionTest.main(args)
    }

    private fun verifyConfiguration() {
        RestrictedAreaRegressionTest::class.java.classLoader.getResourceAsStream("modules/gameplay.yml").use { input ->
            check(input != null, "bundled gameplay.yml is missing")
            val config = YamlConfiguration()
            config.loadFromString(String(input!!.readAllBytes(), StandardCharsets.UTF_8))
            check(!config.getBoolean("restricted-area.enabled", true), "protection is not opt-in")
            check(config.getString("restricted-area.bypass-permission", "") == RestrictedAreaSettings.DEFAULT_PERMISSION,
                "default permission changed")
            check(!config.contains("restricted-area.bounds") && !config.contains("restricted-area.world") &&
                !config.contains("restricted-area.return-location"), "obsolete settings remain in defaults")
        }
        val config = enabledConfig()
        check(RestrictedAreaSettings.load(config).enabled(), "protection still requires area settings")
        config.set("restricted-area.world", "missing-${UUID.randomUUID()}")
        config.set("restricted-area.bounds", "obsolete")
        config.set("restricted-area.return-location.y", 90.0)
        check(RestrictedAreaSettings.load(config).enabled(), "legacy settings disable grief protection")
    }

    private fun verifyPermissionChangesAreLive() {
        val settings = RestrictedAreaSettings.load(enabledConfig())
        val verified = AtomicBoolean()
        val operator = AtomicBoolean()
        val player = player(verified, operator, AtomicInteger(), proxy(World::class.java, emptyMap()))
        check(RestrictedAreaListener.isRestricted(player, settings), "unverified player was not restricted")
        verified.set(true)
        check(!RestrictedAreaListener.isRestricted(player, settings), "verification did not immediately grant access")
        verified.set(false)
        operator.set(true)
        check(!RestrictedAreaListener.isRestricted(player, settings), "operator was restricted")
        operator.set(false)
        check(RestrictedAreaListener.isRestricted(player, settings), "removing bypass did not restore protection")
        check(!RestrictedAreaListener.isRestricted(player, RestrictedAreaSettings.disabled()), "disabled feature still restricts players")
    }

    private fun verifyGriefProtectionInEveryWorld() {
        for (name in arrayOf("world", "world_nether", "world_the_end", "custom_${UUID.randomUUID()}")) {
            val listener = listener()
            val verified = AtomicBoolean()
            val player = player(verified, AtomicBoolean(), AtomicInteger(), proxy(World::class.java, mapOf("getName" to name)))
            val block = proxy(Block::class.java, emptyMap())
            val breaking = BlockBreakEvent(block, player)
            listener.onBlockBreak(breaking)
            check(breaking.isCancelled(), "block breaking was allowed in $name")
            val placing = BlockPlaceEvent(block, proxy(BlockState::class.java, emptyMap()), block, object : ItemStack() {}, player, true, EquipmentSlot.HAND)
            listener.onBlockPlace(placing)
            check(placing.isCancelled(), "block placement was allowed in $name")
            for (target in arrayOf(proxy(Entity::class.java, emptyMap()),
                player(AtomicBoolean(true), AtomicBoolean(), AtomicInteger(), player.getWorld()))) {
                val attack = PrePlayerAttackEntityEvent(player, target, true)
                listener.onPreAttack(attack)
                check(attack.isCancelled(), "unverified player could damage an entity")
                val interaction = PlayerInteractEntityEvent(player, target)
                listener.onInteractEntity(interaction)
                check(interaction.isCancelled(), "unverified player could alter an entity")
            }
            val arrow = ProjectileLaunchEvent(proxy(Arrow::class.java, mapOf("getShooter" to player)))
            listener.onProjectileLaunch(arrow)
            check(arrow.isCancelled(), "unverified player could launch a damaging projectile")
            val pearl = ProjectileLaunchEvent(proxy(EnderPearl::class.java, mapOf("getShooter" to player)))
            listener.onProjectileLaunch(pearl)
            check(!pearl.isCancelled(), "ordinary pearl travel was blocked")
            verified.set(true)
            val permitted = BlockBreakEvent(block, player)
            listener.onBlockBreak(permitted)
            check(!permitted.isCancelled(), "verified player still cannot build")
        }
    }

    private fun verifyPersonalInventoryRemainsUsable() {
        val listener = listener()
        val player = player(AtomicBoolean(), AtomicBoolean(), AtomicInteger(), proxy(World::class.java, emptyMap()))
        for (type in arrayOf(InventoryType.CRAFTING, InventoryType.CREATIVE, InventoryType.PLAYER,
            InventoryType.CHEST, InventoryType.HOPPER, InventoryType.SHULKER_BOX)) {
            val inventory = proxy(Inventory::class.java, mapOf("getType" to type))
            val view = proxy(InventoryView::class.java, mapOf("getType" to type, "getPlayer" to player, "getTopInventory" to inventory))
            val open = InventoryOpenEvent(view)
            listener.onInventoryOpen(open)
            val click = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
            listener.onInventoryClick(click)
            val personal = type == InventoryType.CRAFTING || type == InventoryType.CREATIVE || type == InventoryType.PLAYER
            check(open.isCancelled() != personal, "incorrect inventory access for $type")
            check(click.isCancelled() != personal, "incorrect inventory manipulation for $type")
        }
    }

    private fun verifyNormalMovementAndCommands() {
        val listener = listener()
        val messages = AtomicInteger()
        val world = proxy(World::class.java, emptyMap())
        val player = player(AtomicBoolean(), AtomicBoolean(), messages, world)
        val from = Location(world, 0.0, 64.0, 0.0)
        val outside = Location(world, -100.0, 90.0, 0.0)
        val events = arrayOf<Event>(
            PlayerMoveEvent(player, from, outside),
            PlayerTeleportEvent(player, from, outside, PlayerTeleportEvent.TeleportCause.COMMAND),
            PlayerTeleportEvent(player, from, outside, PlayerTeleportEvent.TeleportCause.ENDER_PEARL),
            PlayerToggleFlightEvent(player, true), FoodLevelChangeEvent(player, 10, null),
            PlayerCommandPreprocessEvent(player, "/tp 0 90 0", emptySet()),
            PlayerCommandPreprocessEvent(player, "/msg Fict${UUID.randomUUID().toString().take(8)} hello", emptySet()))
        for (event in events) {
            dispatch(listener, event)
            check(!(event as Cancellable).isCancelled(), "unrelated behaviour was restricted: " + event.getEventName())
        }
        check(messages.get() == 0, "normal movement or commands sent verification reminders")
        for (method in RestrictedAreaListener::class.java.declaredMethods) {
            if (method.isAnnotationPresent(EventHandler::class.java)) {
                check(!method.parameterTypes[0].simpleName.contains("Chat"), "chat is still restricted")
            }
        }
    }

    private fun verifyPassiveInteractionsStaySilent() {
        val listener = listener()
        val messages = AtomicInteger()
        val player = player(AtomicBoolean(), AtomicBoolean(), messages, proxy(World::class.java, emptyMap()))
        val physical = PlayerInteractEvent(player, Action.PHYSICAL, null, proxy(Block::class.java, emptyMap()), BlockFace.UP)
        listener.onInteract(physical)
        check(physical.useInteractedBlock() == Event.Result.DENY, "farmland/pressure plate protection was removed")
        val air = PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.UP)
        val originalItemUse = air.useItemInHand()
        listener.onInteract(air)
        check(air.useItemInHand() == originalItemUse, "personal item use in the air was blocked")
        check(messages.get() == 0, "passive movement or air interaction sent a reminder")
        val breaking = PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK, null, proxy(Block::class.java, emptyMap()), BlockFace.UP)
        listener.onInteract(breaking)
        check(messages.get() == 1, "obvious grief attempt did not send a reminder")
        val chest = PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, proxy(Block::class.java, mapOf("getType" to Material.CHEST)), BlockFace.UP)
        listener.onInteract(chest)
        check(chest.useInteractedBlock() == Event.Result.DENY, "opening a chest with an empty hand was allowed")
        check(RestrictedAreaListener.isPersonalItem(Material.COOKED_BEEF), "food use was classified as griefing")
        check(!RestrictedAreaListener.isPersonalItem(Material.DIAMOND_HOE), "world-altering tool was allowed")
        val food = object : PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null,
            proxy(Block::class.java, mapOf("getType" to Material.STONE)), BlockFace.UP) {
            override fun hasItem(): Boolean = true
            override fun getMaterial(): Material = Material.COOKED_BEEF
        }
        listener.onInteract(food)
        check(food.useItemInHand() != Event.Result.DENY, "eating while looking at a block was prevented")
    }

    private fun dispatch(listener: RestrictedAreaListener, event: Event) {
        for (method in RestrictedAreaListener::class.java.declaredMethods) {
            if (method.isAnnotationPresent(EventHandler::class.java) && method.parameterTypes[0].isInstance(event)) method.invoke(listener, event)
        }
    }

    private fun listener(): RestrictedAreaListener = RestrictedAreaListener(null, RestrictedAreaSettings.load(enabledConfig()))

    private fun enabledConfig(): YamlConfiguration = YamlConfiguration().apply {
        set("restricted-area.enabled", true)
        set("restricted-area.bypass-permission", "crabcraft.member")
    }

    private fun player(verified: AtomicBoolean, operator: AtomicBoolean, messages: AtomicInteger, world: World): Player {
        val id = UUID.randomUUID()
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world
                "hasPermission" -> verified.get()
                "isOp" -> operator.get()
                "sendMessage" -> { messages.incrementAndGet(); null }
                "teleport" -> throw AssertionError("protection attempted a corrective teleport")
                else -> defaultValue(method.returnType)
            }
        } as Player
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, values: Map<String, Any>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        if (values.containsKey(method.name)) values[method.name] else defaultValue(method.returnType)
    } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Char::class.javaPrimitiveType -> '\u0000'
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        else -> if (type.isPrimitive) 0.0 else null
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
