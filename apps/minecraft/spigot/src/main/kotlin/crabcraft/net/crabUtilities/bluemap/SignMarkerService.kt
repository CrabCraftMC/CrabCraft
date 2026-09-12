package crabcraft.net.crabUtilities.bluemap

import com.flowpowered.math.vector.Vector2i
import com.flowpowered.math.vector.Vector3d
import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.BlueMapMap
import de.bluecolored.bluemap.api.BlueMapWorld
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.markers.POIMarker
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.HandlerList
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/** Stores player sign markers and replays them whenever the BlueMap API enables. */
class SignMarkerService(private val plugin: JavaPlugin) {
    private val store = SignMarkerStore(plugin)
    private val keyword: String
    private val markerSetLabel: String?
    private val toggleable: Boolean
    private val defaultHidden: Boolean
    private val icon: String
    private val iconAnchor: Vector2i
    private val listener: SignMarkerListener
    private val markerSets = ConcurrentHashMap<UUID, MarkerSet>()
    @Volatile private var stopped = false
    // Retain the exact callback instances for shutdown.
    private val onApiEnable = Consumer<BlueMapAPI>(::populate)
    private val onApiDisable = Consumer<BlueMapAPI> { markerSets.clear() }
    init {
        val config = plugin.getConfig()
        val configuredKeyword = config.getString("bluemap.sign-markers.keyword", "[map]")
        keyword = if (configuredKeyword.isNullOrBlank()) "[map]" else configuredKeyword.trim()
        markerSetLabel = config.getString("bluemap.sign-markers.marker-set-label", "Player Markers")
        toggleable = config.getBoolean("bluemap.sign-markers.toggleable", true)
        defaultHidden = config.getBoolean("bluemap.sign-markers.default-hidden", false)
        icon = config.getString("bluemap.sign-markers.icon", "")?.trim() ?: ""
        iconAnchor = Vector2i(config.getInt("bluemap.sign-markers.icon-anchor-x", 0), config.getInt("bluemap.sign-markers.icon-anchor-y", 0))
        listener = SignMarkerListener(this, keyword)
    }
    fun getKeyword(): String = keyword
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
        BlueMapAPI.getInstance().ifPresent { api -> api.getMaps().forEach { it.getMarkerSets().remove(MARKER_SET_ID) } }
        markerSets.clear()
    }
    fun addMarker(block: Block, label: String): Boolean {
        val world = block.getWorld()
        val x = block.getX(); val y = block.getY(); val z = block.getZ()
        store.put(world.getUID(), x, y, z, label)
        val api = BlueMapAPI.getInstance().orElse(null) ?: return false
        val set = markerSetFor(api, world) ?: return false
        set.getMarkers()[markerId(x, y, z)] = createMarker(label, x, y, z)
        return true
    }
    fun removeMarker(block: Block): Boolean {
        val worldId = block.getWorld().getUID()
        val x = block.getX(); val y = block.getY(); val z = block.getZ()
        if (!store.remove(worldId, x, y, z)) return false
        markerSets[worldId]?.getMarkers()?.remove(markerId(x, y, z))
        return true
    }
    fun worldLoaded(world: World) {
        BlueMapAPI.getInstance().ifPresent { api ->
            val worldMarkers = store.snapshotWorld(world.getUID())
            if (worldMarkers.isNotEmpty()) populateWorld(api, world, worldMarkers)
        }
    }
    private fun populate(api: BlueMapAPI) {
        if (Bukkit.isPrimaryThread()) { populateAll(api); return }
        try { Bukkit.getScheduler().runTask(plugin, Runnable { populateAll(api) }) }
        catch (e: IllegalPluginAccessException) { /* Plugin is disabling. */ }
    }
    private fun populateAll(api: BlueMapAPI) {
        // Stale callbacks must not restore retired service or API marker sets.
        if (stopped || BlueMapAPI.getInstance().orElse(null) !== api) return
        markerSets.clear()
        store.snapshot().forEach { (worldId, worldMarkers) ->
            val world = Bukkit.getWorld(worldId)
            if (world != null) populateWorld(api, world, worldMarkers)
        }
    }
    private fun populateWorld(api: BlueMapAPI, world: World, worldMarkers: Map<String, String>) {
        val set = markerSetFor(api, world) ?: return
        worldMarkers.forEach { (posKey, label) ->
            val pos = SignMarkerStore.parseKey(posKey)
            if (pos != null) set.getMarkers()["sign_$posKey"] = createMarker(label, pos[0], pos[1], pos[2])
        }
    }
    private fun markerSetFor(api: BlueMapAPI, world: World): MarkerSet? {
        val blueMapWorld = api.getWorld(world).orElse(null) ?: return null
        return markerSets.computeIfAbsent(world.getUID()) {
            val set = MarkerSet.builder().label(markerSetLabel).toggleable(toggleable).defaultHidden(defaultHidden).build()
            for (map in blueMapWorld.getMaps()) map.getMarkerSets()[MARKER_SET_ID] = set
            set
        }
    }
    private fun createMarker(label: String, x: Int, y: Int, z: Int): POIMarker {
        val marker = POIMarker.builder().label(label).position(Vector3d(x + 0.5, y + 0.5, z + 0.5)).build()
        // BlueMap renders detail as raw HTML; player-written sign text must be escaped.
        marker.setDetail(escapeHtml(label))
        if (icon.isNotEmpty()) marker.setIcon(icon, iconAnchor)
        return marker
    }
    companion object {
        private const val MARKER_SET_ID = "crabutilities-sign-markers"
        private fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        private fun markerId(x: Int, y: Int, z: Int): String = "sign_" + SignMarkerStore.key(x, y, z)
    }
}
