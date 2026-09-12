package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.media.item.MediaItemCodec
import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.ServerPlayer
import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import de.maxhenkel.voicechat.api.events.SoundPacketEvent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

object LofiVoicechatRegressionTest {
  @JvmStatic fun main(args: Array<String>) {
    verifyGroupSelection()
    verifySpeechRoutingSelection()
    verifyVolumeScaling()
    verifyLofiChannelTargets()
    verifyFailureRetryRate()
    verifyDefaultsAndLegacyNamespace()
  }

  private fun verifyGroupSelection() {
    val enabled = CrabVoicechatPlugin.persistentGroupNames(listOf("Global #1", "Global #2", "Global #3"), true)
    check(enabled == listOf("Global #1", "Global #2", "Global #3", CrabVoicechatPlugin.LOFI_GROUP_NAME),
      "enabled lofi group was not added after the three global groups")
    check(enabled.sorted() == enabled, "Simple Voice Chat's alphabetical ordering would not place lofi fourth")
    val disabled = CrabVoicechatPlugin.persistentGroupNames(listOf("Global #1", "24/7 Lofi", CrabVoicechatPlugin.LOFI_GROUP_NAME), false)
    check(disabled == listOf("Global #1"), "disabled lofi group was still created")
    val first = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi")
    val second = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi")
    check(first == second, "lofi group ID was not deterministic across backends")
    check(CrabVoicechatPlugin.LOFI_GROUP_NAME.length == 16, "lofi group name exceeds Simple Voice Chat's plugin API limit")
  }

  private fun verifySpeechRoutingSelection() {
    val lofiId = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi")
    val lofiGroup = proxy(Group::class.java) { _, method, _ ->
      when (method.name) {
        "getId" -> lofiId
        else -> throw AssertionError("unexpected group call: " + method.name)
      }
    }
    val sender = proxy(VoicechatConnection::class.java) { _, method, _ ->
      when (method.name) {
        "getGroup" -> lofiGroup
        else -> throw AssertionError("unexpected connection call: " + method.name)
      }
    }
    check(GroupSpeechAttenuator.shouldAttenuate(SoundPacketEvent.SOURCE_GROUP, lofiId, sender),
      "lofi group speech was not selected for attenuation")
    check(!GroupSpeechAttenuator.shouldAttenuate(SoundPacketEvent.SOURCE_PROXIMITY, lofiId, sender),
      "proximity speech was incorrectly selected for attenuation")
    check(!GroupSpeechAttenuator.shouldAttenuate(SoundPacketEvent.SOURCE_GROUP, UUID.randomUUID(), sender),
      "another group was incorrectly selected for attenuation")
  }

  private fun verifyVolumeScaling() {
    val speech = shortArrayOf(10_000, -10_000, Short.MAX_VALUE, Short.MIN_VALUE)
    OpusVolumeScaler.applyGain(speech, 0.25)
    check(speech[0].toInt() == 2_500 && speech[1].toInt() == -2_500,
      "75% speech reduction did not produce a 25% signal")
    check(speech[2].toInt() == 8_192 && speech[3].toInt() == -8_192,
      "speech volume scaling changed endpoint rounding")
  }

  private fun verifyFailureRetryRate() {
    check(LofiStreamPlayer.RETRY_SECONDS >= 300L, "lofi resolution failures would retry often enough to flood the console")
  }

  private fun verifyLofiChannelTargets() {
    val lofiId = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi")
    val added = AtomicInteger()
    val removed = AtomicInteger()
    val cleared = AtomicInteger()
    val channel = proxy(StaticAudioChannel::class.java) { _, method, _ ->
      when (method.name) {
        "addTarget" -> added.incrementAndGet()
        "removeTarget" -> removed.incrementAndGet()
        "clearTargets" -> cleared.incrementAndGet()
        "setBypassGroupIsolation", "setCategory", "setFilter", "flush" -> {}
        "bypassesGroupIsolation" -> return@proxy true
        else -> throw AssertionError("unexpected channel call: " + method.name)
      }
      null
    }
    val api = proxy(VoicechatServerApi::class.java) { _, method, args ->
      if (method.name == "createStaticAudioChannel" && args.size == 1) channel
      else throw AssertionError("unexpected API call: " + method.name)
    }
    val playerId = UUID.randomUUID()
    val connection = connection(playerId, group(lofiId))
    val player = LofiStreamPlayer(api, lofiId, "https://example.invalid/live", 0.5F,
      Logger.getLogger("LofiVoicechatRegressionTest"))
    check(player.openChannel(), "lofi static channel was not opened")
    player.reconcileTarget(connection)
    check(added.get() == 1, "local lofi member was not registered as an audio target")
    player.updateTarget(connection, UUID.randomUUID())
    check(removed.get() == 1, "player leaving lofi was not removed as an audio target")
    player.updateTarget(connection, lofiId)
    player.removeTarget(playerId)
    check(added.get() == 2 && removed.get() == 2, "disconnect did not remove the tracked lofi audio target")
    player.close()
    check(cleared.get() == 1, "lofi targets were not cleared during shutdown")
  }

  private fun group(id: UUID): Group = proxy(Group::class.java) { _, method, _ ->
    if (method.name == "getId") id else throw AssertionError("unexpected group call: " + method.name)
  }

  private fun connection(playerId: UUID, group: Group): VoicechatConnection {
    val serverPlayer = proxy(ServerPlayer::class.java) { _, method, _ ->
      if (method.name == "getUuid") playerId else throw AssertionError("unexpected player call: " + method.name)
    }
    return proxy(VoicechatConnection::class.java) { _, method, _ ->
      when (method.name) {
        "getPlayer" -> serverPlayer
        "getGroup" -> group
        else -> throw AssertionError("unexpected connection call: " + method.name)
      }
    }
  }

  private fun verifyDefaultsAndLegacyNamespace() {
    val config = LofiVoicechatRegressionTest::class.java.classLoader.getResourceAsStream("modules/voicechat.yml").use { input ->
      check(input != null, "bundled modules/voicechat.yml is missing")
      String(input!!.readAllBytes(), StandardCharsets.UTF_8)
    }
    check(config.contains("https://www.youtube.com/watch?v=8TIVlFtcRDU"), "configured YouTube Live default changed")
    check(config.contains("music-volume: 0.5") && config.contains("player-volume: 0.25"), "lofi volume defaults changed")
    check(MediaItemCodec.compatibilityKeys()["remote"]!!.namespace == "customdiscs",
      "historical media-item namespace changed during the merge")
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

  private fun check(condition: Boolean, message: String) { if (!condition) throw AssertionError(message) }
}
