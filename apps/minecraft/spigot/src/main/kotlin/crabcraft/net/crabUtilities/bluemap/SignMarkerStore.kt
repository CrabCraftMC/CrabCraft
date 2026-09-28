package crabcraft.net.crabUtilities.bluemap

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin

/**
 * YAML-backed source of truth for sign markers, replayed after BlueMap reloads. Mutations update memory immediately and
 * coalesce into asynchronous saves; shutdown flushes synchronously.
 */
class SignMarkerStore(private val plugin: JavaPlugin) {
    private val file = File(plugin.dataFolder, "bluemap-sign-markers.yml")
    private val markers = ConcurrentHashMap<UUID, MutableMap<String, String>>()
    private val savePending = AtomicBoolean()

    fun load() {
        markers.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("markers") ?: return
        for (worldKey in root.getKeys(false)) {
            val worldId =
                try {
                    UUID.fromString(worldKey)
                } catch (exception: IllegalArgumentException) {
                    plugin.logger.warning("Skipping sign markers for invalid world id: $worldKey")
                    continue
                }
            val section = root.getConfigurationSection(worldKey) ?: continue
            val worldMarkers = ConcurrentHashMap<String, String>()
            for (posKey in section.getKeys(false)) {
                val label = section.getString(posKey)
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

    fun snapshotWorld(worldId: UUID): Map<String, String> =
        markers[worldId]?.let { java.util.Map.copyOf(it) } ?: emptyMap()

    fun flush() {
        save()
    }

    private fun queueSave() {
        if (!savePending.compareAndSet(false, true)) return
        try {
            Bukkit.getScheduler()
                .runTaskAsynchronously(
                    plugin,
                    Runnable {
                        savePending.set(false)
                        save()
                    },
                )
        } catch (exception: IllegalPluginAccessException) {
            // Disabling prevents scheduling, so persist inline.
            savePending.set(false)
            save()
        }
    }

    @Synchronized
    private fun save() {
        val yaml = YamlConfiguration()
        markers.forEach { (worldId, worldMarkers) ->
            worldMarkers.forEach { (posKey, label) -> yaml.set("markers.$worldId.$posKey", label) }
        }
        try {
            yaml.save(file)
        } catch (exception: IOException) {
            plugin.logger.log(Level.WARNING, "Could not save ${file.name}", exception)
        }
    }

    companion object {
        @JvmStatic fun key(x: Int, y: Int, z: Int) = "${x}_${y}_${z}"

        @JvmStatic
        fun parseKey(key: String): IntArray? {
            // Java split discards trailing empty fields; retain that behaviour for stored keys.
            val parts = key.split('_').dropLastWhile { it.isEmpty() }
            if (parts.size != 3) return null
            return try {
                intArrayOf(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            } catch (exception: NumberFormatException) {
                null
            }
        }
    }
}
