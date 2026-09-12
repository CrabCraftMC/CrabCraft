package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.Group
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** Binary audio frames and NUL-separated lifecycle messages for the Redis voice bus. */
class VoiceMessages private constructor() {
    data class AudioFrame(private val route: String, private val speaker: UUID, private val whispering: Boolean, private val opus: ByteArray) {
        fun route(): String = route
        fun speaker(): UUID = speaker
        fun whispering(): Boolean = whispering
        fun opus(): ByteArray = opus
    }
    data class GroupDefinition(private val id: UUID, private val name: String, private val password: String?, private val type: Group.Type, private val hidden: Boolean, private val permanent: Boolean) {
        fun id(): UUID = id
        fun name(): String = name
        fun password(): String? = password
        fun type(): Group.Type = type
        fun hidden(): Boolean = hidden
        fun permanent(): Boolean = permanent
    }
    data class RosterJoin(private val groupId: UUID, private val playerId: UUID, private val name: String, private val route: String) {
        fun groupId(): UUID = groupId
        fun playerId(): UUID = playerId
        fun name(): String = name
        fun route(): String = route
        fun backend(): String = routeBackend(route)!!
    }
    data class RosterLeave(private val groupId: UUID, private val playerId: UUID, private val route: String) {
        fun groupId(): UUID = groupId
        fun playerId(): UUID = playerId
        fun route(): String = route
        fun backend(): String = routeBackend(route)!!
    }
    data class CallJoin(private val groupId: UUID, private val playerId: UUID, private val generation: String) {
        fun groupId(): UUID = groupId
        fun playerId(): UUID = playerId
        fun generation(): String = generation
        fun target(): CallTarget = CallTarget(groupId, generation)
    }
    data class CallTarget(private val groupId: UUID, private val generation: String) {
        fun groupId(): UUID = groupId
        fun generation(): String = generation
    }
    data class CallRingStart(private val token: String, private val playerId: UUID, private val direction: RingDirection, private val expiresAtMillis: Long) {
        fun token(): String = token
        fun playerId(): UUID = playerId
        fun direction(): RingDirection = direction
        fun expiresAtMillis(): Long = expiresAtMillis
    }
    data class CallRingStop(private val token: String, private val playerId: UUID, private val direction: RingDirection) {
        fun token(): String = token
        fun playerId(): UUID = playerId
        fun direction(): RingDirection = direction
    }
    enum class RingDirection { INCOMING, OUTGOING }

    companion object {
        const val AUDIO_CHANNEL_PREFIX = "crabcraft:svc:audio:"
        const val PLAYER_HOME_KEY_PREFIX = "crabcraft:svc:player-home:"
        const val PLAYER_GROUP_KEY_PREFIX = "crabcraft:svc:player-group:"
        const val CALL_TARGET_KEY_PREFIX = "crabcraft:svc:call-target:"
        const val GROUP_MEMBERS_KEY_PREFIX = "crabcraft:svc:group-members:"
        const val GROUPS_REGISTRY_KEY = "crabcraft:svc:groups"
        const val PERMANENT_GROUPS_KEY = "crabcraft:svc:groups:permanent"
        const val ROSTER_CHANNEL = "crabcraft:svc:roster"
        const val LIFECYCLE_CHANNEL = "crabcraft:svc:lifecycle"
        const val SEP = "\u0000"
        const val OP_GROUP_CHANGED = "GROUP_CHANGED"
        const val OP_ROSTER_JOIN = "ROSTER_JOIN"
        const val OP_ROSTER_LEAVE = "ROSTER_LEAVE"
        const val OP_CALL_JOIN = "CALL_JOIN"
        const val OP_CALL_RING_START = "CALL_RING_START"
        const val OP_CALL_RING_STOP = "CALL_RING_STOP"
        @JvmStatic fun audioChannel(groupId: UUID): String = AUDIO_CHANNEL_PREFIX + groupId
        @JvmStatic fun playerHomeKey(playerId: UUID): String = PLAYER_HOME_KEY_PREFIX + playerId
        @JvmStatic fun playerGroupKey(playerId: UUID): String = PLAYER_GROUP_KEY_PREFIX + playerId
        @JvmStatic fun callTargetKey(playerId: UUID): String = CALL_TARGET_KEY_PREFIX + playerId
        @JvmStatic fun routeBackend(route: String?): String? {
            if (route == null) return null
            val separator = route.indexOf(SEP)
            return if (separator < 0) route else route.substring(0, separator)
        }

        /** uint16 route length, route UTF-8, UUID, whisper byte, uint16 Opus length, Opus. */
        @JvmStatic fun encodeAudioFrame(route: String, speaker: UUID, whispering: Boolean, opus: ByteArray): ByteArray {
            val routeBytes = route.toByteArray(StandardCharsets.UTF_8)
            val buf = ByteBuffer.allocate(2 + routeBytes.size + 16 + 1 + 2 + opus.size)
            buf.putShort(routeBytes.size.toShort())
            buf.put(routeBytes)
            buf.putLong(speaker.mostSignificantBits)
            buf.putLong(speaker.leastSignificantBits)
            buf.put((if (whispering) 1 else 0).toByte())
            buf.putShort(opus.size.toShort())
            buf.put(opus)
            return buf.array()
        }
        @JvmStatic fun decodeAudioFrame(data: ByteArray): AudioFrame {
            val buf = ByteBuffer.wrap(data)
            val routeLength = buf.getShort().toInt() and 0xFFFF
            val routeBytes = ByteArray(routeLength)
            buf.get(routeBytes)
            val route = String(routeBytes, StandardCharsets.UTF_8)
            val speaker = UUID(buf.getLong(), buf.getLong())
            val whispering = buf.get().toInt() != 0
            val opusLen = buf.getShort().toInt() and 0xFFFF
            val opus = ByteArray(opusLen)
            buf.get(opus)
            return AudioFrame(route, speaker, whispering, opus)
        }

        /** Passwords stay in the authoritative hash; pub/sub only invalidates group IDs. */
        @JvmStatic fun encodeGroupDefinition(group: GroupDefinition): String = listOf(encodeText(group.name()), if (group.password() == null) "0" else "1", group.password()?.let(::encodeText) ?: "", typeToString(group.type()), if (group.hidden()) "1" else "0", if (group.permanent()) "1" else "0").joinToString(SEP)
        @JvmStatic fun decodeGroupDefinition(id: UUID, encoded: String?): GroupDefinition? {
            if (encoded == null) return null
            val parts = encoded.split(SEP)
            if (parts.size != 6) return null
            return try {
                val password = if (parts[1] == "1") decodeText(parts[2]) else null
                GroupDefinition(id, decodeText(parts[0]), password, typeFromString(parts[3]), parts[4] == "1", parts[5] == "1")
            } catch (e: IllegalArgumentException) { null }
        }
        @JvmStatic fun encodeGroupChanged(groupId: UUID): String = listOf(OP_GROUP_CHANGED, groupId.toString()).joinToString(SEP)
        @JvmStatic fun decodeGroupChanged(message: String): UUID? {
            val parts = message.split(SEP)
            if (parts.size != 2 || OP_GROUP_CHANGED != parts[0]) return null
            return try { UUID.fromString(parts[1]) } catch (e: IllegalArgumentException) { null }
        }
        @JvmStatic fun typeToString(type: Group.Type): String {
            if (type == Group.Type.NORMAL) return "NORMAL"
            if (type == Group.Type.OPEN) return "OPEN"
            if (type == Group.Type.ISOLATED) return "ISOLATED"
            throw IllegalArgumentException("Unknown group type")
        }
        @JvmStatic fun typeFromString(value: String): Group.Type = when (value) {
            "OPEN" -> Group.Type.OPEN
            "ISOLATED" -> Group.Type.ISOLATED
            "NORMAL" -> Group.Type.NORMAL
            else -> throw IllegalArgumentException("Unknown group type")
        }
        private fun encodeText(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        private fun decodeText(value: String): String = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

        @JvmStatic fun encodeRosterJoin(groupId: UUID, playerId: UUID, name: String?, route: String): String = listOf(OP_ROSTER_JOIN, groupId.toString(), playerId.toString(), name ?: "", route).joinToString(SEP)
        @JvmStatic fun decodeRosterJoin(message: String): RosterJoin? {
            val parts = message.split(SEP, limit = 5)
            if (parts.size != 5 || OP_ROSTER_JOIN != parts[0]) return null
            return try { RosterJoin(UUID.fromString(parts[1]), UUID.fromString(parts[2]), parts[3], parts[4]) } catch (e: IllegalArgumentException) { null }
        }
        @JvmStatic fun encodeRosterLeave(groupId: UUID, playerId: UUID, route: String): String = listOf(OP_ROSTER_LEAVE, groupId.toString(), playerId.toString(), route).joinToString(SEP)
        @JvmStatic fun decodeRosterLeave(message: String): RosterLeave? {
            val parts = message.split(SEP, limit = 4)
            if (parts.size != 4 || OP_ROSTER_LEAVE != parts[0]) return null
            return try { RosterLeave(UUID.fromString(parts[1]), UUID.fromString(parts[2]), parts[3]) } catch (e: IllegalArgumentException) { null }
        }
        /** Password-free wake-up hint; Redis carries the target and group secret. */
        @JvmStatic fun encodeCallJoin(groupId: UUID, playerId: UUID, generation: String): String {
            requireOpaqueToken(generation)
            return listOf(OP_CALL_JOIN, groupId.toString(), playerId.toString(), generation).joinToString(SEP)
        }
        @JvmStatic fun decodeCallJoin(message: String): CallJoin? {
            val parts = message.split(SEP)
            if (parts.size != 4 || OP_CALL_JOIN != parts[0] || !isOpaqueToken(parts[3])) return null
            return try { CallJoin(parseCanonicalUuid(parts[1]), parseCanonicalUuid(parts[2]), parts[3]) } catch (e: IllegalArgumentException) { null }
        }
        @JvmStatic fun encodeCallTarget(target: CallTarget): String {
            requireOpaqueToken(target.generation())
            return listOf(target.groupId().toString(), target.generation()).joinToString(SEP)
        }
        @JvmStatic fun decodeCallTarget(encoded: String?): CallTarget? {
            if (encoded == null) return null
            val parts = encoded.split(SEP)
            if (parts.size != 2 || !isOpaqueToken(parts[1])) return null
            return try { CallTarget(parseCanonicalUuid(parts[0]), parts[1]) } catch (e: IllegalArgumentException) { null }
        }
        @JvmStatic fun encodeCallRingStart(token: String, playerId: UUID?, direction: RingDirection?, expiresAtMillis: Long): String {
            requireOpaqueToken(token)
            if (playerId == null || direction == null || expiresAtMillis <= 0L) throw IllegalArgumentException("Invalid call ringtone start")
            return listOf(OP_CALL_RING_START, token, playerId.toString(), direction.name, expiresAtMillis.toString()).joinToString(SEP)
        }
        @JvmStatic fun decodeCallRingStart(message: String): CallRingStart? {
            val parts = message.split(SEP)
            if (parts.size != 5 || OP_CALL_RING_START != parts[0] || !isOpaqueToken(parts[1])) return null
            return try { CallRingStart(parts[1], parseCanonicalUuid(parts[2]), RingDirection.valueOf(parts[3]), parseCanonicalPositiveLong(parts[4])) } catch (e: IllegalArgumentException) { null }
        }
        @JvmStatic fun encodeCallRingStop(token: String, playerId: UUID?, direction: RingDirection?): String {
            requireOpaqueToken(token)
            if (playerId == null || direction == null) throw IllegalArgumentException("Invalid call ringtone stop")
            return listOf(OP_CALL_RING_STOP, token, playerId.toString(), direction.name).joinToString(SEP)
        }
        @JvmStatic fun decodeCallRingStop(message: String): CallRingStop? {
            val parts = message.split(SEP)
            if (parts.size != 4 || OP_CALL_RING_STOP != parts[0] || !isOpaqueToken(parts[1])) return null
            return try { CallRingStop(parts[1], parseCanonicalUuid(parts[2]), RingDirection.valueOf(parts[3])) } catch (e: IllegalArgumentException) { null }
        }
        private fun requireOpaqueToken(token: String?) { if (!isOpaqueToken(token)) throw IllegalArgumentException("Invalid call token") }
        private fun isOpaqueToken(token: String?): Boolean {
            if (token == null || token.length < 22 || token.length > 64) return false
            for (character in token) if (character !in 'a'..'z' && character !in 'A'..'Z' && character !in '0'..'9' && character != '-' && character != '_') return false
            return true
        }
        private fun parseCanonicalUuid(value: String): UUID {
            val parsed = UUID.fromString(value)
            if (parsed.toString() != value) throw IllegalArgumentException("Non-canonical UUID")
            return parsed
        }
        private fun parseCanonicalPositiveLong(value: String): Long {
            val parsed = value.toLong()
            if (parsed <= 0L || parsed.toString() != value) throw IllegalArgumentException("Non-canonical positive long")
            return parsed
        }
    }
}
