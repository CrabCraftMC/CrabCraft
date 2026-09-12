package crabcraft.net.crabUtilities

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

class CrabUtilities : JavaPlugin() {
    private var essentials: Plugin? = null // Optional: present when EssentialsX is installed
    private lateinit var resourcePackManager: ResourcePackManager

    override fun onEnable() {
        // Detect EssentialsX (optional) and register event listeners
        essentials = Bukkit.getPluginManager().getPlugin("Essentials")
        // Config and managers
        saveDefaultConfig()
        resourcePackManager = ResourcePackManager(this)
        // Event listeners
        Bukkit.getPluginManager().registerEvents(NicknameMessageListener(this), this)
        Bukkit.getPluginManager().registerEvents(PackJoinListener(this), this)
        // Commands
        val packCommand = PackCommand(this)
        getCommand("pack")!!.setExecutor(packCommand)
        getCommand("pack")!!.tabCompleter = packCommand
        logger.info("CrabUtilities enabled. EssentialsX present: ${essentials != null}")
    }

    fun getEssentials(): Plugin? = essentials
    fun getResourcePackManager(): ResourcePackManager = resourcePackManager
    override fun onDisable() {
        // Plugin shutdown logic
    }
}
