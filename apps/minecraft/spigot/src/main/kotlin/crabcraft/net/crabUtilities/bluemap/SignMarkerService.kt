package crabcraft.net.crabUtilities.bluemap

import com.flowpowered.math.vector.Vector2i
import com.flowpowered.math.vector.Vector3d
import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.markers.POIMarker
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.event.HandlerList
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin

/**
 * Persists player-made sign markers and replays them when BlueMap enables. Loaded only after the optional BlueMap
 * dependency has been confirmed. API callbacks hop to the main thread to serialise world access and mutations.
 */
class SignMarkerService(private val plugin: JavaPlugin) {
    private val store = SignMarkerStore(plugin)
    private val listener: SignMarkerListener
    private val keyword: String
    private val markerSetLabel: String?
    private val toggleable: Boolean
    private val defaultHidden: Boolean
    private val icon: String
    private val iconAnchor: Vector2i
    private val markerSets = ConcurrentHashMap<UUID, MarkerSet>()
    @Volatile private var stopped = false
    private val onApiEnable = Consumer<BlueMapAPI>(::populate)
    private val onApiDisable = Consumer<BlueMapAPI> { markerSets.clear() }

    init {
        val config = plugin.config
        val configuredKeyword = config.getString("bluemap.sign-markers.keyword", "[map]")
        keyword =
            if (configuredKeyword == null || configuredKeyword.codePoints().allMatch(Character::isWhitespace)) "[map]"
            else configuredKeyword.trim { it <= ' ' }
        markerSetLabel = config.getString("bluemap.sign-markers.marker-set-label", "Player Markers")
        toggleable = config.getBoolean("bluemap.sign-markers.toggleable", true)
        defaultHidden = config.getBoolean("bluemap.sign-markers.default-hidden", false)
        icon = config.getString("bluemap.sign-markers.icon", "")?.trim { it <= ' ' } ?: ""
        iconAnchor =
            Vector2i(
                config.getInt("bluemap.sign-markers.icon-anchor-x", 0),
                config.getInt("bluemap.sign-markers.icon-anchor-y", 0),
            )
        listener = SignMarkerListener(this, keyword)
    }

    fun getKeyword() = keyword

    fun start() {
        store.load()
        Bukkit.getPluginManager().registerEvents(listener, plugin)
        BlueMapAPI.onEnable(onApiEnable)
        BlueMapAPI.onDisable(onApiDisable)
    }

    fun shutdown() {
        stopped = true
        HandlerList.unregisterAll(listener)
        BlueMapAPI.unregisterListener(onApiEnable)
        BlueMapAPI.unregisterListener(onApiDisable)
        store.flush()
        BlueMapAPI.getInstance().ifPresent { api -> api.maps.forEach { it.markerSets.remove(MARKER_SET_ID) } }
        markerSets.clear()
    }

    /** False means persisted but not currently visible because the API or map is unavailable. */
    fun addMarker(block: Block, label: String): Boolean {
        val world = block.world
        val x = block.x
        val y = block.y
        val z = block.z
        store.put(world.uid, x, y, z, label)
        val api = BlueMapAPI.getInstance().orElse(null) ?: return false
        val set = markerSetFor(api, world) ?: return false
        set.markers[markerId(x, y, z)] = createMarker(label, x, y, z)
        return true
    }

    fun removeMarker(block: Block): Boolean {
        val worldId = block.world.uid
        if (!store.remove(worldId, block.x, block.y, block.z)) return false
        markerSets[worldId]?.markers?.remove(markerId(block.x, block.y, block.z))
        return true
    }

    fun worldLoaded(world: World) {
        BlueMapAPI.getInstance().ifPresent { api ->
            val worldMarkers = store.snapshotWorld(world.uid)
            if (worldMarkers.isNotEmpty()) populateWorld(api, world, worldMarkers)
        }
    }

    private fun populate(api: BlueMapAPI) {
        if (Bukkit.isPrimaryThread()) {
            populateAll(api)
            return
        }
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable { populateAll(api) })
        } catch (exception: IllegalPluginAccessException) {
            /* Markers die with the API during shutdown. */
        }
    }

    private fun populateAll(api: BlueMapAPI) {
        // Retired services and replaced API instances must not resurrect old marker sets.
        if (stopped || BlueMapAPI.getInstance().orElse(null) !== api) return
        markerSets.clear()
        store.snapshot().forEach { (worldId, markers) ->
            Bukkit.getWorld(worldId)?.let { populateWorld(api, it, markers) }
        }
    }

    private fun populateWorld(api: BlueMapAPI, world: World, worldMarkers: Map<String, String>) {
        val set = markerSetFor(api, world) ?: return
        worldMarkers.forEach { (posKey, label) ->
            SignMarkerStore.parseKey(posKey)?.let { pos ->
                set.markers["sign_$posKey"] = createMarker(label, pos[0], pos[1], pos[2])
            }
        }
    }

    private fun markerSetFor(api: BlueMapAPI, world: World): MarkerSet? {
        val blueMapWorld = api.getWorld(world).orElse(null) ?: return null
        return markerSets.computeIfAbsent(world.uid) {
            val set =
                MarkerSet.builder().label(markerSetLabel).toggleable(toggleable).defaultHidden(defaultHidden).build()
            blueMapWorld.maps.forEach { it.markerSets[MARKER_SET_ID] = set }
            set
        }
    }

    private fun createMarker(label: String, x: Int, y: Int, z: Int): POIMarker {
        val marker = POIMarker.builder().label(label).position(Vector3d(x + 0.5, y + 0.5, z + 0.5)).build()
        // BlueMap renders details as HTML; escape player-written sign labels.
        marker.detail = escapeHtml(label)
        if (icon.isNotEmpty()) marker.setIcon(icon, iconAnchor)
        return marker
    }

    companion object {
        private const val MARKER_SET_ID = "crabutilities-sign-markers"

        @JvmStatic
        private fun escapeHtml(text: String) =
            text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")

        private fun markerId(x: Int, y: Int, z: Int) = "sign_${SignMarkerStore.key(x, y, z)}"
    }
}
