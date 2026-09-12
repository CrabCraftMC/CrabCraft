package crabcraft.net.crabUtilities.media

import crabcraft.net.crabUtilities.CrabUtilities
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import crabcraft.net.crabUtilities.media.audio.AudioEngine
import crabcraft.net.crabUtilities.media.command.MediaCommand
import crabcraft.net.crabUtilities.media.command.DiscCommand
import crabcraft.net.crabUtilities.media.command.HornCommand
import crabcraft.net.crabUtilities.media.event.HornPlaybackListener
import crabcraft.net.crabUtilities.media.event.JukeboxPlaybackListener
import crabcraft.net.crabUtilities.media.file.LegacyMediaMigration
import crabcraft.net.crabUtilities.media.file.MediaConfig
import crabcraft.net.crabUtilities.media.language.MediaMessages
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import java.io.File

/** Disc, horn, and shared audio support owned by Crab Utilities. */
class MediaFeature private constructor(private val javaPlugin: CrabUtilities) {
  private val dataFolder = File(javaPlugin.dataFolder, "media")
  private val config = MediaConfig(javaPlugin)
  private val language = MediaMessages()
  private var packetEventsRegistration: AutoCloseable? = null
  private var audioStarted = false
  private var enabled = false

  fun getJavaPlugin(): CrabUtilities = javaPlugin
  fun getDataFolder(): File = dataFolder
  fun getMediaConfig(): MediaConfig = config
  fun getMessages(): MediaMessages = language
  fun getServer(): org.bukkit.Server = javaPlugin.server
  fun isEnabled(): Boolean = enabled

  fun refreshConfiguration(): Boolean {
    val mediaPolicyReloaded = !audioStarted || AudioEngine.getInstance().reloadMediaPolicy()
    return mediaPolicyReloaded
  }

  private fun integrationAvailable(name: String): Boolean =
    javaPlugin.server.pluginManager.isPluginEnabled(name)

  @Suppress("UnstableApiUsage")
  private fun registerCommands() {
    javaPlugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
      val commands = event.registrar()
      commands.register("disc", "Create, edit, and clear playable music discs.", emptyList(), DiscCommand())
      commands.register("horn", "Create, edit, and clear playable goat horns.", emptyList(), HornCommand())
      commands.register("cd", "Crab Utilities media commands.", emptyList(), MediaCommand())
    }
  }

  private fun registerEvents() {
    val manager = javaPlugin.server.pluginManager
    manager.registerEvents(JukeboxPlaybackListener(), javaPlugin)
    manager.registerEvents(HornPlaybackListener(), javaPlugin)
  }

  companion object {
    private var instance: MediaFeature? = null

    /** Creates and enables the module during the main plugin's enable phase. */
    @JvmStatic @Synchronized fun enable(javaPlugin: CrabUtilities) {
      if (instance == null) instance = MediaFeature(javaPlugin)
      val feature = get()
      if (feature.enabled) return
      if (!feature.integrationAvailable("voicechat")) {
        warn("Media playback disabled: Simple Voice Chat must be installed")
        return
      }
      if (!feature.dataFolder.exists() && !feature.dataFolder.mkdirs()) {
        warn("Failed to create media data directory {}", feature.dataFolder.absolutePath)
      }
      LegacyMediaMigration.run(feature.javaPlugin, feature.dataFolder)
      val provisioner = Thread(AudioEngine.getInstance()::provision, "CrabUtilities-MediaInit")
      provisioner.isDaemon = true
      provisioner.start()
      feature.audioStarted = true
      if (!feature.integrationAvailable("packetevents")) {
        warn("Disc and horn interactions disabled because PacketEvents is not installed; shared audio remains available")
        return
      }
      feature.registerEvents()
      feature.registerCommands()
      feature.packetEventsRegistration = JukeboxPacketFilter.install()
      feature.enabled = true
      info("Crab Utilities media feature enabled")
    }

    @JvmStatic @Synchronized fun disable() {
      val feature = instance ?: return
      if (feature.packetEventsRegistration != null) {
        try {
          feature.packetEventsRegistration!!.close()
        } catch (e: Exception) {
          warn("PacketEvents listener shutdown failed: {}", e.message)
        }
        feature.packetEventsRegistration = null
      }
      if (feature.audioStarted) {
        AudioEngine.getInstance().shutdown()
        feature.audioStarted = false
      }
      feature.enabled = false
      info("Crab Utilities media feature disabled")
    }

    @JvmStatic fun get(): MediaFeature =
      instance ?: throw IllegalStateException("Media feature has not been loaded")

    @JvmStatic fun sendMessage(sender: CommandSender, component: Component) {
      sender.sendMessage(component)
    }

    @JvmStatic fun debug(message: String, vararg format: Any?) {
      if (get().getMediaConfig().isDebug()) get().javaPlugin.getSLF4JLogger().info(message, *format)
    }

    @JvmStatic fun info(message: String, vararg format: Any?) {
      get().javaPlugin.getSLF4JLogger().info(message, *format)
    }

    @JvmStatic fun warn(message: String, vararg format: Any?) {
      get().javaPlugin.getSLF4JLogger().warn(message, *format)
    }

    @JvmStatic fun error(message: String, e: Throwable?, vararg format: Any?) {
      val arguments = arrayOfNulls<Any>(format.size + 1)
      format.copyInto(arguments)
      arguments[format.size] = e
      get().javaPlugin.getSLF4JLogger().error(message, *arguments)
    }

    @JvmStatic fun error(message: String, vararg format: Any?) {
      get().javaPlugin.getSLF4JLogger().error(message, *format)
    }
  }
}
