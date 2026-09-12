package crabcraft.net.crabUtilities.bluemap

import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/** YAML marker storage with coalesced async saves and a synchronous shutdown flush. */
class SignMarkerStore(private val plugin: JavaPlugin) {
    private val file = File(plugin.getDataFolder(), "bluemap-sign-markers.yml")
    private val markers = ConcurrentHashMap<UUID, MutableMap<String, String>>()
    private val savePending = AtomicBoolean()
    fun load() {
        markers.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("markers") ?: return
        for (worldKey in root.getKeys(false)) {
            val worldId = try { UUID.fromString(worldKey) } catch (e: IllegalArgumentException) {
                plugin.getLogger().warning("Skipping sign markers for invalid world id: $worldKey")
                continue
            }
            val worldSection = root.getConfigurationSection(worldKey) ?: continue
            val worldMarkers = ConcurrentHashMap<String, String>()
            for (posKey in worldSection.getKeys(false)) {
                val label = worldSection.getString(posKey)
                if (label != null && parseKey(posKey) != null) worldMarkers[posKey] = label
            }
            if (worldMarkers.isNotEmpty()) markers[worldId] = worldMarkers
        }
    }
    fun put(worldId: UUID, x: Int, y: Int, z: Int, label: String) {
        markers.computeIfAbsent(worldId) { ConcurrentHashMap() }[key(x, y, z)] = label
        queueSave()
    }
    fun remove(worldId: UUID, x: Int, y: Int, z: Int): Boolean {
        val worldMarkers = markers[worldId] ?: return false
        if (worldMarkers.remove(key(x, y, z)) == null) return false
        queueSave()
        return true
    }
    fun snapshot(): Map<UUID, Map<String, String>> {
        val copy = HashMap<UUID, Map<String, String>>()
        markers.forEach { (id, worldMarkers) -> copy[id] = HashMap(worldMarkers) }
        return Collections.unmodifiableMap(copy)
    }
    fun snapshotWorld(worldId: UUID): Map<String, String> {
        val worldMarkers = markers[worldId] ?: return emptyMap()
        return java.util.Map.copyOf(worldMarkers)
    }
    fun flush() { save() }
    private fun queueSave() {
        if (!savePending.compareAndSet(false, true)) return
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { savePending.set(false); save() })
        } catch (e: IllegalPluginAccessException) { savePending.set(false); save() }
    }
    @Synchronized
    private fun save() {
        val yaml = YamlConfiguration()
        markers.forEach { (worldId, worldMarkers) -> worldMarkers.forEach { (posKey, label) -> yaml.set("markers.$worldId.$posKey", label) } }
        try { yaml.save(file) } catch (e: IOException) { plugin.getLogger().log(Level.WARNING, "Could not save " + file.name, e) }
    }
    companion object {
        @JvmStatic fun key(x: Int, y: Int, z: Int): String = "${x}_${y}_$z"
        @JvmStatic
        fun parseKey(key: String): IntArray? {
            val parts = java.util.regex.Pattern.compile("_").split(key)
            if (parts.size != 3) return null
            return try { intArrayOf(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) } catch (e: NumberFormatException) { null }
        }
    }
}
