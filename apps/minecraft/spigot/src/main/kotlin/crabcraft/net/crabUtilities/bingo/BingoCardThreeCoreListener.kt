package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.block.VaultChangeStateEvent
import java.util.HashMap
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.type.Vault
import org.bukkit.entity.Fox
import org.bukkit.entity.Player
import org.bukkit.entity.Sheep
import org.bukkit.entity.SulfurCube
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the core Bingo #3 tasks. */
class BingoCardThreeCoreListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
) : AbstractBingoDetector() {
    private val sulfurOwnerKey = NamespacedKey(plugin, "bingo_card3_sulfur_owner")
    private val sulfurFedAtKey = NamespacedKey(plugin, "bingo_card3_sulfur_fed_at")
    private val foxTotemOwnerKey = NamespacedKey(plugin, "bingo_card3_fox_totem_owner")
    private val foxTotemDroppedAtKey = NamespacedKey(plugin, "bingo_card3_fox_totem_dropped_at")
    private val foxTotemPickedUpByKey = NamespacedKey(plugin, "bingo_card3_fox_totem_fox")
    private val playerResetAtMillis = HashMap<UUID, Long>()
    private var clearedAtMillis = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSulfurCubeInteracted(event: PlayerInteractEntityEvent) {
        val cube = event.rightClicked as? SulfurCube ?: return
        val player = event.player
        val used = player.inventory.getItem(event.hand)
        if (
            used.type == Material.TNT && !cube.canExplode() && tracking.test(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)
        ) {
            val playerId = player.uniqueId
            val cubeId = cube.uniqueId
            val token = attemptToken(playerId)
            Bukkit.getScheduler().runTask(plugin, Runnable { confirmSulfurCubeFed(playerId, cubeId, token) })
            return
        }
        if (
            (used.type == Material.FLINT_AND_STEEL || used.type == Material.FIRE_CHARGE) &&
                cube.canExplode() &&
                cube.fuseTicks < 0 &&
                isSulfurCubeOwnedBy(cube, player.uniqueId) &&
                tracking.test(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)
        ) {
            val playerId = player.uniqueId
            val cubeId = cube.uniqueId
            val token = attemptToken(playerId)
            Bukkit.getScheduler().runTask(plugin, Runnable { confirmSulfurCubeIgnited(playerId, cubeId, token) })
        }
    }

    private fun confirmSulfurCubeFed(playerId: UUID, cubeId: UUID, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val cube = Bukkit.getEntity(cubeId) as? SulfurCube ?: return
        if (
            !isCurrent(playerId, token) ||
                !hasAbsorbedTnt(cube.equipment.getItem(EquipmentSlot.BODY).type) ||
                !tracking.test(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)
        )
            return
        cube.persistentDataContainer.set(sulfurOwnerKey, PersistentDataType.STRING, playerId.toString())
        cube.persistentDataContainer.set(sulfurFedAtKey, PersistentDataType.LONG, System.currentTimeMillis())
    }

    private fun confirmSulfurCubeIgnited(playerId: UUID, cubeId: UUID, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId) ?: return
        val cube = Bukkit.getEntity(cubeId) as? SulfurCube ?: return
        if (
            !isCurrent(playerId, token) ||
                cube.fuseTicks < 0 ||
                !isSulfurCubeOwnedBy(cube, playerId) ||
                !tracking.test(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)
        )
            return
        clearSulfurAttribution(cube)
        completion.accept(player, BingoTask.SULFUR_CUBE_TNT_IGNITE)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSheepBred(event: EntityBreedEvent) {
        val player = event.breeder as? Player ?: return
        val child = event.entity as? Sheep ?: return
        val mother = event.mother as? Sheep ?: return
        val father = event.father as? Sheep ?: return
        val motherColour = mother.color
        val fatherColour = father.color
        val childColour = child.color
        if (
            motherColour != fatherColour &&
                childColour != motherColour &&
                childColour != fatherColour &&
                tracking.test(player, BingoTask.BREED_THIRD_COLOUR_SHEEP)
        )
            completion.accept(player, BingoTask.BREED_THIRD_COLOUR_SHEEP)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVaultStateChanged(event: VaultChangeStateEvent) {
        val player = event.player
        val vault = event.block.blockData
        if (
            player != null &&
                event.newState == Vault.State.UNLOCKING &&
                vault is Vault &&
                vault.isOminous &&
                tracking.test(player, BingoTask.UNLOCK_OMINOUS_VAULT)
        )
            completion.accept(player, BingoTask.UNLOCK_OMINOUS_VAULT)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTotemDropped(event: PlayerDropItemEvent) {
        val totem = event.itemDrop.itemStack
        if (totem.type != Material.TOTEM_OF_UNDYING || !tracking.test(event.player, BingoTask.FOX_USES_TOTEM)) return
        val playerId = event.player.uniqueId
        val droppedAt = System.currentTimeMillis()
        totem.editPersistentDataContainer { data ->
            data.set(foxTotemOwnerKey, PersistentDataType.STRING, playerId.toString())
            data.set(foxTotemDroppedAtKey, PersistentDataType.LONG, droppedAt)
            data.remove(foxTotemPickedUpByKey)
        }
        event.itemDrop.itemStack = totem
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTotemPickedUp(event: EntityPickupItemEvent) {
        val fox = event.entity as? Fox ?: return
        val totem = event.item.itemStack
        val marker = markerFrom(totem, foxTotemOwnerKey, foxTotemDroppedAtKey)
        if (totem.type != Material.TOTEM_OF_UNDYING || marker == null || !markerIsCurrent(marker)) return
        val player = Bukkit.getPlayer(marker.playerId) ?: return
        if (!tracking.test(player, BingoTask.FOX_USES_TOTEM)) return
        val foxId = fox.uniqueId
        val token = attemptToken(marker.playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable { confirmFoxTotemPickup(marker, foxId, token) })
    }

    private fun confirmFoxTotemPickup(expectedMarker: PersistentMarker, foxId: UUID, token: AttemptToken) {
        val player = Bukkit.getPlayer(expectedMarker.playerId) ?: return
        val fox = Bukkit.getEntity(foxId) as? Fox ?: return
        if (
            !isCurrent(expectedMarker.playerId, token) ||
                !markerIsCurrent(expectedMarker) ||
                !tracking.test(player, BingoTask.FOX_USES_TOTEM)
        )
            return
        val heldTotem = fox.equipment.itemInMainHand
        val equippedMarker = markerFrom(heldTotem, foxTotemOwnerKey, foxTotemDroppedAtKey)
        if (heldTotem.type != Material.TOTEM_OF_UNDYING || expectedMarker != equippedMarker) return
        heldTotem.editPersistentDataContainer {
            it.set(foxTotemPickedUpByKey, PersistentDataType.STRING, foxId.toString())
        }
        fox.equipment.setItemInMainHand(heldTotem)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFoxResurrected(event: EntityResurrectEvent) {
        val fox = event.entity as? Fox ?: return
        val hand = event.hand ?: return
        val totem = fox.equipment.getItem(hand)
        val marker = markerFrom(totem, foxTotemOwnerKey, foxTotemDroppedAtKey)
        val pickedUpBy = totem.persistentDataContainer.get(foxTotemPickedUpByKey, PersistentDataType.STRING)
        if (totem.type != Material.TOTEM_OF_UNDYING || marker == null || fox.uniqueId.toString() != pickedUpBy) return
        val player = Bukkit.getPlayer(marker.playerId)
        if (player != null && markerIsCurrent(marker) && tracking.test(player, BingoTask.FOX_USES_TOTEM))
            completion.accept(player, BingoTask.FOX_USES_TOTEM)
    }

    override fun resetPlayer(playerId: UUID) {
        super.resetPlayer(playerId)
        playerResetAtMillis[playerId] = System.currentTimeMillis()
    }

    override fun clear() {
        super.clear()
        playerResetAtMillis.clear()
        clearedAtMillis = System.currentTimeMillis()
    }

    private fun isSulfurCubeOwnedBy(cube: SulfurCube, playerId: UUID): Boolean {
        val data = cube.persistentDataContainer
        val owner = data.get(sulfurOwnerKey, PersistentDataType.STRING)
        val fedAt = data.get(sulfurFedAtKey, PersistentDataType.LONG)
        return owner != null &&
            owner == playerId.toString() &&
            fedAt != null &&
            markerIsCurrent(PersistentMarker(playerId, fedAt))
    }

    private fun clearSulfurAttribution(cube: SulfurCube) {
        cube.persistentDataContainer.remove(sulfurOwnerKey)
        cube.persistentDataContainer.remove(sulfurFedAtKey)
    }

    private fun markerFrom(item: ItemStack, ownerKey: NamespacedKey, timestampKey: NamespacedKey): PersistentMarker? {
        val owner = item.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) ?: return null
        val timestamp = item.persistentDataContainer.get(timestampKey, PersistentDataType.LONG) ?: return null
        return try {
            PersistentMarker(UUID.fromString(owner), timestamp)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun markerIsCurrent(marker: PersistentMarker): Boolean {
        val now = System.currentTimeMillis()
        val playerResetAt = playerResetAtMillis.getOrDefault(marker.playerId, 0L)
        return marker.timestamp > clearedAtMillis &&
            marker.timestamp > playerResetAt &&
            marker.timestamp <= now &&
            now - marker.timestamp <= MAX_PERSISTENT_ATTRIBUTION_MILLIS
    }

    private data class PersistentMarker(val playerId: UUID, val timestamp: Long)

    companion object {
        private const val MAX_PERSISTENT_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000

        @JvmStatic
        fun hasAbsorbedTnt(bodyItem: Material): Boolean {
            // Paper updates canExplode() during the later entity tick, after scheduled tasks run.
            // The BODY item is the immediate, reliable proof that the direct feed succeeded.
            return bodyItem == Material.TNT
        }
    }
}
