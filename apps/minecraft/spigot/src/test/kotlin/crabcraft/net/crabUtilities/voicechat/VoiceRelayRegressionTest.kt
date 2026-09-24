package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.media.VoiceMediaRegistry
import de.maxhenkel.voicechat.api.Group
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.UUID

object VoiceRelayRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val speaker = UUID.fromString("11111111-1111-1111-1111-111111111111")
        check(!AudioRelay.isRelayPayload(null), "null audio must not be relayed")
        check(AudioRelay.isRelayPayload(ByteArray(0)), "stop marker must be relayed")
        val route = "backend-b\u0000proxy:7"
        val stop = VoiceMessages.decodeAudioFrame(VoiceMessages.encodeAudioFrame(route, speaker, false, ByteArray(0)))
        check(stop.speaker == speaker, "stop-frame speaker changed")
        check(stop.opus.isEmpty(), "zero-length stop frame was not preserved")
        check(stop.route == route, "audio frame lost its hop token")
        check(VoiceMessages.routeBackend(stop.route) == "backend-b", "route backend decoding changed")
        val roster =
            VoiceMessages.decodeRosterJoin(
                VoiceMessages.encodeRosterJoin(UUID.randomUUID(), speaker, "Crabby", "survival")
            )
        check(roster?.name == "Crabby", "voice roster did not preserve a nickname display label")
        val safeName = CrabVoicechatPlugin.safeRosterName("Crab\u0000/\\:*?\"<>|\n", "Steve")
        check(
            safeName.none { it.code < 32 || it.code == 127 || it in "/\\:*?\"<>|" },
            "voice roster nickname retained a delimiter or unsafe filename character",
        )
        val cappedName = CrabVoicechatPlugin.safeRosterName("🦀".repeat(49), "Steve")
        check(
            cappedName.codePointCount(0, cappedName.length) == 48,
            "voice roster nickname exceeded the display-name cap",
        )
        verifyGroupDefinitionRoundTrip()
        verifyPasswordAccess()
        verifyMembershipJoinReplacesPreviousGroup()
        verifySequenceResetUsesNextSequence()
        verifyPrivateCallProtocol()
        verifyPrivateCallDefinition()
        verifyRingtoneFrames()
        verifyRingtoneResources()
    }

    private fun verifyGroupDefinitionRoundTrip() {
        for (type in listOf(Group.Type.NORMAL, Group.Type.OPEN, Group.Type.ISOLATED)) {
            val expected =
                VoiceMessages.GroupDefinition(
                    UUID.randomUUID(),
                    "Crab Group",
                    "secret\u0000password",
                    type,
                    true,
                    false,
                )
            val actual = VoiceMessages.decodeGroupDefinition(expected.id, VoiceMessages.encodeGroupDefinition(expected))
            check(actual == expected, "group definition changed during Redis round-trip")
            check(
                !VoiceMessages.encodeGroupChanged(expected.id).contains(expected.password!!),
                "group invalidation exposed its password",
            )
        }
    }

    private fun verifyPasswordAccess() {
        val groupId = UUID.randomUUID()
        try {
            check(
                GroupSynchronizer.passwordOf(TestGroup(groupId, "crab-secret")) == "crab-secret",
                "password compatibility bridge did not read the SVC backing group",
            )
            check(
                GroupSynchronizer.passwordOf(TestGroup(groupId, null)) == null,
                "password compatibility bridge invented an unprotected password",
            )
        } catch (e: ReflectiveOperationException) {
            throw AssertionError("password compatibility bridge failed", e)
        }
    }

    private fun verifyMembershipJoinReplacesPreviousGroup() {
        val membership = MembershipTracker()
        val playerId = UUID.randomUUID()
        val firstGroup = UUID.randomUUID()
        val secondGroup = UUID.randomUUID()
        membership.setLocalGroup(playerId, firstGroup)
        membership.setLocalGroup(playerId, secondGroup)
        check(
            membership.getLocalMembers(firstGroup).isEmpty(),
            "join without leave retained the player's previous group",
        )
        check(membership.getLocalGroupOf(playerId) == secondGroup, "player was not tracked in exactly the latest group")
    }

    private fun verifySequenceResetUsesNextSequence() {
        val channelId = UUID.randomUUID()
        try {
            val reset = AudioRelay.nextSequenceStop(staticPacket(channelId, 41L, byteArrayOf(1, 2, 3)))
            check(reset.sequenceNumber == 42L, "native stop did not use the next sequence")
            check(reset.opusEncodedData.isEmpty(), "native stop retained audio data")
            check(reset.channelId == channelId, "native stop changed the speaker channel")
        } catch (e: ReflectiveOperationException) {
            throw AssertionError("native stop packet could not be built", e)
        }
    }

    private fun verifyPrivateCallProtocol() {
        val groupId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val playerId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val token = "Abcdefghijklmnopqrstuvwx"
        val wire = VoiceMessages.encodeCallJoin(groupId, playerId, token)
        val join = VoiceMessages.decodeCallJoin(wire)
        check(
            join != null && join.groupId == groupId && join.playerId == playerId && join.generation == token,
            "password-free call join did not round-trip",
        )
        check(wire.split(VoiceMessages.SEP).size == 4, "call join unexpectedly carries another field")
        check(
            VoiceMessages.decodeCallJoin(wire + VoiceMessages.SEP + "secret") == null,
            "call join accepted a password field",
        )
        check(!wire.contains("random-secret"), "call join exposed a group password")
        check(
            VoiceMessages.decodeCallJoin(
                listOf(VoiceMessages.OP_CALL_JOIN, "0-0-0-0-0", playerId.toString(), token)
                    .joinToString(VoiceMessages.SEP)
            ) == null,
            "call join accepted a non-canonical UUID",
        )
        val target = VoiceMessages.CallTarget(groupId, token)
        check(
            VoiceMessages.decodeCallTarget(VoiceMessages.encodeCallTarget(target)) == target,
            "durable call target generation did not round-trip",
        )
        check(
            VoiceMessages.decodeCallTarget("$groupId\u0000short") == null,
            "call target accepted a weak generation token",
        )
        val oldTarget = VoiceMessages.CallTarget(groupId, "abcdefghijklmnopqrstuv")
        val newTarget = VoiceMessages.CallTarget(groupId, "zyxwvutsrqponmlkjihgfe")
        check(
            CallTargetSynchronizer.isNewAcceptedTarget(newTarget, newTarget, oldTarget),
            "a newly accepted generation did not supersede an old manual suppression",
        )
        check(
            !CallTargetSynchronizer.isNewAcceptedTarget(oldTarget, oldTarget, oldTarget),
            "a delayed old hint superseded its manual suppression",
        )
        check(
            CallTargetSynchronizer.manualSuppressionTarget(null, oldTarget) == oldTarget,
            "a pending call hint survived a manual group choice",
        )
        check(
            CallTargetSynchronizer.manualSuppressionTarget(oldTarget, newTarget) == newTarget,
            "manual suppression did not prefer the latest pending generation",
        )
        val start =
            VoiceMessages.decodeCallRingStart(
                VoiceMessages.encodeCallRingStart(token, playerId, VoiceMessages.RingDirection.INCOMING, 30_000L)
            )
        check(start?.expiresAtMillis == 30_000L, "ring start did not preserve its absolute deadline")
        check(
            VoiceMessages.decodeCallRingStart(
                listOf(VoiceMessages.OP_CALL_RING_START, token, playerId.toString(), "INCOMING", "030000")
                    .joinToString(VoiceMessages.SEP)
            ) == null,
            "ring start accepted a non-canonical deadline",
        )
        val stop =
            VoiceMessages.decodeCallRingStop(
                VoiceMessages.encodeCallRingStop(token, playerId, VoiceMessages.RingDirection.OUTGOING)
            )
        check(stop?.direction == VoiceMessages.RingDirection.OUTGOING, "ring stop did not preserve its direction")
    }

    private fun verifyPrivateCallDefinition() {
        val call =
            VoiceMessages.GroupDefinition(
                UUID.randomUUID(),
                CallTargetSynchronizer.CALL_GROUP_NAME,
                "random-secret",
                Group.Type.OPEN,
                true,
                false,
            )
        check(CallTargetSynchronizer.isCallGroup(call), "authoritative private call definition was rejected")
        check(!GroupSynchronizer.isHiddenLocally(call), "private call was hidden from the local group list")
        check(
            !CallTargetSynchronizer.isCallGroup(call.copy(type = Group.Type.NORMAL)),
            "non-OPEN group was accepted as a private call",
        )
        check(
            !CallTargetSynchronizer.isCallGroup(call.copy(hidden = false)),
            "visible registry group was accepted as a private call",
        )
        check(
            !CallTargetSynchronizer.isCallGroup(call.copy(password = null)),
            "passwordless group was accepted as a private call",
        )
        val ordinary =
            VoiceMessages.GroupDefinition(
                UUID.randomUUID(),
                "Secret Club",
                "another-secret",
                Group.Type.OPEN,
                true,
                false,
            )
        check(GroupSynchronizer.isHiddenLocally(ordinary), "ordinary hidden group became visible locally")
    }

    private fun verifyRingtoneFrames() {
        check(CallRingtonePlayer.RINGTONE_GAIN == 0.5, "ringtone baseline volume is not halved")
        var now = 1_000L
        val partial = CallRingtonePlayer.LoopingFrames(shortArrayOf(1, 2)) { now }
        partial.addToken("abcdefghijklmnopqrstuv", 1_010L)
        val finalFrame = partial.get()
        check(
            finalFrame != null && finalFrame.size == CallRingtonePlayer.FRAME_SAMPLES,
            "ringtone final frame had the wrong size",
        )
        check(
            finalFrame!![479] != 0.toShort() && finalFrame[480] == 0.toShort(),
            "ringtone did not silence the exact partial frame at its deadline",
        )
        now = 1_010L
        check(partial.get() == null, "ringtone continued at its absolute deadline")
        now = 2_000L
        val shared = CallRingtonePlayer.LoopingFrames(shortArrayOf(1)) { now }
        shared.addToken("abcdefghijklmnopqrstuv", 2_100L)
        shared.addToken("zyxwvutsrqponmlkjihgfe", 2_200L)
        check(!shared.removeToken("abcdefghijklmnopqrstuv"), "one invitation stopped another invitation's ringtone")
        check(shared.get() != null, "remaining invitation did not keep ringing")
        check(shared.removeToken("zyxwvutsrqponmlkjihgfe"), "last invitation did not stop its ringtone")
    }

    private fun verifyRingtoneResources() {
        check(VoiceMediaRegistry.CALL_CATEGORY == "crabcraft_calls", "call volume category changed")
        val resources = listOf("crabcraft/call/incoming_ringtone.pcm", "crabcraft/call/outgoing_ringtone.pcm")
        for (resource in resources) try {
            VoiceRelayRegressionTest::class.java.classLoader.getResourceAsStream(resource).use {
                check(it != null, "missing bundled ringtone $resource")
            }
        } catch (e: Exception) {
            throw AssertionError("could not read bundled ringtone $resource", e)
        }
        for (resource in resources) try {
            val clip = CallRingtonePlayer.loadClip(resource)
            check(clip.size > CallRingtonePlayer.FRAME_SAMPLES, "bundled ringtone is too short $resource")
            check(clip.any { it != 0.toShort() }, "bundled ringtone is silent $resource")
        } catch (e: Exception) {
            throw AssertionError("could not load bundled ringtone $resource", e)
        }
    }

    private fun staticPacket(channelId: UUID, sequence: Long, opus: ByteArray): StaticSoundPacket =
        proxy(StaticSoundPacket::class.java) { proxy, method, _ ->
            when (method.name) {
                "getChannelId",
                "getSender" -> channelId
                "getSequenceNumber" -> sequence
                "getOpusEncodedData" -> opus
                "getCategory" -> null
                "staticSoundPacketBuilder" -> TestStaticBuilder(channelId, sequence, opus)
                "toStaticSoundPacket" -> proxy
                else -> throw AssertionError("unexpected static packet call: ${method.name}")
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    class TestGroup(private val id: UUID, password: String?) : Group {
        private val group = BackingGroup(password)

        fun getGroup() = group

        override fun getName() = "Test"

        override fun hasPassword() = group.getPassword() != null

        override fun getId() = id

        override fun isPersistent() = false

        override fun isHidden() = false

        override fun getType() = Group.Type.NORMAL
    }

    class BackingGroup(private val password: String?) {
        fun getPassword() = password
    }

    private class TestStaticBuilder(
        private var channelId: UUID,
        private var sequenceNumber: Long,
        private var opus: ByteArray,
    ) : StaticSoundPacket.Builder<TestStaticBuilder> {
        override fun channelId(channelId: UUID) = apply { this.channelId = channelId }

        override fun opusEncodedData(opus: ByteArray) = apply { this.opus = opus }

        override fun category(category: String?) = this

        override fun build() = staticPacket(channelId, sequenceNumber, opus)
    }
}
