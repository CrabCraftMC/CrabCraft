package crabcraft.net.crabUtilities

import org.bukkit.entity.Player
import java.util.UUID

open class ResourcePackManager(private val plugin: CrabUtilities) {
    private var url: String? = null
    private var hashHex: String? = null // Optional, informational only for now
    private val autoEnabled = HashSet<UUID>()

    init { reload() }

    open fun reload() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
        val rp = plugin.config.getConfigurationSection("resource-pack")
            ?: plugin.config.createSection("resource-pack")
        url = rp.getString("url", "")
        hashHex = rp.getString("hash", "")
        autoEnabled.clear()
        for (value in rp.getStringList("auto-enabled")) {
            try {
                autoEnabled.add(UUID.fromString(value))
            } catch (ignored: IllegalArgumentException) {
            }
        }
    }

    open fun isConfigured(): Boolean = !url.isNullOrBlank()
    open fun isEnabled(uuid: UUID): Boolean = uuid in autoEnabled
    open fun setEnabled(uuid: UUID, enabled: Boolean) {
        if (enabled) autoEnabled.add(uuid) else autoEnabled.remove(uuid)
        persist()
    }
    open fun enableFor(player: Player): Boolean {
        if (!isConfigured()) return false
        sendPack(player)
        setEnabled(player.uniqueId, true)
        return true
    }
    open fun disableFor(player: Player) { setEnabled(player.uniqueId, false) }
    open fun sendIfOptedIn(player: Player) {
        if (isConfigured() && isEnabled(player.uniqueId)) sendPack(player)
    }
    private fun persist() {
        val rp = plugin.config.getConfigurationSection("resource-pack")
            ?: plugin.config.createSection("resource-pack")
        rp.set("auto-enabled", autoEnabled.map { it.toString() })
        plugin.saveConfig()
    }
    private fun sendPack(player: Player) {
        // Use the simplest API to maximise compatibility across Paper builds.
        // The hash is optional here; if your server needs hash verification, we can extend this.
        player.setResourcePack(url!!)
    }
}
