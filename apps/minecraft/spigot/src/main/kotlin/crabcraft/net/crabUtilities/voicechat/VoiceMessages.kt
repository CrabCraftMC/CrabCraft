package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.Group
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.UUID

/** Binary audio and NUL-separated lifecycle messages for the Redis voice bus. */
object VoiceMessages {
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

    @JvmStatic fun audioChannel(groupId: UUID) = AUDIO_CHANNEL_PREFIX + groupId

    @JvmStatic fun playerHomeKey(playerId: UUID) = PLAYER_HOME_KEY_PREFIX + playerId

    @JvmStatic fun playerGroupKey(playerId: UUID) = PLAYER_GROUP_KEY_PREFIX + playerId

    @JvmStatic fun callTargetKey(playerId: UUID) = CALL_TARGET_KEY_PREFIX + playerId

    @JvmStatic fun routeBackend(route: String?): String? = route?.substringBefore(SEP)

    /** uint16 route length, UTF-8 route, UUID, whisper flag, uint16 opus length, opus. */
    @JvmStatic
    fun encodeAudioFrame(route: String, speaker: UUID, whispering: Boolean, opus: ByteArray): ByteArray {
        val routeBytes = route.toByteArray(UTF_8)
        return ByteBuffer.allocate(2 + routeBytes.size + 16 + 1 + 2 + opus.size)
            .putShort(routeBytes.size.toShort())
            .put(routeBytes)
            .putLong(speaker.mostSignificantBits)
            .putLong(speaker.leastSignificantBits)
            .put(if (whispering) 1.toByte() else 0.toByte())
            .putShort(opus.size.toShort())
            .put(opus)
            .array()
    }

    @JvmStatic
    fun decodeAudioFrame(data: ByteArray): AudioFrame {
        val buf = ByteBuffer.wrap(data)
        val routeBytes = ByteArray(buf.short.toInt() and 0xFFFF).also(buf::get)
        val speaker = UUID(buf.long, buf.long)
        val whispering = buf.get() != 0.toByte()
        val opus = ByteArray(buf.short.toInt() and 0xFFFF).also(buf::get)
        return AudioFrame(String(routeBytes, UTF_8), speaker, whispering, opus)
    }

    data class AudioFrame(val route: String, val speaker: UUID, val whispering: Boolean, val opus: ByteArray) {
        fun route() = route

        fun speaker() = speaker

        fun whispering() = whispering

        fun opus() = opus
    }

    /** Passwords stay in the authoritative Redis hash; pub/sub only invalidates an ID. */
    @JvmStatic
    fun encodeGroupDefinition(group: GroupDefinition): String =
        listOf(
                encodeText(group.name),
                if (group.password == null) "0" else "1",
                group.password?.let(::encodeText) ?: "",
                typeToString(group.type),
                if (group.hidden) "1" else "0",
                if (group.permanent) "1" else "0",
            )
            .joinToString(SEP)

    @JvmStatic
    fun decodeGroupDefinition(id: UUID, encoded: String?): GroupDefinition? {
        val parts = encoded?.split(SEP) ?: return null
        if (parts.size != 6) return null
        return try {
            GroupDefinition(
                id,
                decodeText(parts[0]),
                if (parts[1] == "1") decodeText(parts[2]) else null,
                typeFromString(parts[3]),
                parts[4] == "1",
                parts[5] == "1",
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @JvmStatic fun encodeGroupChanged(groupId: UUID) = listOf(OP_GROUP_CHANGED, groupId.toString()).joinToString(SEP)

    @JvmStatic
    fun decodeGroupChanged(message: String): UUID? {
        val parts = message.split(SEP)
        if (parts.size != 2 || parts[0] != OP_GROUP_CHANGED) return null
        return try {
            UUID.fromString(parts[1])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    @JvmStatic
    fun typeToString(type: Group.Type): String =
        when (type) {
            Group.Type.NORMAL -> "NORMAL"
            Group.Type.OPEN -> "OPEN"
            Group.Type.ISOLATED -> "ISOLATED"
            else -> throw IllegalArgumentException("Unknown group type")
        }

    @JvmStatic
    fun typeFromString(value: String): Group.Type =
        when (value) {
            "NORMAL" -> Group.Type.NORMAL
            "OPEN" -> Group.Type.OPEN
            "ISOLATED" -> Group.Type.ISOLATED
            else -> throw IllegalArgumentException("Unknown group type")
        }

    private fun encodeText(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(UTF_8))

    private fun decodeText(value: String) = String(Base64.getDecoder().decode(value), UTF_8)

    data class GroupDefinition(
        val id: UUID,
        val name: String,
        val password: String?,
        val type: Group.Type,
        val hidden: Boolean,
        val permanent: Boolean,
    ) {
        fun id() = id

        fun name() = name

        fun password() = password

        fun type() = type

        fun hidden() = hidden

        fun permanent() = permanent
    }

    @JvmStatic
    fun encodeRosterJoin(groupId: UUID, playerId: UUID, name: String?, route: String) =
        listOf(OP_ROSTER_JOIN, groupId.toString(), playerId.toString(), name ?: "", route).joinToString(SEP)

    @JvmStatic
    fun decodeRosterJoin(message: String): RosterJoin? {
        val parts = message.split(SEP, limit = 5)
        if (parts.size != 5 || parts[0] != OP_ROSTER_JOIN) return null
        return try {
            RosterJoin(UUID.fromString(parts[1]), UUID.fromString(parts[2]), parts[3], parts[4])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    data class RosterJoin(val groupId: UUID, val playerId: UUID, val name: String, val route: String) {
        fun groupId() = groupId

        fun playerId() = playerId

        fun name() = name

        fun route() = route

        fun backend() = routeBackend(route)!!
    }

    @JvmStatic
    fun encodeRosterLeave(groupId: UUID, playerId: UUID, route: String) =
        listOf(OP_ROSTER_LEAVE, groupId.toString(), playerId.toString(), route).joinToString(SEP)

    @JvmStatic
    fun decodeRosterLeave(message: String): RosterLeave? {
        val parts = message.split(SEP, limit = 4)
        if (parts.size != 4 || parts[0] != OP_ROSTER_LEAVE) return null
        return try {
            RosterLeave(UUID.fromString(parts[1]), UUID.fromString(parts[2]), parts[3])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    data class RosterLeave(val groupId: UUID, val playerId: UUID, val route: String) {
        fun groupId() = groupId

        fun playerId() = playerId

        fun route() = route

        fun backend() = routeBackend(route)!!
    }

    /** Password-free wake-up hint; the target and group secret are read from Redis. */
    @JvmStatic
    fun encodeCallJoin(groupId: UUID, playerId: UUID, generation: String): String {
        requireOpaqueToken(generation)
        return listOf(OP_CALL_JOIN, groupId.toString(), playerId.toString(), generation).joinToString(SEP)
    }

    @JvmStatic
    fun decodeCallJoin(message: String): CallJoin? {
        val parts = message.split(SEP)
        if (parts.size != 4 || parts[0] != OP_CALL_JOIN || !isOpaqueToken(parts[3])) return null
        return try {
            CallJoin(parseCanonicalUuid(parts[1]), parseCanonicalUuid(parts[2]), parts[3])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    data class CallJoin(val groupId: UUID, val playerId: UUID, val generation: String) {
        fun groupId() = groupId

        fun playerId() = playerId

        fun generation() = generation

        fun target() = CallTarget(groupId, generation)
    }

    @JvmStatic
    fun encodeCallTarget(target: CallTarget): String {
        requireOpaqueToken(target.generation)
        return listOf(target.groupId.toString(), target.generation).joinToString(SEP)
    }

    @JvmStatic
    fun decodeCallTarget(encoded: String?): CallTarget? {
        val parts = encoded?.split(SEP) ?: return null
        if (parts.size != 2 || !isOpaqueToken(parts[1])) return null
        return try {
            CallTarget(parseCanonicalUuid(parts[0]), parts[1])
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    data class CallTarget(val groupId: UUID, val generation: String) {
        fun groupId() = groupId

        fun generation() = generation
    }

    @JvmStatic
    fun encodeCallRingStart(token: String?, playerId: UUID?, direction: RingDirection?, expiresAtMillis: Long): String {
        requireOpaqueToken(token)
        require(playerId != null && direction != null && expiresAtMillis > 0) { "Invalid call ringtone start" }
        return listOf(OP_CALL_RING_START, token, playerId.toString(), direction.name, expiresAtMillis.toString())
            .joinToString(SEP)
    }

    @JvmStatic
    fun decodeCallRingStart(message: String): CallRingStart? {
        val parts = message.split(SEP)
        if (parts.size != 5 || parts[0] != OP_CALL_RING_START || !isOpaqueToken(parts[1])) return null
        return try {
            CallRingStart(
                parts[1],
                parseCanonicalUuid(parts[2]),
                RingDirection.valueOf(parts[3]),
                parseCanonicalPositiveLong(parts[4]),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    data class CallRingStart(
        val token: String,
        val playerId: UUID,
        val direction: RingDirection,
        val expiresAtMillis: Long,
    ) {
        fun token() = token

        fun playerId() = playerId

        fun direction() = direction

        fun expiresAtMillis() = expiresAtMillis
    }

    @JvmStatic
    fun encodeCallRingStop(token: String?, playerId: UUID?, direction: RingDirection?): String {
        requireOpaqueToken(token)
        require(playerId != null && direction != null) { "Invalid call ringtone stop" }
        return listOf(OP_CALL_RING_STOP, token, playerId.toString(), direction.name).joinToString(SEP)
    }

    @JvmStatic
    fun decodeCallRingStop(message: String): CallRingStop? {
        val parts = message.split(SEP)
        if (parts.size != 4 || parts[0] != OP_CALL_RING_STOP || !isOpaqueToken(parts[1])) return null
        return try {
            CallRingStop(parts[1], parseCanonicalUuid(parts[2]), RingDirection.valueOf(parts[3]))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    data class CallRingStop(val token: String, val playerId: UUID, val direction: RingDirection) {
        fun token() = token

        fun playerId() = playerId

        fun direction() = direction
    }

    enum class RingDirection {
        INCOMING,
        OUTGOING,
    }

    private fun requireOpaqueToken(token: String?) {
        require(isOpaqueToken(token)) { "Invalid call token" }
    }

    private fun isOpaqueToken(token: String?): Boolean =
        token != null &&
            token.length in 22..64 &&
            token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    private fun parseCanonicalUuid(value: String): UUID =
        UUID.fromString(value).also {
            require(it.toString() == value) { "Non-canonical UUID" }
        }

    private fun parseCanonicalPositiveLong(value: String): Long =
        value.toLong().also {
            require(it > 0 && it.toString() == value) { "Non-canonical positive long" }
        }
}
