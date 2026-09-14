package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.NicknameComponentResolver
import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.*
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

open class CrabVoicechatPlugin(private val plugin: CrabUtilities) : VoicechatPlugin {
    private val logger = plugin.getLogger()
    private val crossServerEnabled = plugin.getConfig().getBoolean("voicechat.cross-server.enabled", true)
    private val thisBackend = plugin.getConfig().getString("voicechat.cross-server.this-backend", "")
    private val lofiEnabled = plugin.getConfig().getBoolean("voicechat.lofi.enabled", true)
    private val lofiUrl = plugin.getConfig().getString("voicechat.lofi.youtube-url", "")
    private val lofiMusicVolume = plugin.getConfig().getDouble("voicechat.lofi.music-volume", 0.5).toFloat()
    private val lofiPlayerVolume = plugin.getConfig().getDouble("voicechat.lofi.player-volume", 0.25)
    // Preserve the original seed across the group rename and saved auto-rejoin data.
    private val lofiGroupId = deterministicGroupId(LOFI_GROUP_ID_SEED)
    private val persistentGroupNames = persistentGroupNames(plugin.getConfig().getStringList("voicechat.cross-server.persistent-groups"), lofiEnabled)
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

    override fun getPluginId(): String = PLUGIN_ID
    override fun initialize(api: VoicechatApi) { /* Nothing to initialise before the server is up. */ }
    override fun registerEvents(reg: EventRegistration) {
        reg.registerEvent(VoicechatServerStartedEvent::class.java, ::onServerStarted)
        if (lofiEnabled) reg.registerEvent(StaticSoundPacketEvent::class.java, ::onStaticSoundPacket)
        if (crossServerEnabled || lofiEnabled) {
            reg.registerEvent(PlayerConnectedEvent::class.java, ::onPlayerConnected)
            reg.registerEvent(JoinGroupEvent::class.java, ::onJoinGroup)
            reg.registerEvent(LeaveGroupEvent::class.java, ::onLeaveGroup)
            reg.registerEvent(PlayerDisconnectedEvent::class.java, ::onPlayerDisconnect)
        }
        if (!crossServerEnabled) return
        reg.registerEvent(CreateGroupEvent::class.java, ::onCreateGroup, Int.MIN_VALUE)
        reg.registerEvent(MicrophonePacketEvent::class.java, ::onMicrophonePacket)
    }

    private fun onServerStarted(event: VoicechatServerStartedEvent) {
        val api = event.getVoicechat()
        this.api = api
        VoiceMediaRegistry.getInstance().attach(api, lofiEnabled)
        try { callRingtones = CallRingtonePlayer(api, logger) } catch (e: IOException) { logger.warning("Call ringtones are unavailable: " + e.message) }
        val permanentGroups = ArrayList<Group>()
        for (name in persistentGroupNames) {
            val id = if (LOFI_GROUP_NAME == name) lofiGroupId else deterministicGroupId(name)
            val group = api.groupBuilder().setId(id).setName(name).setType(Group.Type.OPEN).setPersistent(true).build()
            permanentGroups.add(group)
            logger.info("Created persistent voice chat group '" + name + "' (" + group.getId() + ")")
        }
        if (lofiEnabled) {
            groupSpeechAttenuator = GroupSpeechAttenuator(api, lofiGroupId, lofiPlayerVolume, logger)
            if (lofiUrl.isNullOrBlank()) logger.warning("voicechat.lofi.enabled=true but youtube-url is empty; lofi playback disabled")
            else {
                val player = LofiStreamPlayer(api, lofiGroupId, lofiUrl, lofiMusicVolume, logger)
                lofiStreamPlayer = player
                player.start()
                for (onlinePlayer in Bukkit.getOnlinePlayers()) player.reconcileTarget(api.getConnectionOf(onlinePlayer.getUniqueId()))
            }
        }
        if (!crossServerEnabled) return
        if (thisBackend.isNullOrEmpty()) {
            logger.warning("voicechat.cross-server.enabled=true but this-backend is empty in config — cross-server voice DISABLED (set voicechat.cross-server.this-backend to this backend's Velocity server name)")
            return
        }
        startBridge(permanentGroups)
    }

    private fun startBridge(permanentGroups: List<Group>) {
        val api = api!!
        val bus = RedisVoiceBus(plugin)
        this.bus = bus
        val membership = MembershipTracker()
        this.membership = membership
        val audioRelay = AudioRelay(plugin, bus, membership, thisBackend!!, logger, voiceRoutes::get, if (lofiEnabled) lofiGroupId else null, lofiPlayerVolume)
        this.audioRelay = audioRelay
        audioRelay.setApi(api)
        val svcPackets = SvcPacketSender(plugin)
        this.svcPackets = svcPackets
        val roster = RosterTracker(plugin, svcPackets, thisBackend, logger, audioRelay::stopRemoteSpeaker)
        this.roster = roster
        val groupSynchronizer = GroupSynchronizer(plugin, api, bus, logger, ::reconcileMembership)
        this.groupSynchronizer = groupSynchronizer
        val callTargets = CallTargetSynchronizer(plugin, api, bus, groupSynchronizer, thisBackend, voiceSessions::get, voiceRoutes::get,
            { playerId -> restoreSessions.remove(playerId) }, ::scheduleMembershipReconciliation, logger)
        this.callTargets = callTargets
        bus.start(audioRelay::onAudioFrame, ::onLifecycleMessage)
        permanentGroups.forEach(groupSynchronizer::seedPermanent)
        groupSynchronizer.reconcileRegistry()
        audioRelay.start()
        // A 30-second broadcast refreshes remote roster entries and catches cold starts.
        rosterRebroadcastTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::rebroadcastLocalRoster), 20L * 30L, 20L * 30L)
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { roster.sweepStaleEntries() }, 20L * 60L, 20L * 30L)
        groupReconcileTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(groupSynchronizer::reconcileRegistry), 20L * 30L, 20L * 30L)
        routeRefreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable(::refreshLocalRoutes), 20L * 5L, 20L * 5L)
        callTargetReconcileTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { callTargets.reconcile(java.util.Set.copyOf(voiceSessions.keys)) }, 20L * 2L, 20L * 5L)
        logger.info("Cross-server voice bridge started (backend='" + thisBackend + "', " + permanentGroups.size + " permanent groups plus synced player groups; relying on tab-list sync for skins)")
    }

    private fun rebroadcastLocalRoster() {
        val bus = bus ?: return
        val membership = membership ?: return
        val groupSynchronizer = groupSynchronizer!!
        for (player in Bukkit.getOnlinePlayers()) {
            val playerId = player.getUniqueId()
            synchronized(membership) {
                // Disconnect removes the session before this lock, preventing a late heartbeat join.
                if (!voiceSessions.containsKey(playerId)) return@synchronized
                val groupId = membership.getLocalGroupOf(playerId)
                val route = voiceRoutes[playerId] ?: return@synchronized
                if (groupId == null) {
                    if (!restoreSessions.containsKey(playerId)) bus.deletePlayerGroup(playerId, null, route, groupSynchronizer::onRegistryWrite)
                    return@synchronized
                }
                val group = groupSynchronizer.definition(groupId) ?: return@synchronized
                bus.writePlayerGroup(playerId, group, PLAYER_GROUP_TTL_SECONDS, route, groupSynchronizer::onRegistryWrite)
                callTargets?.onMembershipReconciled(playerId, groupId)
                bus.publishRoster(VoiceMessages.encodeRosterJoin(groupId, playerId, voicechatName(player), route), playerId, route)
            }
        }
    }

    private fun onLifecycleMessage(message: String) {
        if (groupSynchronizer?.onLifecycleMessage(message) == true) return
        val ringStart = VoiceMessages.decodeCallRingStart(message)
        if (ringStart != null) { runVoiceControl { callRingtones?.start(ringStart) }; return }
        val ringStop = VoiceMessages.decodeCallRingStop(message)
        if (ringStop != null) { runVoiceControl { callRingtones?.stop(ringStop) }; return }
        val callJoin = VoiceMessages.decodeCallJoin(message)
        if (callJoin != null) { callTargets?.onJoinHint(callJoin); return }
        roster?.onLifecycleMessage(message)
    }
    private fun runVoiceControl(control: Runnable) {
        try { Bukkit.getScheduler().runTask(plugin, control) } catch (_: Exception) { /* Plugin is stopping. */ }
    }

    private fun refreshLocalRoutes() {
        val bus = bus ?: return
        if (voiceSessions.isEmpty()) return
        val sessions = java.util.Map.copyOf(voiceSessions)
        val routes = bus.fetchPlayerHomes(sessions.keys) ?: return
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                for ((playerId, session) in sessions) {
                    if (voiceSessions[playerId] != session) continue
                    val route = routes[playerId]
                    if (thisBackend != VoiceMessages.routeBackend(route)) { voiceRoutes.remove(playerId); continue }
                    val previous = voiceRoutes.put(playerId, route!!)
                    if (previous != route) {
                        scheduleMembershipReconciliation(playerId)
                        callTargets?.onRouteReady(playerId)
                    }
                }
            })
        } catch (_: Exception) { /* Plugin is stopping. */ }
    }

    /** Catch up the roster immediately, then restore the saved group on each SVC connection or hop. */
    private fun onPlayerConnected(event: PlayerConnectedEvent) {
        val connection = event.getConnection() ?: return
        lofiStreamPlayer?.reconcileTarget(connection)
        if (api == null || bus == null) return
        val playerId = connection.getPlayer().getUuid()
        val session = sessionSequence.incrementAndGet()
        voiceSessions[playerId] = session
        restoreSessions[playerId] = session
        voiceRoutes.remove(playerId)
        callRingtones?.removePlayer(playerId)
        callTargets?.onConnect(playerId)
        audioRelay!!.onPlayerConnect(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val p = Bukkit.getPlayer(playerId)
            if (p != null && p.isOnline()) {
                roster!!.onLocalConnect(playerId)
                roster!!.catchUpNewLocalConnection(p)
            }
        })
        scheduleRestoreAttempt(playerId, session, 0)
    }

    private fun scheduleRestoreAttempt(playerId: UUID, session: Long, attempt: Int) {
        if (attempt >= MAX_RESTORE_ATTEMPTS && voiceRoutes.containsKey(playerId)) { restoreSessions.remove(playerId, session); return }
        val read = Runnable read@{
            if (voiceSessions[playerId] != session) return@read
            val velocityRoute = bus!!.fetchPlayerHome(playerId)
            val velocityName = VoiceMessages.routeBackend(velocityRoute)
            if (attempt >= FAST_RESTORE_ATTEMPTS && velocityName != null && velocityName != thisBackend && homeMismatchWarned.add(velocityName)) {
                logger.severe("voicechat.cross-server.this-backend is '" + thisBackend + "' but Velocity calls this backend '" + velocityName + "'. Other backends will DROP all voice frames published by this server — fix modules/voicechat.yml (names are case-sensitive).")
            }
            val restoring = restoreSessions[playerId] == session
            val groupRead = if (restoring) bus!!.fetchPlayerGroup(playerId) else RedisVoiceBus.ReadResult<String>(true, null)
            val groupId = parseUuid(groupRead.value())
            val definition = if (groupId == null) null else groupSynchronizer!!.fetch(groupId)
            Bukkit.getScheduler().runTask(plugin, Runnable apply@{
                if (voiceSessions[playerId] != session) return@apply
                val player = Bukkit.getPlayer(playerId)
                val current = api!!.getConnectionOf(playerId)
                if (player == null || !player.isOnline() || current == null || !current.isConnected()) { scheduleRestoreAttempt(playerId, session, attempt + 1); return@apply }
                if (thisBackend != velocityName) { scheduleRestoreAttempt(playerId, session, attempt + 1); return@apply }
                voiceRoutes[playerId] = velocityRoute!!
                if (restoreSessions[playerId] != session) { scheduleMembershipReconciliation(playerId); return@apply }
                if (!groupRead.succeeded()) { scheduleRestoreAttempt(playerId, session, attempt + 1); return@apply }
                if (groupId == null) { restoreSessions.remove(playerId, session); return@apply }
                var group = groupSynchronizer!!.findLocal(groupId)
                if (group == null && definition != null) group = groupSynchronizer!!.apply(definition)
                if (group == null) { scheduleRestoreAttempt(playerId, session, attempt + 1); return@apply }
                val currentGroup = current.getGroup()
                if (currentGroup == null || groupId != currentGroup.getId()) {
                    applyingRestore.add(playerId)
                    try { current.setGroup(group) }
                    catch (e: Exception) {
                        logger.warning("Auto-rejoin failed for " + playerId + ": " + e.message)
                        scheduleRestoreAttempt(playerId, session, attempt + 1)
                        return@apply
                    } finally { applyingRestore.remove(playerId) }
                }
                Bukkit.getScheduler().runTask(plugin, Runnable { confirmRestore(playerId, session, groupId, attempt) })
            })
        }
        try {
            if (attempt == 0) Bukkit.getScheduler().runTaskAsynchronously(plugin, read)
            else Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, read, if (attempt < FAST_RESTORE_ATTEMPTS) FAST_RESTORE_RETRY_TICKS else SLOW_RESTORE_RETRY_TICKS)
        } catch (_: Exception) { /* Plugin is stopping. */ }
    }

    private fun confirmRestore(playerId: UUID, session: Long, groupId: UUID, attempt: Int) {
        if (voiceSessions[playerId] != session || restoreSessions[playerId] != session) return
        val committed = api!!.getConnectionOf(playerId)?.getGroup()
        if (committed == null || groupId != committed.getId()) { scheduleRestoreAttempt(playerId, session, attempt + 1); return }
        restoreSessions.remove(playerId, session)
        logger.info("Auto-rejoined " + playerId + " to group '" + committed.getName() + "'")
        scheduleMembershipReconciliation(playerId)
    }
    private fun onCreateGroup(event: CreateGroupEvent) {
        if (!event.isCancelled() && event.getConnection() != null) restoreSessions.remove(event.getConnection()!!.getPlayer().getUuid())
        groupSynchronizer?.onCreateGroup(event)
    }
    private fun onJoinGroup(event: JoinGroupEvent) {
        val playerId = event.getConnection()?.getPlayer()?.getUuid() ?: return
        if (applyingRestore.contains(playerId) || callTargets?.isApplying(playerId) == true) return
        restoreSessions.remove(playerId)
        callTargets?.onManualGroupChange(playerId)
        scheduleMembershipReconciliation(playerId)
    }
    private fun onLeaveGroup(event: LeaveGroupEvent) {
        val playerId = event.getConnection()?.getPlayer()?.getUuid() ?: return
        if (applyingRestore.contains(playerId) || callTargets?.isApplying(playerId) == true) return
        restoreSessions.remove(playerId)
        callTargets?.onManualGroupChange(playerId)
        scheduleMembershipReconciliation(playerId)
    }
    private fun scheduleMembershipReconciliation(playerId: UUID) {
        try { Bukkit.getScheduler().runTask(plugin, Runnable { reconcileMembership(playerId) }) } catch (_: Exception) { /* Plugin is stopping. */ }
    }

    private fun reconcileMembership(playerId: UUID) {
        val api = api ?: return
        val membership = membership ?: return
        val bus = bus ?: return
        val groupSynchronizer = groupSynchronizer!!
        synchronized(membership) {
            // The session token cancels queued reconciliations after disconnect.
            if (!voiceSessions.containsKey(playerId)) return
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !player.isOnline()) return
            val connection = api.getConnectionOf(playerId)
            val groupId = connection?.getGroup()?.getId()
            if (connection != null) lofiStreamPlayer?.updateTarget(connection, groupId)
            val previousGroupId = membership.setLocalGroup(playerId, groupId)
            val changed = previousGroupId != groupId
            val route = voiceRoutes[playerId] ?: return
            if (changed && previousGroupId != null) bus.publishRoster(VoiceMessages.encodeRosterLeave(previousGroupId, playerId, route), playerId, route)
            if (groupId == null) {
                if (restoreSessions.containsKey(playerId)) return
                bus.deletePlayerGroup(playerId, previousGroupId, route, groupSynchronizer::onRegistryWrite)
                return
            }
            val definition = groupSynchronizer.definition(groupId)
            if (definition == null) { logger.warning("Not publishing membership for unknown voice group " + groupId); return }
            val name = voicechatName(player)
            bus.writePlayerGroup(playerId, definition, PLAYER_GROUP_TTL_SECONDS, route, groupSynchronizer::onRegistryWrite)
            callTargets?.onMembershipReconciled(playerId, groupId)
            bus.publishRoster(VoiceMessages.encodeRosterJoin(groupId, playerId, name, route), playerId, route)
            if (changed) logger.info("Roster published: " + name + " (" + playerId + ") joined " + groupId)
        }
    }

    private fun voicechatName(player: Player): String = safeRosterName(NicknameComponentResolver.plainNicknameOrName(plugin.getEssentials(), player), player.getName())
    private fun onMicrophonePacket(event: MicrophonePacketEvent) { audioRelay?.onMicrophonePacketEvent(event) }
    private fun onStaticSoundPacket(event: StaticSoundPacketEvent) { groupSpeechAttenuator?.onStaticSoundPacket(event) }
    /** Runs before SVC removes native player state, allowing an accepted N+1 stop marker. */
    fun beforePlayerQuit(playerId: UUID) { audioRelay?.beforePlayerQuit(playerId) }

    private fun onPlayerDisconnect(event: PlayerDisconnectedEvent) {
        val playerId = event.getPlayerUuid()
        val route = voiceRoutes[playerId]
        voiceSessions.remove(playerId)
        restoreSessions.remove(playerId)
        applyingRestore.remove(playerId)
        callRingtones?.removePlayer(playerId)
        callTargets?.onDisconnect(playerId)
        lofiStreamPlayer?.removeTarget(playerId)
        groupSpeechAttenuator?.remove(playerId)
        audioRelay?.onPlayerDisconnect(event)
        val membership = membership
        if (membership != null) synchronized(membership) {
            val bus = bus
            if (bus != null) {
                val groupId = membership.getLocalGroupOf(playerId)
                if (groupId != null && route != null) bus.publishRoster(VoiceMessages.encodeRosterLeave(groupId, playerId, route), playerId, route)
                // Retain the 90-second player-group lease for hops and short relogs.
            }
            membership.onPlayerDisconnect(event)
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
        for (task in arrayOf(rosterRebroadcastTask, sweepTask, groupReconcileTask, routeRefreshTask, callTargetReconcileTask)) {
            if (task != null) try { task.cancel() } catch (_: Exception) {}
        }
        val bus = bus
        val membership = membership
        if (bus != null && membership != null) {
            for (player in Bukkit.getOnlinePlayers()) {
                val playerId = player.getUniqueId()
                synchronized(membership) {
                    val groupId = membership.getLocalGroupOf(playerId)
                    val route = voiceRoutes[playerId]
                    if (groupId == null || route == null) return@synchronized
                    bus.publishRoster(VoiceMessages.encodeRosterLeave(groupId, playerId, route), playerId, route)
                }
            }
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
        // SVC recording filenames need room for four-byte UTF-8 code points.
        private const val MAX_ROSTER_NAME_CODE_POINTS = 48
        private val UNSAFE_ROSTER_NAME = Pattern.compile("[\\p{Cc}/\\\\:*?\"<>|]")
        @JvmStatic fun persistentGroupNames(configured: List<String>, lofiEnabled: Boolean): List<String> {
            val names = ArrayList(if (configured.isEmpty()) listOf("Global #1", "Global #2", "Global #3") else configured)
            names.removeIf { LOFI_GROUP_ID_SEED.equals(it, ignoreCase = true) || LOFI_GROUP_NAME.equals(it, ignoreCase = true) }
            if (lofiEnabled) names.add(LOFI_GROUP_NAME)
            return java.util.List.copyOf(names)
        }
        @JvmStatic fun safeRosterName(name: String, fallback: String): String {
            var safe = UNSAFE_ROSTER_NAME.matcher(name).replaceAll("_")
            if (safe.codePointCount(0, safe.length) > MAX_ROSTER_NAME_CODE_POINTS) safe = safe.substring(0, safe.offsetByCodePoints(0, MAX_ROSTER_NAME_CODE_POINTS))
            return if (safe.isBlank()) fallback else safe
        }
        @JvmStatic fun deterministicGroupId(name: String): UUID = UUID.nameUUIDFromBytes(("crabcraft:svc:global:" + name).toByteArray(StandardCharsets.UTF_8))
        private fun parseUuid(value: String?): UUID? {
            if (value == null) return null
            return try { UUID.fromString(value) } catch (e: IllegalArgumentException) { null }
        }
    }
}
