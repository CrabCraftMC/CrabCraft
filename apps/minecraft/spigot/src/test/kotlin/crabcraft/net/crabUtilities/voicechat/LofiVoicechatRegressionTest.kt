package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.ServerPlayer
import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import de.maxhenkel.voicechat.api.events.SoundPacketEvent
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

object LofiVoicechatRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        verifyGroupSelection()
        verifySpeechRoutingSelection()
        verifyVolumeScaling()
        verifyLofiChannelTargets()
        check(
            LofiStreamPlayer.RETRY_SECONDS >= 300L,
            "lofi resolution failures would retry often enough to flood the console",
        )
    }

    private fun verifyGroupSelection() {
        val enabled = CrabVoicechatPlugin.persistentGroupNames(listOf("Global #1", "Global #2", "Global #3"), true)
        check(
            enabled == listOf("Global #1", "Global #2", "Global #3", CrabVoicechatPlugin.LOFI_GROUP_NAME),
            "enabled lofi group was not added after the three global groups",
        )
        check(enabled.sorted() == enabled, "Simple Voice Chat's alphabetical ordering would not place lofi fourth")
        val disabled =
            CrabVoicechatPlugin.persistentGroupNames(
                listOf("Global #1", "24/7 Lofi", CrabVoicechatPlugin.LOFI_GROUP_NAME),
                false,
            )
        check(disabled == listOf("Global #1"), "disabled lofi group was still created")
        check(
            CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi") ==
                CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi"),
            "lofi group ID was not deterministic across backends",
        )
        check(
            CrabVoicechatPlugin.LOFI_GROUP_NAME.length == 16,
            "lofi group name exceeds Simple Voice Chat's plugin API limit",
        )
    }

    private fun verifySpeechRoutingSelection() {
        val lofiId = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi")
        val lofiGroup = group(lofiId)
        val sender =
            proxy(VoicechatConnection::class.java) { _, method, _ ->
                if (method.name == "getGroup") lofiGroup
                else throw AssertionError("unexpected connection call: ${method.name}")
            }
        check(
            GroupSpeechAttenuator.shouldAttenuate(SoundPacketEvent.SOURCE_GROUP, lofiId, sender),
            "lofi group speech was not selected for attenuation",
        )
        check(
            !GroupSpeechAttenuator.shouldAttenuate(SoundPacketEvent.SOURCE_PROXIMITY, lofiId, sender),
            "proximity speech was incorrectly selected for attenuation",
        )
        check(
            !GroupSpeechAttenuator.shouldAttenuate(SoundPacketEvent.SOURCE_GROUP, UUID.randomUUID(), sender),
            "another group was incorrectly selected for attenuation",
        )
    }

    private fun verifyVolumeScaling() {
        val speech = shortArrayOf(10_000, -10_000, Short.MAX_VALUE, Short.MIN_VALUE)
        OpusVolumeScaler.applyGain(speech, 0.25)
        check(
            speech[0] == 2_500.toShort() && speech[1] == (-2_500).toShort(),
            "75% speech reduction did not produce a 25% signal",
        )
        check(
            speech[2] == 8_192.toShort() && speech[3] == (-8_192).toShort(),
            "speech volume scaling changed endpoint rounding",
        )
    }

    private fun verifyLofiChannelTargets() {
        val lofiId = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi")
        val added = AtomicInteger()
        val removed = AtomicInteger()
        val cleared = AtomicInteger()
        val channel =
            proxy(StaticAudioChannel::class.java) { _, method, _ ->
                when (method.name) {
                    "addTarget" -> {
                        added.incrementAndGet()
                        null
                    }
                    "removeTarget" -> {
                        removed.incrementAndGet()
                        null
                    }
                    "clearTargets" -> {
                        cleared.incrementAndGet()
                        null
                    }
                    "setBypassGroupIsolation",
                    "setCategory",
                    "setFilter",
                    "flush" -> null
                    "bypassesGroupIsolation" -> true
                    else -> throw AssertionError("unexpected channel call: ${method.name}")
                }
            }
        val api =
            proxy(VoicechatServerApi::class.java) { _, method, args ->
                if (method.name == "createStaticAudioChannel" && args!!.size == 1) channel
                else throw AssertionError("unexpected API call: ${method.name}")
            }
        val playerId = UUID.randomUUID()
        val connection = connection(playerId, group(lofiId))
        val player =
            LofiStreamPlayer(
                api,
                lofiId,
                "https://example.invalid/live",
                0.5F,
                Logger.getLogger("LofiVoicechatRegressionTest"),
            )
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

    private fun group(id: UUID) =
        proxy(Group::class.java) { _, method, _ ->
            if (method.name == "getId") id else throw AssertionError("unexpected group call: ${method.name}")
        }

    private fun connection(playerId: UUID, group: Group): VoicechatConnection {
        val player =
            proxy(ServerPlayer::class.java) { _, method, _ ->
                if (method.name == "getUuid") playerId
                else throw AssertionError("unexpected player call: ${method.name}")
            }
        return proxy(VoicechatConnection::class.java) { _, method, _ ->
            when (method.name) {
                "getPlayer" -> player
                "getGroup" -> group
                else -> throw AssertionError("unexpected connection call: ${method.name}")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
