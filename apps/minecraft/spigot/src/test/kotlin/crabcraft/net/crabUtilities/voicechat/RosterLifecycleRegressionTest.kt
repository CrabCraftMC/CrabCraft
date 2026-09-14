package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import sun.misc.Unsafe
import de.maxhenkel.voicechat.api.ServerPlayer
import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitScheduler
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.UUID
import java.util.logging.Logger

/** Exercises Redis delivery separately from Bukkit task execution. */
object RosterLifecycleRegressionTest {
  private val SPEAKER = UUID.randomUUID()
  private val GROUP = UUID.randomUUID()
  private const val FIRST_ROUTE = "survival\u0000proxy:1"
  private const val RETURN_ROUTE = "survival\u0000proxy:3"
  private val tasks = ArrayDeque<Runnable>()
  private val localPlayers = HashMap<UUID, Player>()
  private val listener = player(UUID.randomUUID())
  // The mocked scheduler never inspects its plugin; Kotlin requires a non-null Bukkit argument.
  private val plugin = run {
    val field = Unsafe::class.java.getDeclaredField("theUnsafe")
    field.isAccessible = true
    (field.get(null) as Unsafe).allocateInstance(CrabUtilities::class.java) as CrabUtilities
  }

  @JvmStatic fun main(args: Array<String>) {
    val scheduler = proxy(BukkitScheduler::class.java) { method, arguments ->
      if (method == "runTask") tasks.add(arguments!![1] as Runnable)
      null
    }
    val serverField = Bukkit::class.java.getDeclaredField("server")
    serverField.isAccessible = true
    serverField.set(null, proxy(Server::class.java) { method, arguments ->
      when (method) {
        "getScheduler" -> scheduler
        "getPlayer" -> localPlayers[arguments!![0]]
        "getOnlinePlayers" -> listOf(listener)
        "getLogger" -> Logger.getAnonymousLogger()
        "getName", "getVersion", "getBukkitVersion" -> "voice-regression"
        else -> null
      }
    })
    delayedLeaveCannotRemoveReturnToSameBackend()
    leaveStopsAudioAndRemovesState()
    catchUpCannotResurrectQueuedLeave()
    localArrivalDiscardsRemoteCache()
    groupMoveStopsOldAudio()
    shutdownCancelsQueuedRoster()
    legacyRosterStillDecodes()
    audioSequenceSurvivesRosterChange(UUID.randomUUID(), FIRST_ROUTE, false)
    audioSequenceSurvivesRosterChange(GROUP, FIRST_ROUTE, true)
    audioSequenceSurvivesRosterChange(GROUP, RETURN_ROUTE, false)
  }

  private fun delayedLeaveCannotRemoveReturnToSameBackend() {
    val fixture = Fixture()
    fixture.join(GROUP, FIRST_ROUTE)
    fixture.join(GROUP, "creative\u0000proxy:2")
    fixture.join(GROUP, RETURN_ROUTE)
    fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE))
    drain()
    check(fixture.packets.states.containsKey(SPEAKER), "a delayed leave from the previous visit removed the return hop")
    check(fixture.packets.removals == 0, "a stale leave sent a client removal")
  }

  private fun leaveStopsAudioAndRemovesState() {
    val fixture = Fixture()
    fixture.join(GROUP, FIRST_ROUTE)
    fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE))
    drain()
    check(!fixture.packets.states.containsKey(SPEAKER), "departed speaker remained in the client roster")
    check(fixture.invalidated == listOf(SPEAKER), "leaving did not stop the remote audio channel")
  }

  private fun catchUpCannotResurrectQueuedLeave() {
    val fixture = Fixture()
    fixture.join(GROUP, FIRST_ROUTE)
    fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE))
    fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", RETURN_ROUTE))
    // Catch-up must observe the same order as normal state updates, even when Redis runs ahead.
    tasks.add { fixture.roster.catchUpNewLocalConnection(listener) }
    drain()
    check(GROUP == fixture.packets.states[SPEAKER], "catch-up lost the latest hop")
    check(fixture.packets.events.last() == "state", "queued removal erased newer client state")
  }

  private fun localArrivalDiscardsRemoteCache() {
    val fixture = Fixture()
    fixture.join(GROUP, FIRST_ROUTE)
    localPlayers[SPEAKER] = player(SPEAKER)
    fixture.roster.onLocalConnect(SPEAKER)
    fixture.join(GROUP, FIRST_ROUTE)
    fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE))
    drain()
    check(fixture.packets.removals == 0, "old remote leave removed native local state")
    localPlayers.remove(SPEAKER)
    fixture.packets.states.clear()
    fixture.roster.catchUpNewLocalConnection(listener)
    check(fixture.packets.states.isEmpty(), "catch-up resurrected the stale pre-arrival remote entry")
  }

  private fun groupMoveStopsOldAudio() {
    val fixture = Fixture()
    fixture.join(GROUP, FIRST_ROUTE)
    val nextGroup = UUID.randomUUID()
    fixture.join(nextGroup, FIRST_ROUTE)
    fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE))
    drain()
    check(nextGroup == fixture.packets.states[SPEAKER], "old-group leave removed the new group")
    check(fixture.invalidated == listOf(SPEAKER), "group move retained the old audio targets")
  }

  private fun legacyRosterStillDecodes() {
    val legacy = VoiceMessages.decodeRosterJoin(VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", "survival"))
    check(legacy != null && "survival" == legacy.backend(), "legacy backend-only roster stopped decoding")
    val current = VoiceMessages.decodeRosterJoin(VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", FIRST_ROUTE))
    check(current != null && FIRST_ROUTE == current.route(), "roster discarded its hop token")
  }

  private fun shutdownCancelsQueuedRoster() {
    val fixture = Fixture()
    fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", FIRST_ROUTE))
    fixture.roster.shutdown()
    drain()
    check(fixture.packets.states.isEmpty(), "queued roster join survived shutdown")
  }

  /** A listener leaves first, misses the reset, then hears the same speaker again. */
  private fun audioSequenceSurvivesRosterChange(nextGroup: UUID, nextRoute: String, leaveFirst: Boolean) {
    val listenerId = listener.uniqueId
    val remainingId = UUID.randomUUID()
    val membership = MembershipTracker()
    membership.setLocalGroup(listenerId, GROUP)
    membership.setLocalGroup(remainingId, GROUP)
    val movedListener = SequenceClient()
    val remainingListener = SequenceClient()
    val clients = mapOf(listenerId to movedListener, remainingId to remainingListener)
    val channelTargets = ArrayList<Set<UUID>>()
    val api = proxy(VoicechatServerApi::class.java) { method, arguments ->
      when (method) {
        "getConnectionOf" -> if (SPEAKER == arguments!![0]) null else connection(arguments[0] as UUID)
        "createStaticAudioChannel" -> sequenceChannel(clients, channelTargets)
        else -> null
      }
    }
    val relay = AudioRelay(null, null, membership, "lobby", Logger.getAnonymousLogger(), { null }, null, 1.0)
    relay.setApi(api)
    val roster = RosterTracker(plugin, Packets(), "lobby", Logger.getAnonymousLogger(), relay::stopRemoteSpeaker)
    try {
      roster.onLifecycleMessage(VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", FIRST_ROUTE))
      drain()
      seedRouteCache(relay, FIRST_ROUTE)
      repeat(501) { sendAudio(relay, GROUP, FIRST_ROUTE) }
      check(movedListener.accepted == 501, "initial audio did not reach the listener")
      membership.setLocalGroup(listenerId, UUID.randomUUID())
      sendAudio(relay, GROUP, FIRST_ROUTE) // Removes this listener from audio targets.
      if (leaveFirst) roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE))
      roster.onLifecycleMessage(VoiceMessages.encodeRosterJoin(nextGroup, SPEAKER, "Crab", nextRoute))
      drain()
      check(remainingListener.stops == 1, "roster change did not stop the old listeners' audio")
      check(movedListener.stops == 0, "test listener unexpectedly received the reset")
      check(channelTargets.all { it.isEmpty() }, "roster change retained old audio targets")
      membership.setLocalGroup(listenerId, nextGroup)
      seedRouteCache(relay, nextRoute)
      val before = movedListener.accepted
      repeat(10) { sendAudio(relay, nextGroup, nextRoute) }
      check(movedListener.accepted - before == 10,
        "listener rejected audio after missing the old group's reset (leave=" + leaveFirst +
          ", route=" + VoiceMessages.routeBackend(nextRoute) + ")")
    } finally { relay.shutdown() }
  }

  private fun sendAudio(relay: AudioRelay, group: UUID, route: String) {
    relay.onAudioFrame(group, VoiceMessages.encodeAudioFrame(route, SPEAKER, false, byteArrayOf(1)))
  }

  @Suppress("UNCHECKED_CAST")
  private fun seedRouteCache(relay: AudioRelay, route: String) {
    // Keep Redis I/O out of this test while exercising the real relay and roster handlers.
    val cacheType = Class.forName(AudioRelay::class.java.name + "\$PlayerRouteCache")
    val constructor = cacheType.getDeclaredConstructor(String::class.java, Long::class.javaPrimitiveType)
    constructor.isAccessible = true
    val cache = AudioRelay::class.java.getDeclaredField("playerHomeCache")
    cache.isAccessible = true
    (cache.get(relay) as MutableMap<UUID, Any>)[SPEAKER] = constructor.newInstance(route, System.currentTimeMillis())
  }

  private fun connection(id: UUID): VoicechatConnection {
    val player = proxy(ServerPlayer::class.java) { method, _ -> if (method == "getUuid") id else null }
    return proxy(VoicechatConnection::class.java) { method, _ -> if (method == "getPlayer") player else null }
  }

  private fun sequenceChannel(clients: Map<UUID, SequenceClient>, channelTargets: MutableList<Set<UUID>>): StaticAudioChannel {
    val targets = HashSet<UUID>()
    channelTargets.add(targets)
    var sequence = 0L
    return proxy(StaticAudioChannel::class.java) { method, arguments ->
      when (method) {
        "addTarget" -> targets.add((arguments!![0] as VoicechatConnection).player.uuid)
        "clearTargets" -> targets.clear()
        "send", "flush" -> {
          val data = if (method == "flush") ByteArray(0) else arguments!![0] as ByteArray
          val current = sequence++
          for (target in targets) clients[target]!!.receive(current, data)
        }
      }
      null
    }
  }

  /** SVC checks packet sequence before handling an empty stop/reset packet. */
  private class SequenceClient {
    var lastSequence = -1L
    var accepted = 0
    var stops = 0
    fun receive(sequence: Long, data: ByteArray) {
      if (lastSequence >= 0L && sequence <= lastSequence) return
      if (data.isEmpty()) { lastSequence = -1L; stops++ }
      else { lastSequence = sequence; accepted++ }
    }
  }

  private fun drain() { while (!tasks.isEmpty()) tasks.remove().run() }

  private fun player(id: UUID): Player = proxy(Player::class.java) { method, _ ->
    when (method) {
      "getUniqueId" -> id
      "getName" -> "Listener"
      "isOnline" -> true
      else -> null
    }
  }

  private fun <T> proxy(type: Class<T>, handler: Handler): T = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments -> handler.invoke(method.name, arguments) })

  private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }

  private fun interface Handler { fun invoke(method: String, arguments: Array<out Any?>?): Any? }

  private class Fixture {
    val packets = Packets()
    val invalidated = ArrayList<UUID>()
    val roster = RosterTracker(plugin, packets, "lobby", Logger.getAnonymousLogger(), invalidated::add)
    fun join(group: UUID, route: String) {
      roster.onLifecycleMessage(VoiceMessages.encodeRosterJoin(group, SPEAKER, "Crab", route))
      drain()
    }
  }

  private class Packets : SvcPacketSender(null) {
    val states = HashMap<UUID, UUID?>()
    val events = ArrayList<String>()
    var removals = 0
    override fun sendState(recipient: Player, playerUuid: UUID, playerName: String?, groupId: UUID?) {
      states[playerUuid] = groupId
      events.add("state")
    }
    override fun sendRemove(recipient: Player, playerUuid: UUID) {
      states.remove(playerUuid)
      events.add("remove")
      removals++
    }
  }
}
