package crabcraft.net.crabUtilities.jade

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.jade.protocol.JadeMessenger
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import crabcraft.net.crabUtilities.jade.protocol.payload.*
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandExecutor
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import org.slf4j.Logger
import java.nio.ByteBuffer

/** Static holder and initialiser for the in-tree Jade server-side companion. */
class JadeBootstrap private constructor() {
    companion object {
        private const val CLIENT_PROTOCOL_CHANNEL = "crabcraft:client_protocol"
        lateinit var INSTANCE: JavaPlugin
        lateinit var LOGGER: Logger
        private var integration: JadeIntegration? = null
        private var clientProtocolListener: PluginMessageListener? = null
        private var validatedPlayers: Set<ServerPlayer> = emptySet()
        private var enabled = false
        private val DISABLED_COMMAND = CommandExecutor { sender, _, _, _ ->
            sender.sendMessage(CrabMessages.error("Jade integration is disabled."))
            true
        }

        @JvmStatic
        @Synchronized
        fun enable(plugin: JavaPlugin): Boolean {
            if (!plugin.config.getBoolean("mod-protocols.jade.enabled", true)) {
                disable(plugin)
                plugin.logger.info("Jade integration disabled in config.")
                return false
            }
            if (enabled) return true
            INSTANCE = plugin
            LOGGER = plugin.getSLF4JLogger()
            try {
                val inventoryDataEnabled = plugin.config.getBoolean("mod-protocols.jade.inventory-data-enabled", false)
                JadeProtocol.init(inventoryDataEnabled)
                if (!inventoryDataEnabled) {
                    plugin.logger.info("Jade inventory data disabled; protection-aware access checks are unavailable.")
                }
                JadeMessenger.registerIncoming(plugin, ClientHandshakePayload::class.java, JadeProtocol::clientHandshake)
                JadeMessenger.registerIncoming(plugin, RequestBlockPayload::class.java, JadeProtocol::requestBlockData)
                JadeMessenger.registerIncoming(plugin, RequestEntityPayload::class.java, JadeProtocol::requestEntityData)
                JadeMessenger.registerOutgoing(plugin, ServerHandshakePayload::class.java)
                JadeMessenger.registerOutgoing(plugin, ReceiveDataPayload::class.java)
                val listener = PluginMessageListener { _, player, data ->
                    val protocol = decodeClientProtocol(data)
                    if (protocol > 0) JadeProtocol.setClientProtocol(player.uniqueId, protocol)
                }
                clientProtocolListener = listener
                plugin.server.messenger.registerIncomingPluginChannel(plugin, CLIENT_PROTOCOL_CHANNEL, listener)
                val jadeIntegration = JadeIntegration()
                integration = jadeIntegration
                plugin.server.pluginManager.registerEvents(jadeIntegration, plugin)
                plugin.getCommand("jadehandshake")?.setExecutor(jadeIntegration)
                enabled = true
                for (player in plugin.server.onlinePlayers) {
                    val serverPlayer = (player as CraftPlayer).handle
                    if (!validatedPlayers.contains(serverPlayer)) continue
                    try {
                        JadeProtocol.resendHandshake(serverPlayer)
                    } catch (exception: RuntimeException) {
                        plugin.logger.warning("Could not resend the Jade handshake to " + player.name + ": " + exception.message)
                    }
                }
                validatedPlayers = emptySet()
                plugin.logger.info("Jade integration enabled (protocol v" + JadeProtocol.PROTOCOL_VERSION + ")")
                return true
            } catch (exception: LinkageError) {
                return failEnable(plugin, exception)
            } catch (exception: RuntimeException) {
                return failEnable(plugin, exception)
            }
        }

        private fun failEnable(plugin: JavaPlugin, exception: Throwable): Boolean {
            disable(plugin)
            plugin.logger.warning("Jade integration could not initialise: " + exception.message)
            return false
        }

        @JvmStatic
        @Synchronized
        fun disable(plugin: JavaPlugin) {
            if (enabled) validatedPlayers = JadeProtocol.snapshotEnabledPlayers()
            enabled = false
            JadeProtocol.shutdown()
            integration?.let { HandlerList.unregisterAll(it) }
            integration = null
            JadeMessenger.unregisterIncoming(plugin, ClientHandshakePayload::class.java)
            JadeMessenger.unregisterIncoming(plugin, RequestBlockPayload::class.java)
            JadeMessenger.unregisterIncoming(plugin, RequestEntityPayload::class.java)
            JadeMessenger.unregisterOutgoing(plugin, ServerHandshakePayload::class.java)
            JadeMessenger.unregisterOutgoing(plugin, ReceiveDataPayload::class.java)
            plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CLIENT_PROTOCOL_CHANNEL)
            clientProtocolListener = null
            plugin.getCommand("jadehandshake")?.setExecutor(DISABLED_COMMAND)
        }

        @JvmStatic
        fun isEnabled(): Boolean = enabled

        @JvmStatic
        fun decodeClientProtocol(data: ByteArray): Int {
            if (data.size != Integer.BYTES) return -1
            val protocol = ByteBuffer.wrap(data).int
            return if (protocol > 0) protocol else -1
        }
    }
}
