package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.NicknameComponentResolver
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry
import de.maxhenkel.voicechat.api.*
import de.maxhenkel.voicechat.api.events.*
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

open class CrabVoicechatPlugin(private val plugin: CrabUtilities) : VoicechatPlugin {
    private val logger = plugin.logger
    private val crossServerEnabled = plugin.config.getBoolean("voicechat.cross-server.enabled", true)
    private val thisBackend = plugin.config.getString("voicechat.cross-server.this-backend", "")
    private val lofiEnabled = plugin.config.getBoolean("voicechat.lofi.enabled", true)
    private val lofiUrl = plugin.config.getString("voicechat.lofi.youtube-url", "")
    private val lofiMusicVolume = plugin.config.getDouble("voicechat.lofi.music-volume", 0.5).toFloat()
    private val lofiPlayerVolume = plugin.config.getDouble("voicechat.lofi.player-volume", 0.25)
    // Preserve the original identity across display-name changes and backend hops.
    private val lofiGroupId = deterministicGroupId(LOFI_GROUP_ID_SEED)
    private val persistentGroupNames =
        persistentGroupNames(plugin.config.getStringList("voicechat.cross-server.persistent-groups"), lofiEnabled)
    private var api: VoicechatServerApi? = null
    private var bus: RedisVoiceBus? = null
    private var membership: MembershipTracker? = null
    private var audioRelay: AudioRelay? = null
    private var roster: RosterTracker? = null
    private var svcPackets: SvcPacketSender? = null
    private var rosterRebroadcastTask: BukkitTask? = null
    private var sweepTask: BukkitTask? = null
    private var groupReconcileTask: BukkitTask? = null
    private var routeRefreshTask: BukkitTask? = null
    private var callTargetReconcileTask: BukkitTask? = null
    private var groupSynchronizer: GroupSynchronizer? = null
    private var callTargets: CallTargetSynchronizer? = null
    private var callRingtones: CallRingtonePlayer? = null
    private var lofiStreamPlayer: LofiStreamPlayer? = null
    private var groupSpeechAttenuator: GroupSpeechAttenuator? = null
    private val sessionSequence = AtomicLong()
    private val voiceSessions = ConcurrentHashMap<UUID, Long>()
    private val restoreSessions = ConcurrentHashMap<UUID, Long>()
    private val voiceRoutes = ConcurrentHashMap<UUID, String>()
    private val applyingRestore = ConcurrentHashMap.newKeySet<UUID>()
    private val homeMismatchWarned = ConcurrentHashMap.newKeySet<String>()

    override fun getPluginId() = PLUGIN_ID

    override fun initialize(api: VoicechatApi) {}

    override fun registerEvents(reg: EventRegistration) {
        reg.registerEvent(VoicechatServerStartedEvent::class.java, ::onServerStarted)
        if (lofiEnabled) reg.registerEvent(StaticSoundPacketEvent::class.java, ::onStaticSoundPacket)
        if (crossServerEnabled || lofiEnabled) {
            reg.registerEvent(PlayerConnectedEvent::class.java, ::onPlayerConnected)
            reg.registerEvent(JoinGroupEvent::class.java, { onManualGroupChange(it.connection) })
            reg.registerEvent(LeaveGroupEvent::class.java, { onManualGroupChange(it.connection) })
            reg.registerEvent(PlayerDisconnectedEvent::class.java, ::onPlayerDisconnect)
        }
        if (!crossServerEnabled) return
        reg.registerEvent(CreateGroupEvent::class.java, ::onCreateGroup, Int.MIN_VALUE)
        reg.registerEvent(MicrophonePacketEvent::class.java, ::onMicrophonePacket)
    }

    private fun onServerStarted(event: VoicechatServerStartedEvent) {
        val api = event.voicechat
        this.api = api
        VoiceMediaRegistry.getInstance().attach(api, lofiEnabled)
        try {
            callRingtones = CallRingtonePlayer(api, logger)
        } catch (e: IOException) {
            logger.warning("Call ringtones are unavailable: ${e.message}")
        }
        val permanentGroups = ArrayList<Group>()
        for (name in persistentGroupNames) {
            val id = if (name == LOFI_GROUP_NAME) lofiGroupId else deterministicGroupId(name)
            val group = api.groupBuilder().setId(id).setName(name).setType(Group.Type.OPEN).setPersistent(true).build()
            permanentGroups.add(group)
            logger.info("Created persistent voice chat group '$name' (${group.id})")
        }
        if (lofiEnabled) {
            groupSpeechAttenuator = GroupSpeechAttenuator(api, lofiGroupId, lofiPlayerVolume, logger)
            if (lofiUrl.isNullOrBlank())
                logger.warning("voicechat.lofi.enabled=true but youtube-url is empty; lofi playback disabled")
            else {
                val stream = LofiStreamPlayer(api, lofiGroupId, lofiUrl, lofiMusicVolume, logger)
                lofiStreamPlayer = stream
                stream.start()
                for (player in Bukkit.getOnlinePlayers()) stream.reconcileTarget(api.getConnectionOf(player.uniqueId))
            }
        }
        if (!crossServerEnabled) return
        if (thisBackend.isNullOrEmpty()) {
            logger.warning(
                "voicechat.cross-server.enabled=true but this-backend is empty in config — " +
                    "cross-server voice DISABLED (set voicechat.cross-server.this-backend to this backend's Velocity server name)"
            )
            return
        }
        startBridge(permanentGroups)
    }

    private fun startBridge(permanentGroups: List<Group>) {
        val api = api!!
        val backend = thisBackend!!
        val bus = RedisVoiceBus(plugin).also { this.bus = it }
        val membership = MembershipTracker().also { this.membership = it }
        val relay =
            AudioRelay(
                    plugin,
                    bus,
                    membership,
                    backend,
                    logger,
                    voiceRoutes::get,
                    if (lofiEnabled) lofiGroupId else null,
                    lofiPlayerVolume,
                )
                .also { audioRelay = it }
        relay.setApi(api)
        val packets = SvcPacketSender(plugin).also { svcPackets = it }
        val roster = RosterTracker(plugin, packets, backend, logger, relay::stopRemoteSpeaker).also { this.roster = it }
        val groups = GroupSynchronizer(plugin, api, bus, logger, ::reconcileMembership).also { groupSynchronizer = it }
        val targets =
            CallTargetSynchronizer(
                    plugin,
                    api,
                    bus,
                    groups,
                    backend,
                    voiceSessions::get,
                    voiceRoutes::get,
                    { restoreSessions.remove(it) },
                    ::scheduleMembershipReconciliation,
                    logger,
                )
                .also { callTargets = it }
        bus.start(relay::onAudioFrame, ::onLifecycleMessage)
        permanentGroups.forEach(groups::seedPermanent)
        groups.reconcileRegistry()
        relay.start()
        // Heartbeats support late-starting backends and expire ghosts after crashes.
        rosterRebroadcastTask =
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable { rebroadcastLocalRoster() }, 20L * 30, 20L * 30)
        sweepTask =
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable { roster.sweepStaleEntries() }, 20L * 60, 20L * 30)
        groupReconcileTask =
            Bukkit.getScheduler().runTaskTimer(plugin, Runnable { groups.reconcileRegistry() }, 20L * 30, 20L * 30)
        routeRefreshTask =
            Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, Runnable { refreshLocalRoutes() }, 20L * 5, 20L * 5)
        callTargetReconcileTask =
            Bukkit.getScheduler()
                .runTaskTimer(plugin, Runnable { targets.reconcile(voiceSessions.keys.toSet()) }, 20L * 2, 20L * 5)
        logger.info(
            "Cross-server voice bridge started (backend='$thisBackend', ${permanentGroups.size} permanent groups plus synced player groups; relying on tab-list sync for skins)"
        )
    }

    private fun rebroadcastLocalRoster() {
        val bus = bus ?: return
        val membership = membership ?: return
        val groups = groupSynchronizer!!
        for (player in Bukkit.getOnlinePlayers()) {
            val playerId = player.uniqueId
            synchronized(membership) {
                // Disconnect removes the session before taking this same lock.
                if (!voiceSessions.containsKey(playerId)) return@synchronized
                val groupId = membership.getLocalGroupOf(playerId)
                val route = voiceRoutes[playerId] ?: return@synchronized
                if (groupId == null) {
                    if (!restoreSessions.containsKey(playerId))
                        bus.deletePlayerGroup(playerId, null, route, groups::onRegistryWrite)
                    return@synchronized
                }
                val group = groups.definition(groupId) ?: return@synchronized
                bus.writePlayerGroup(playerId, group, PLAYER_GROUP_TTL_SECONDS, route, groups::onRegistryWrite)
                callTargets?.onMembershipReconciled(playerId, groupId)
                bus.publishRoster(
                    VoiceMessages.encodeRosterJoin(groupId, playerId, voicechatName(player), route),
                    playerId,
                    route,
                )
            }
        }
    }

    private fun onLifecycleMessage(message: String) {
        if (groupSynchronizer?.onLifecycleMessage(message) == true) return
        VoiceMessages.decodeCallRingStart(message)?.let { ring ->
            runVoiceControl { callRingtones?.start(ring) }
            return
        }
        VoiceMessages.decodeCallRingStop(message)?.let { ring ->
            runVoiceControl { callRingtones?.stop(ring) }
            return
        }
        VoiceMessages.decodeCallJoin(message)?.let {
            callTargets?.onJoinHint(it)
            return
        }
        roster?.onLifecycleMessage(message)
    }

    private fun runVoiceControl(control: Runnable) {
        try {
            Bukkit.getScheduler().runTask(plugin, control)
        } catch (_: Exception) {
            /* Plugin is stopping. */
        }
    }

    private fun refreshLocalRoutes() {
        val bus = bus ?: return
        if (voiceSessions.isEmpty()) return
        val sessions = voiceSessions.toMap()
        val routes = bus.fetchPlayerHomes(sessions.keys) ?: return
        try {
            Bukkit.getScheduler()
                .runTask(
                    plugin,
                    Runnable {
                        for ((playerId, session) in sessions) {
                            if (voiceSessions[playerId] != session) continue
                            val route = routes[playerId]
                            if (thisBackend != VoiceMessages.routeBackend(route)) {
                                voiceRoutes.remove(playerId)
                                continue
                            }
                            val previous = voiceRoutes.put(playerId, route!!)
                            if (previous != route) {
                                scheduleMembershipReconciliation(playerId)
                                callTargets?.onRouteReady(playerId)
                            }
                        }
                    },
                )
        } catch (_: Exception) {
            /* Plugin is stopping. */
        }
    }

    /** Every voice connection receives the current roster and attempts to restore its leased group. */
    private fun onPlayerConnected(event: PlayerConnectedEvent) {
        val connection = event.connection ?: return
        lofiStreamPlayer?.reconcileTarget(connection)
        if (api == null || bus == null) return
        val playerId = connection.player.uuid
        val session = sessionSequence.incrementAndGet()
        voiceSessions[playerId] = session
        restoreSessions[playerId] = session
        voiceRoutes.remove(playerId)
        callRingtones?.removePlayer(playerId)
        callTargets?.onConnect(playerId)
        audioRelay!!.onPlayerConnect(playerId)
        Bukkit.getScheduler()
            .runTask(
                plugin,
                Runnable {
                    val player = Bukkit.getPlayer(playerId)
                    if (player != null && player.isOnline) {
                        roster!!.onLocalConnect(playerId)
                        roster!!.catchUpNewLocalConnection(player)
                    }
                },
            )
        scheduleRestoreAttempt(playerId, session, 0)
    }

    private fun scheduleRestoreAttempt(playerId: UUID, session: Long, attempt: Int) {
        if (attempt >= MAX_RESTORE_ATTEMPTS && voiceRoutes.containsKey(playerId)) {
            restoreSessions.remove(playerId, session)
            return
        }
        val read = Runnable read@{
            if (voiceSessions[playerId] != session) return@read
            val velocityRoute = bus!!.fetchPlayerHome(playerId)
            val velocityName = VoiceMessages.routeBackend(velocityRoute)
            if (
                attempt >= FAST_RESTORE_ATTEMPTS &&
                    velocityName != null &&
                    velocityName != thisBackend &&
                    homeMismatchWarned.add(velocityName)
            ) {
                logger.severe(
                    "voicechat.cross-server.this-backend is '$thisBackend' but Velocity calls this backend '$velocityName'. " +
                        "Other backends will DROP all voice frames published by this server — fix modules/voicechat.yml (names are case-sensitive)."
                )
            }
            val restoring = restoreSessions[playerId] == session
            val groupRead = if (restoring) bus!!.fetchPlayerGroup(playerId) else RedisVoiceBus.ReadResult(true, null)
            val groupId = parseUuid(groupRead.value())
            val definition = groupId?.let { groupSynchronizer!!.fetch(it) }
            Bukkit.getScheduler()
                .runTask(
                    plugin,
                    Runnable apply@{
                        if (voiceSessions[playerId] != session) return@apply
                        val player = Bukkit.getPlayer(playerId)
                        val current = api!!.getConnectionOf(playerId)
                        if (
                            player == null ||
                                !player.isOnline ||
                                current == null ||
                                !current.isConnected ||
                                thisBackend != velocityName
                        ) {
                            scheduleRestoreAttempt(playerId, session, attempt + 1)
                            return@apply
                        }
                        voiceRoutes[playerId] = velocityRoute!!
                        if (restoreSessions[playerId] != session) {
                            scheduleMembershipReconciliation(playerId)
                            return@apply
                        }
                        if (!groupRead.succeeded()) {
                            scheduleRestoreAttempt(playerId, session, attempt + 1)
                            return@apply
                        }
                        if (groupId == null) {
                            restoreSessions.remove(playerId, session)
                            return@apply
                        }
                        val group =
                            groupSynchronizer!!.findLocal(groupId) ?: definition?.let { groupSynchronizer!!.apply(it) }
                        if (group == null) {
                            scheduleRestoreAttempt(playerId, session, attempt + 1)
                            return@apply
                        }
                        val currentGroup = current.group
                        if (currentGroup == null || groupId != currentGroup.id) {
                            applyingRestore.add(playerId)
                            try {
                                current.group = group
                            } catch (e: Exception) {
                                logger.warning("Auto-rejoin failed for $playerId: ${e.message}")
                                scheduleRestoreAttempt(playerId, session, attempt + 1)
                                return@apply
                            } finally {
                                applyingRestore.remove(playerId)
                            }
                        }
                        Bukkit.getScheduler()
                            .runTask(plugin, Runnable { confirmRestore(playerId, session, groupId, attempt) })
                    },
                )
        }
        try {
            if (attempt == 0) Bukkit.getScheduler().runTaskAsynchronously(plugin, read)
            else
                Bukkit.getScheduler()
                    .runTaskLaterAsynchronously(
                        plugin,
                        read,
                        if (attempt < FAST_RESTORE_ATTEMPTS) FAST_RESTORE_RETRY_TICKS else SLOW_RESTORE_RETRY_TICKS,
                    )
        } catch (_: Exception) {
            /* Plugin is stopping. */
        }
    }

    private fun confirmRestore(playerId: UUID, session: Long, groupId: UUID, attempt: Int) {
        if (voiceSessions[playerId] != session || restoreSessions[playerId] != session) return
        val committed = api!!.getConnectionOf(playerId)?.group
        if (committed == null || groupId != committed.id) {
            scheduleRestoreAttempt(playerId, session, attempt + 1)
            return
        }
        restoreSessions.remove(playerId, session)
        logger.info("Auto-rejoined $playerId to group '${committed.name}'")
        scheduleMembershipReconciliation(playerId)
    }

    private fun onCreateGroup(event: CreateGroupEvent) {
        if (!event.isCancelled) event.connection?.let { restoreSessions.remove(it.player.uuid) }
        groupSynchronizer?.onCreateGroup(event)
    }

    private fun onManualGroupChange(connection: VoicechatConnection?) {
        val playerId = connection?.player?.uuid ?: return
        if (playerId in applyingRestore || callTargets?.isApplying(playerId) == true) return
        restoreSessions.remove(playerId)
        callTargets?.onManualGroupChange(playerId)
        scheduleMembershipReconciliation(playerId)
    }

    private fun scheduleMembershipReconciliation(playerId: UUID) {
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable { reconcileMembership(playerId) })
        } catch (_: Exception) {
            /* Plugin is stopping. */
        }
    }

    private fun reconcileMembership(playerId: UUID) {
        val api = api ?: return
        val membership = membership ?: return
        val bus = bus ?: return
        val groups = groupSynchronizer!!
        synchronized(membership) {
            // The session map cancels work queued before disconnect, under the cleanup lock.
            if (!voiceSessions.containsKey(playerId)) return
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline) return
            val connection = api.getConnectionOf(playerId)
            val groupId = connection?.group?.id
            if (connection != null) lofiStreamPlayer?.updateTarget(connection, groupId)
            val previousGroupId = membership.setLocalGroup(playerId, groupId)
            val changed = previousGroupId != groupId
            val route = voiceRoutes[playerId] ?: return
            if (changed && previousGroupId != null)
                bus.publishRoster(VoiceMessages.encodeRosterLeave(previousGroupId, playerId, route), playerId, route)
            if (groupId == null) {
                if (restoreSessions.containsKey(playerId)) return
                bus.deletePlayerGroup(playerId, previousGroupId, route, groups::onRegistryWrite)
                return
            }
            val definition = groups.definition(groupId)
            if (definition == null) {
                logger.warning("Not publishing membership for unknown voice group $groupId")
                return
            }
            val name = voicechatName(player)
            bus.writePlayerGroup(playerId, definition, PLAYER_GROUP_TTL_SECONDS, route, groups::onRegistryWrite)
            callTargets?.onMembershipReconciled(playerId, groupId)
            bus.publishRoster(VoiceMessages.encodeRosterJoin(groupId, playerId, name, route), playerId, route)
            if (changed) logger.info("Roster published: $name ($playerId) joined $groupId")
        }
    }

    private fun voicechatName(player: Player) =
        safeRosterName(
            NicknameComponentResolver.plainNicknameOrName(plugin.getEssentials(), player),
            player.name,
        )

    private fun onMicrophonePacket(event: MicrophonePacketEvent) {
        audioRelay?.onMicrophonePacketEvent(event)
    }

    private fun onStaticSoundPacket(event: StaticSoundPacketEvent) {
        groupSpeechAttenuator?.onStaticSoundPacket(event)
    }

    /** Before native SVC cleanup: N+1 stop markers let listeners accept the relay after a hop. */
    fun beforePlayerQuit(playerId: UUID) {
        audioRelay?.beforePlayerQuit(playerId)
    }

    private fun onPlayerDisconnect(event: PlayerDisconnectedEvent) {
        val playerId = event.playerUuid
        val route = voiceRoutes[playerId]
        voiceSessions.remove(playerId)
        restoreSessions.remove(playerId)
        applyingRestore.remove(playerId)
        callRingtones?.removePlayer(playerId)
        callTargets?.onDisconnect(playerId)
        lofiStreamPlayer?.removeTarget(playerId)
        groupSpeechAttenuator?.remove(playerId)
        audioRelay?.onPlayerDisconnect(event)
        membership?.let { members ->
            synchronized(members) {
                val groupId = members.getLocalGroupOf(playerId)
                if (groupId != null && route != null)
                    bus?.publishRoster(VoiceMessages.encodeRosterLeave(groupId, playerId, route), playerId, route)
                // Keep the 90s group lease for short relogs and hops.
                members.onPlayerDisconnect(event)
            }
        }
        voiceRoutes.remove(playerId)
    }

    open fun shutdown() {
        callRingtones?.close()
        callTargets?.close()
        lofiStreamPlayer?.close()
        groupSpeechAttenuator?.close()
        voiceSessions.clear()
        restoreSessions.clear()
        for (task in
            arrayOf(rosterRebroadcastTask, sweepTask, groupReconcileTask, routeRefreshTask, callTargetReconcileTask)) {
            try {
                task?.cancel()
            } catch (_: Exception) {}
        }
        val bus = bus
        val membership = membership
        if (bus != null && membership != null)
            for (player in Bukkit.getOnlinePlayers()) synchronized(membership) {
                val playerId = player.uniqueId
                val groupId = membership.getLocalGroupOf(playerId)
                val route = voiceRoutes[playerId]
                if (groupId != null && route != null)
                    bus.publishRoster(VoiceMessages.encodeRosterLeave(groupId, playerId, route), playerId, route)
            }
        voiceRoutes.clear()
        groupSynchronizer?.shutdown()
        bus?.shutdown()
        roster?.shutdown()
        audioRelay?.shutdown()
        VoiceMediaRegistry.getInstance().detach()
    }

    companion object {
        const val PLUGIN_ID = "crabutilities"
        const val LOFI_GROUP_NAME = "Lofi 24/7 CrabFM"
        private const val LOFI_GROUP_ID_SEED = "24/7 Lofi"
        private const val PLAYER_GROUP_TTL_SECONDS = 90L
        private const val FAST_RESTORE_ATTEMPTS = 8
        private const val MAX_RESTORE_ATTEMPTS = 26
        private const val FAST_RESTORE_RETRY_TICKS = 10L
        private const val SLOW_RESTORE_RETRY_TICKS = 20L * 5L
        private const val MAX_ROSTER_NAME_CODE_POINTS = 48
        private val UNSAFE_ROSTER_NAME = Pattern.compile("[\\p{Cc}/\\\\:*?\"<>|]")

        @JvmStatic
        fun persistentGroupNames(configured: List<String>, lofiEnabled: Boolean): List<String> {
            val names =
                (if (configured.isEmpty()) listOf("Global #1", "Global #2", "Global #3") else configured)
                    .toMutableList()
            names.removeIf { it.equals(LOFI_GROUP_ID_SEED, true) || it.equals(LOFI_GROUP_NAME, true) }
            if (lofiEnabled) names.add(LOFI_GROUP_NAME)
            return java.util.List.copyOf(names)
        }

        @JvmStatic
        fun safeRosterName(name: String, fallback: String): String {
            var safe = UNSAFE_ROSTER_NAME.matcher(name).replaceAll("_")
            if (safe.codePointCount(0, safe.length) > MAX_ROSTER_NAME_CODE_POINTS)
                safe = safe.substring(0, safe.offsetByCodePoints(0, MAX_ROSTER_NAME_CODE_POINTS))
            return if (safe.isBlank()) fallback else safe
        }

        @JvmStatic
        fun deterministicGroupId(name: String) =
            UUID.nameUUIDFromBytes(("crabcraft:svc:global:" + name).toByteArray(UTF_8))

        private fun parseUuid(value: String?): UUID? =
            try {
                value?.let(UUID::fromString)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
