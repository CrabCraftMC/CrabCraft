package crabcraft.net.crabUtilities.bingo

import io.papermc.paper.event.player.PlayerToggleEntityAgeLockEvent
import java.util.EnumSet
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.BiPredicate
import org.bukkit.Bukkit
import org.bukkit.Instrument
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Frog
import org.bukkit.entity.Hoglin
import org.bukkit.entity.MagmaCube
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.NotePlayEvent
import org.bukkit.event.entity.CreeperPowerEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.weather.LightningStrikeEvent
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Event-driven detectors for the multi-stage challenge tasks in Bingo #3. */
class BingoCardThreeChallengeListener(
    private val plugin: JavaPlugin,
    private val tracking: BiPredicate<Player, BingoTask>,
    private val completion: BiConsumer<Player, BingoTask>,
) : BingoDetector {
    private val chargedCreeperOwnerKey = NamespacedKey(plugin, "bingo_card3_creeper_owner")
    private val chargedCreeperAtKey = NamespacedKey(plugin, "bingo_card3_creeper_charged_at")
    private val frogLeashOwnerKey = NamespacedKey(plugin, "bingo_card3_frog_leash_owner")
    private val frogLeashedAtKey = NamespacedKey(plugin, "bingo_card3_frog_leashed_at")
    private val channelledLightningOwners = HashMap<UUID, UUID>()
    private val noteBlockAttempts = HashMap<BlockKey, TimedPlayer>()
    private val copperTrumpetsByPlayer = HashMap<UUID, EnumSet<Instrument>>()
    private val playerGenerations = HashMap<UUID, Long>()
    private val playerResetAtMillis = HashMap<UUID, Long>()
    private var detectorGeneration = 0L
    private var clearedAtMillis = 0L

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        detectLeashedFrogFroglight(event)
        detectChargedCreeperHead(event)
    }

    private fun detectLeashedFrogFroglight(event: EntityDeathEvent) {
        val cube = event.entity as? MagmaCube ?: return
        val frog = damageSourceEntity(event) as? Frog ?: return
        if (cube.size != 1 || !frog.isLeashed || event.drops.none { isFroglight(it.type) }) return
        val marker = markerFrom(frog.persistentDataContainer, frogLeashOwnerKey, frogLeashedAtKey)
        if (marker == null || !markerIsCurrent(marker)) return
        val directHolder = frog.leashHolder
        if (directHolder is Player) {
            if (marker.playerId == directHolder.uniqueId
                && tracking.test(directHolder, BingoTask.LEASHED_FROG_FROGLIGHT)) {
                completion.accept(directHolder, BingoTask.LEASHED_FROG_FROGLIGHT)
            }
            return
        }
        val player = Bukkit.getPlayer(marker.playerId)
        if (player != null && tracking.test(player, BingoTask.LEASHED_FROG_FROGLIGHT)) {
            completion.accept(player, BingoTask.LEASHED_FROG_FROGLIGHT)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrogLeashed(event: PlayerLeashEntityEvent) {
        val frog = event.entity as? Frog ?: return
        clearFrogLeashAttribution(frog)
        val player = event.player
        if (!tracking.test(player, BingoTask.LEASHED_FROG_FROGLIGHT)) return
        val data = frog.persistentDataContainer
        data.set(frogLeashOwnerKey, PersistentDataType.STRING, player.uniqueId.toString())
        data.set(frogLeashedAtKey, PersistentDataType.LONG, System.currentTimeMillis())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFrogUnleashed(event: EntityUnleashEvent) {
        val frog = event.entity
        if (frog is Frog) clearFrogLeashAttribution(frog)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLightningStruck(event: LightningStrikeEvent) {
        if (event.cause != LightningStrikeEvent.Cause.TRIDENT) return
        val lightning = event.lightning
        val player = lightning.causingPlayer ?: (lightning.causingEntity as? Player)
        if (player == null || !tracking.test(player, BingoTask.CHARGED_CREEPER_MOB_HEAD)) return
        putBounded(channelledLightningOwners, lightning.uniqueId, player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCreeperPowered(event: CreeperPowerEvent) {
        val creeper = event.entity
        if (ChargedCreeperAttributionPolicy.shouldPreserve(creeper.isPowered, event.cause)) return
        clearChargedCreeperAttribution(creeper)
        val lightning = event.lightning
        if (event.cause != CreeperPowerEvent.PowerCause.LIGHTNING || lightning == null) return
        val ownerId = channelledLightningOwners[lightning.uniqueId] ?: return
        val data = creeper.persistentDataContainer
        data.set(chargedCreeperOwnerKey, PersistentDataType.STRING, ownerId.toString())
        data.set(chargedCreeperAtKey, PersistentDataType.LONG, System.currentTimeMillis())
    }

    private fun detectChargedCreeperHead(event: EntityDeathEvent) {
        val expectedHead = correspondingMobHead(event.entityType) ?: return
        if (event.drops.none { expectedHead == it.type }) return
        val creeper = damageSourceEntity(event) as? Creeper ?: return
        if (!creeper.isPowered) return
        val marker = markerFrom(creeper.persistentDataContainer, chargedCreeperOwnerKey, chargedCreeperAtKey)
        if (marker == null || !markerIsCurrent(marker)) return
        val player = Bukkit.getPlayer(marker.playerId)
        if (player != null && tracking.test(player, BingoTask.CHARGED_CREEPER_MOB_HEAD)) {
            completion.accept(player, BingoTask.CHARGED_CREEPER_MOB_HEAD)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHoglinAgeLockToggled(event: PlayerToggleEntityAgeLockEvent) {
        val player = event.player
        val hoglin = event.entity
        if (hoglin is Hoglin && !hoglin.isAdult && event.item.type == Material.GOLDEN_DANDELION
            && event.isAgeLocked && tracking.test(player, BingoTask.GOLDEN_DANDELION_HOGLIN)) {
            val playerId = player.uniqueId
            val hoglinId = hoglin.uniqueId
            val token = attemptToken(playerId)
            Bukkit.getScheduler().runTask(plugin, Runnable { confirmHoglinAgeLocked(playerId, hoglinId, token) })
        }
    }

    private fun confirmHoglinAgeLocked(playerId: UUID, hoglinId: UUID, token: AttemptToken) {
        val player = Bukkit.getPlayer(playerId)
        val hoglin = Bukkit.getEntity(hoglinId)
        if (player != null && hoglin is Hoglin && !hoglin.isAdult && hoglin.ageLock
            && isCurrent(playerId, token) && tracking.test(player, BingoTask.GOLDEN_DANDELION_HOGLIN)) {
            completion.accept(player, BingoTask.GOLDEN_DANDELION_HOGLIN)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNoteBlockInteracted(event: PlayerInteractEvent) {
        val action = event.action
        val block = event.clickedBlock
        if ((action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) || block == null
            || block.type != Material.NOTE_BLOCK || event.useInteractedBlock() == Event.Result.DENY
            || !tracking.test(event.player, BingoTask.FOUR_COPPER_TRUMPET_SOUNDS)) return
        val key = BlockKey.from(block)
        val attempt = TimedPlayer(event.player.uniqueId, Bukkit.getCurrentTick())
        putBounded(noteBlockAttempts, key, attempt)
        Bukkit.getScheduler().runTaskLater(
            plugin, Runnable { noteBlockAttempts.remove(key, attempt) }, NOTE_PLAY_CORRELATION_TICKS + 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNotePlayed(event: NotePlayEvent) {
        val instrument = event.instrument
        if (!COPPER_TRUMPETS.contains(instrument)) return
        val attempt = noteBlockAttempts.remove(BlockKey.from(event.block))
        if (attempt == null || !isFresh(attempt.tick, Bukkit.getCurrentTick(), NOTE_PLAY_CORRELATION_TICKS)) return
        val player = Bukkit.getPlayer(attempt.playerId)
        if (player == null || !tracking.test(player, BingoTask.FOUR_COPPER_TRUMPET_SOUNDS)) return
        val instruments = copperTrumpetsByPlayer.computeIfAbsent(attempt.playerId) { EnumSet.noneOf(Instrument::class.java) }
        if (instruments.add(instrument) && instruments.containsAll(COPPER_TRUMPETS)) {
            completion.accept(player, BingoTask.FOUR_COPPER_TRUMPET_SOUNDS)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityRemoved(event: EntityRemoveEvent) {
        channelledLightningOwners.remove(event.entity.uniqueId)
    }

    override fun resetPlayer(playerId: UUID) {
        playerGenerations.merge(playerId, 1L, Long::plus)
        channelledLightningOwners.values.removeIf(playerId::equals)
        noteBlockAttempts.values.removeIf { it.playerId == playerId }
        copperTrumpetsByPlayer.remove(playerId)
        playerResetAtMillis[playerId] = System.currentTimeMillis()
    }

    override fun clear() {
        detectorGeneration++
        channelledLightningOwners.clear()
        noteBlockAttempts.clear()
        copperTrumpetsByPlayer.clear()
        playerGenerations.clear()
        playerResetAtMillis.clear()
        clearedAtMillis = System.currentTimeMillis()
    }

    private fun attemptToken(playerId: UUID): AttemptToken =
        AttemptToken(detectorGeneration, playerGenerations.getOrDefault(playerId, 0L))

    private fun isCurrent(playerId: UUID, token: AttemptToken): Boolean =
        token.detectorGeneration == detectorGeneration
            && token.playerGeneration == playerGenerations.getOrDefault(playerId, 0L)

    private fun markerFrom(data: PersistentDataContainer, ownerKey: NamespacedKey, timestampKey: NamespacedKey): PersistentMarker? {
        val owner = data.get(ownerKey, PersistentDataType.STRING)
        val timestamp = data.get(timestampKey, PersistentDataType.LONG)
        if (owner == null || timestamp == null) return null
        return try {
            PersistentMarker(UUID.fromString(owner), timestamp)
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    private fun markerIsCurrent(marker: PersistentMarker): Boolean {
        val now = System.currentTimeMillis()
        val playerResetAt = playerResetAtMillis.getOrDefault(marker.playerId, 0L)
        return marker.timestamp > clearedAtMillis && marker.timestamp > playerResetAt
            && marker.timestamp <= now && now - marker.timestamp <= MAX_PERSISTENT_ATTRIBUTION_MILLIS
    }

    private fun clearChargedCreeperAttribution(creeper: Creeper) {
        val data = creeper.persistentDataContainer
        data.remove(chargedCreeperOwnerKey)
        data.remove(chargedCreeperAtKey)
    }

    private fun clearFrogLeashAttribution(frog: Frog) {
        val data = frog.persistentDataContainer
        data.remove(frogLeashOwnerKey)
        data.remove(frogLeashedAtKey)
    }

    private data class BlockKey(val worldId: UUID, val x: Int, val y: Int, val z: Int) {
        companion object {
            fun from(block: Block): BlockKey = BlockKey(block.world.uid, block.x, block.y, block.z)
        }
    }

    private data class TimedPlayer(val playerId: UUID, val tick: Int)
    private data class AttemptToken(val detectorGeneration: Long, val playerGeneration: Long)
    private data class PersistentMarker(val playerId: UUID, val timestamp: Long)

    class ChargedCreeperAttributionPolicy private constructor() {
        companion object {
            @JvmStatic
            fun shouldPreserve(alreadyPowered: Boolean, cause: CreeperPowerEvent.PowerCause?): Boolean =
                alreadyPowered && cause == CreeperPowerEvent.PowerCause.LIGHTNING
        }
    }

    companion object {
        private const val MAX_TRANSIENT_ENTRIES = 4_096
        private const val NOTE_PLAY_CORRELATION_TICKS = 1
        private const val MAX_PERSISTENT_ATTRIBUTION_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private val COPPER_TRUMPETS: Set<Instrument> = EnumSet.of(
            Instrument.TRUMPET, Instrument.TRUMPET_EXPOSED, Instrument.TRUMPET_WEATHERED, Instrument.TRUMPET_OXIDIZED)

        private fun damageSourceEntity(event: EntityDeathEvent): Entity? =
            event.damageSource.causingEntity ?: event.damageSource.directEntity

        @JvmStatic
        fun isFroglight(material: Material?): Boolean = material == Material.OCHRE_FROGLIGHT
            || material == Material.VERDANT_FROGLIGHT || material == Material.PEARLESCENT_FROGLIGHT

        @JvmStatic
        fun correspondingMobHead(entityType: EntityType): Material? = when (entityType) {
            EntityType.CREEPER -> Material.CREEPER_HEAD
            EntityType.PIGLIN -> Material.PIGLIN_HEAD
            EntityType.SKELETON -> Material.SKELETON_SKULL
            EntityType.WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL
            EntityType.ZOMBIE -> Material.ZOMBIE_HEAD
            else -> null
        }

        private fun isFresh(earlier: Int, current: Int, maximumAge: Int): Boolean {
            val age = current - earlier
            return age >= 0 && age <= maximumAge
        }

        private fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
            if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) {
                map.remove(map.keys.iterator().next())
            }
            map[key] = value
        }
    }
}
