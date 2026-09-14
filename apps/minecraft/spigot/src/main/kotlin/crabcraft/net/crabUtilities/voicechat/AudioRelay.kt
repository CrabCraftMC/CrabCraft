package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.function.Function
import java.util.logging.Logger

/** Cross-server group audio relay, preserving native local routing and speaker channel identities. */
class AudioRelay(private val plugin: CrabUtilities?, private val bus: RedisVoiceBus?, private val membership: MembershipTracker,
                 private val thisBackend: String, private val logger: Logger, private val localRoute: Function<UUID, String?>,
                 private val attenuatedGroupId: UUID?, private val attenuatedGroupGain: Double) {
    private var api: VoicechatServerApi? = null
    private var remoteVolumeScaler: OpusVolumeScaler? = null
    private val channels = ConcurrentHashMap<UUID, ChannelEntry>()
    private val playerHomeCache = ConcurrentHashMap<UUID, PlayerRouteCache>()
    private val playerHomeRefreshes = ConcurrentHashMap.newKeySet<UUID>()
    private val lastNativePackets = ConcurrentHashMap<UUID, StaticSoundPacket>()
    private val quittingSpeakers = ConcurrentHashMap.newKeySet<UUID>()
    private val disconnectResets = ConcurrentHashMap<UUID, DisconnectReset>()
    private val firstRelayLogged = ConcurrentHashMap.newKeySet<UUID>()
    private val nullChannelLogged = ConcurrentHashMap.newKeySet<UUID>()
    private var evictionTask: BukkitTask? = null
    private val playerHomeRefreshExecutor = ThreadPoolExecutor(1, 2, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(64),
        { r -> Thread(r, "CrabUtilities-VoiceRoute").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun setApi(api: VoicechatServerApi) {
        this.api = api
        if (attenuatedGroupId != null && attenuatedGroupGain < 1.0) remoteVolumeScaler = OpusVolumeScaler(api, attenuatedGroupGain)
    }
    fun start() {
        evictionTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin!!, Runnable(::evictIdle), 20L * 30L, 20L * 30L)
    }
    fun shutdown() {
        evictionTask?.let { try { it.cancel() } catch (_: Exception) {} }
        for (entry in channels.values) try { entry.channel.flush() } catch (_: Exception) {}
        channels.clear()
        lastNativePackets.clear()
        quittingSpeakers.clear()
        disconnectResets.clear()
        playerHomeRefreshExecutor.shutdownNow()
        remoteVolumeScaler?.close()
    }

    /** Native SVC handles normal local packets; only packets racing a quit are cancelled. */
    fun onMicrophonePacketEvent(event: MicrophonePacketEvent) {
        val conn = event.getSenderConnection() ?: return
        val group = conn.getGroup() ?: return
        val groupId = group.getId()
        val packet = event.getPacket() ?: return
        val speakerId = conn.getPlayer().getUuid()
        try { lastNativePackets[speakerId] = packet.staticSoundPacketBuilder().channelId(speakerId).build() }
        catch (e: Exception) { logger.fine { "Could not read microphone sequence for " + speakerId + ": " + e.message } }
        if (stopQuittingPacket(event, speakerId)) return
        val opus = packet.getOpusEncodedData()
        // Empty Opus is SVC's stop/reset marker, so it must cross backends too.
        if (!isRelayPayload(opus)) return
        val route = localRoute.apply(speakerId)
        if (thisBackend != VoiceMessages.routeBackend(route)) return
        val frame = VoiceMessages.encodeAudioFrame(route!!, speakerId, packet.isWhispering(), opus!!)
        if (stopQuittingPacket(event, speakerId)) return
        bus!!.publishAudio(groupId, speakerId, frame, opus.isEmpty())
    }

    private fun stopQuittingPacket(event: MicrophonePacketEvent, speakerId: UUID): Boolean {
        if (!quittingSpeakers.contains(speakerId)) return false
        event.cancel()
        try { resetSpeaker(speakerId) } catch (e: Exception) { logger.warning("Failed to reset late microphone packet for " + speakerId + ": " + e.message) }
        return true
    }

    fun onAudioFrame(groupId: UUID, data: ByteArray) {
        val api = api ?: return
        val frame = try { VoiceMessages.decodeAudioFrame(data) } catch (e: Exception) {
            logger.fine { "Skipping malformed audio frame on " + groupId + ": " + e.message }
            return
        }
        if (thisBackend == VoiceMessages.routeBackend(frame.route())) return
        // A local arrival must never receive a late frame from its previous backend.
        if (api.getConnectionOf(frame.speaker()) != null) return
        val localTargets = membership.getLocalMembers(groupId)
        if (localTargets.isEmpty()) return
        if (!isAuthoritativeOrigin(frame.speaker(), frame.route())) return
        var entry = channels[frame.speaker()]
        if (entry == null) {
            val ch = createChannel(frame.speaker())
            if (ch == null) {
                if (nullChannelLogged.add(frame.speaker())) logger.warning("StaticAudioChannel.create returned null for speaker " + frame.speaker() + " — audio for this player will be dropped until the API accepts the channel ID. Will retry every frame.")
                return
            }
            nullChannelLogged.remove(frame.speaker())
            entry = ChannelEntry(ch)
            channels[frame.speaker()] = entry
        }
        synchronized(entry) {
            // Recheck under the channel lock to order route hand-off resets.
            if (api.getConnectionOf(frame.speaker()) != null || channels[frame.speaker()] !== entry) return
            syncTargets(entry, localTargets)
            entry.lastUsed = System.currentTimeMillis()
            try {
                var opus: ByteArray? = frame.opus()
                val scaler = remoteVolumeScaler
                if (scaler != null && groupId == attenuatedGroupId) opus = scaler.scaleNext(frame.speaker(), opus)
                entry.channel.send(opus)
                if (firstRelayLogged.add(frame.speaker())) logger.info("Now relaying audio from " + frame.speaker() + " (backend='" + VoiceMessages.routeBackend(frame.route()) + "') to " + localTargets.size + " local listener(s)")
            } catch (e: Exception) { logger.warning("Audio send failed for " + frame.speaker() + ": " + e.message) }
        }
    }

    /** Retain a stopped remote channel's sequence until its normal idle eviction. */
    fun stopRemoteSpeaker(speakerId: UUID) { resetRelayChannel(speakerId, channels[speakerId]) }
    fun invalidateSpeaker(speakerId: UUID) { resetRelayChannel(speakerId, channels.remove(speakerId)) }
    private fun resetRelayChannel(speakerId: UUID, entry: ChannelEntry?) {
        if (entry != null) synchronized(entry) {
            try { entry.channel.flush() } catch (_: Exception) {}
            try { entry.channel.clearTargets() } catch (_: Exception) {}
            entry.currentTargets = emptySet()
        }
        playerHomeCache.remove(speakerId)
        firstRelayLogged.remove(speakerId)
        nullChannelLogged.remove(speakerId)
        remoteVolumeScaler?.remove(speakerId)
    }

    fun beforePlayerQuit(speakerId: UUID) {
        quittingSpeakers.add(speakerId)
        try { resetSpeaker(speakerId) } catch (e: Exception) { logger.warning("Failed to send pre-quit voice stop marker for " + speakerId + ": " + e.message) }
    }
    fun onPlayerConnect(speakerId: UUID) {
        val pending = disconnectResets.remove(speakerId)
        if (pending != null) try { sendLocalReset(speakerId, pending.targets) } catch (e: Exception) { logger.warning("Failed to reset voice before reconnect for " + speakerId + ": " + e.message) }
        quittingSpeakers.remove(speakerId)
        lastNativePackets.remove(speakerId)
        invalidateSpeaker(speakerId)
    }
    fun onPlayerDisconnect(event: PlayerDisconnectedEvent) {
        val speakerId = event.getPlayerUuid()
        quittingSpeakers.add(speakerId)
        val groupId = membership.getLocalGroupOf(speakerId)
        val targets = if (groupId == null) emptySet() else membership.getLocalMembers(groupId)
        val reset = DisconnectReset(targets)
        disconnectResets[speakerId] = reset
        try {
            // Repeat the early reset for a microphone frame already being processed.
            resetSpeaker(speakerId)
        } catch (e: Exception) { logger.warning("Failed to send native stop marker for " + speakerId + ": " + e.message) }
        finally {
            invalidateSpeaker(speakerId)
            Bukkit.getScheduler().runTaskLater(plugin!!, Runnable { finishDisconnectReset(speakerId, reset, false) }, 1L)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { finishDisconnectReset(speakerId, reset, true) }, 2L)
        }
    }

    private fun finishDisconnectReset(speakerId: UUID, reset: DisconnectReset, finalPass: Boolean) {
        if (disconnectResets[speakerId] !== reset) return
        try {
            if (api!!.getConnectionOf(speakerId) == null) sendLocalReset(speakerId, reset.targets)
        } catch (e: Exception) { logger.warning("Failed to send final voice stop marker for " + speakerId + ": " + e.message) }
        finally {
            if (finalPass && disconnectResets.remove(speakerId, reset)) {
                lastNativePackets.remove(speakerId)
                quittingSpeakers.remove(speakerId)
            }
        }
    }

    @Throws(ReflectiveOperationException::class)
    private fun resetSpeaker(speakerId: UUID) {
        val groupId = membership.getLocalGroupOf(speakerId) ?: return
        sendLocalReset(speakerId, membership.getLocalMembers(groupId))
        val route = localRoute.apply(speakerId)
        if (thisBackend == VoiceMessages.routeBackend(route)) bus!!.publishAudio(groupId, speakerId, VoiceMessages.encodeAudioFrame(route!!, speakerId, false, ByteArray(0)), true)
    }

    @Throws(ReflectiveOperationException::class)
    private fun sendLocalReset(speakerId: UUID, targets: Set<UUID>) {
        val lastPacket = lastNativePackets[speakerId] ?: return
        val reset = nextSequenceStop(lastPacket)
        for (targetId in targets) {
            if (speakerId == targetId) continue
            val target = api!!.getConnectionOf(targetId)
            if (target != null) api!!.sendStaticSoundPacketTo(target, reset)
        }
    }

    private fun createChannel(speakerId: UUID): StaticAudioChannel? {
        try {
            val channel = api!!.createStaticAudioChannel(speakerId)
            channel?.setBypassGroupIsolation(true)
            return channel
        } catch (e: Exception) { logger.warning("Failed to create static audio channel for " + speakerId + ": " + e.message); return null }
    }

    private fun syncTargets(entry: ChannelEntry, wantedPlayerIds: Set<UUID>) {
        if (entry.currentTargets == wantedPlayerIds) return
        val added = HashSet<UUID>()
        try {
            entry.channel.clearTargets()
            for (id in wantedPlayerIds) {
                val conn = api!!.getConnectionOf(id) ?: continue
                entry.channel.addTarget(conn)
                added.add(id)
            }
        } catch (e: Exception) { logger.warning("Failed to sync audio targets: " + e.message) }
        // Retry transient lookup failures on the next frame.
        entry.currentTargets = java.util.Set.copyOf(added)
    }

    private fun isAuthoritativeOrigin(speakerId: UUID, claimedRoute: String): Boolean {
        val now = System.currentTimeMillis()
        val cached = playerHomeCache[speakerId]
        if (cached == null || cached.route == null) { refreshPlayerHome(speakerId); return false }
        if (cached.route != claimedRoute) {
            // A new hop token invalidates the old cached route immediately.
            playerHomeCache.remove(speakerId, cached)
            refreshPlayerHome(speakerId)
            return false
        }
        if (now - cached.fetchedAt > PLAYER_HOME_CACHE_MS) refreshPlayerHome(speakerId)
        return true
    }

    private fun refreshPlayerHome(speakerId: UUID) {
        if (!playerHomeRefreshes.add(speakerId)) return
        try {
            playerHomeRefreshExecutor.execute {
                try { playerHomeCache[speakerId] = PlayerRouteCache(bus!!.fetchPlayerHome(speakerId), System.currentTimeMillis()) }
                finally { playerHomeRefreshes.remove(speakerId) }
            }
        } catch (e: RejectedExecutionException) { playerHomeRefreshes.remove(speakerId) }
    }

    private fun evictIdle() {
        val now = System.currentTimeMillis()
        for ((speakerId, entry) in channels) if (now - entry.lastUsed > CHANNEL_IDLE_MS) invalidateSpeaker(speakerId)
        playerHomeCache.entries.removeIf { now - it.value.fetchedAt > PLAYER_HOME_CACHE_MS * 60 }
    }

    private class ChannelEntry(val channel: StaticAudioChannel) {
        @Volatile var currentTargets: Set<UUID> = emptySet()
        @Volatile var lastUsed = System.currentTimeMillis()
    }
    private data class PlayerRouteCache(val route: String?, val fetchedAt: Long)
    private data class DisconnectReset(val targets: Set<UUID>)

    companion object {
        private const val CHANNEL_IDLE_MS = 60_000L
        private const val PLAYER_HOME_CACHE_MS = 1_000L
        @JvmStatic fun isRelayPayload(opus: ByteArray?): Boolean = opus != null
        /** Support SVC's older builder field and newer sequence setter for accepted N+1 resets. */
        @JvmStatic @Throws(ReflectiveOperationException::class)
        fun nextSequenceStop(lastPacket: StaticSoundPacket): StaticSoundPacket {
            val builder = lastPacket.staticSoundPacketBuilder().channelId(lastPacket.getChannelId()).opusEncodedData(ByteArray(0))
            val nextSequence = lastPacket.getSequenceNumber() + 1L
            try { builder.javaClass.getMethod("sequenceNumber", Long::class.javaPrimitiveType).invoke(builder, nextSequence) }
            catch (e: NoSuchMethodException) {
                var type: Class<*>? = builder.javaClass
                while (type != null) {
                    try {
                        val field = type.getDeclaredField("sequenceNumber")
                        field.isAccessible = true
                        field.setLong(builder, nextSequence)
                        return builder.build()
                    } catch (_: NoSuchFieldException) { type = type.superclass }
                }
                throw e
            }
            return builder.build()
        }
    }
}
