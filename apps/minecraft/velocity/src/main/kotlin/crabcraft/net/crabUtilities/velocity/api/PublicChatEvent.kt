package crabcraft.net.crabUtilities.velocity.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.resps.StreamEntry
import java.nio.charset.StandardCharsets
import java.util.Objects
import java.util.regex.Pattern

/** A public chat message read from the CrabCraft Redis Stream. */
data class PublicChatEvent(
    val id: String?,
    val timestamp: Long,
    val uuid: String?,
    val username: String?,
    val message: String?
) {
    init {
        requireText(id, "id")
        requireText(uuid, "uuid")
        requireText(username, "username")
        Objects.requireNonNull(message, "message")
        if (!isStreamId(id)) throw IllegalArgumentException("id must be a Redis Stream ID")
        if (timestamp < 0L) throw IllegalArgumentException("timestamp must not be negative")
    }

    fun id(): String = id!!
    fun timestamp(): Long = timestamp
    fun uuid(): String = uuid!!
    fun username(): String = username!!
    fun message(): String = message!!

    private fun toJsonObject(): JsonObject {
        val json = JsonObject()
        json.addProperty("timestamp", timestamp)
        json.addProperty("uuid", uuid)
        json.addProperty("username", username)
        json.addProperty("message", message)
        return json
    }

    fun toJson(): String = GSON.toJson(toJsonObject())

    /** Returns one complete SSE message, including the terminating blank line. */
    fun toSseFrame(): String = "id: " + id + '\n' + "data: " + toJson() + "\n\n"

    fun toSseBytes(): ByteArray = toSseFrame().toByteArray(StandardCharsets.UTF_8)

    companion object {
        private val GSON = Gson()
        private val STREAM_ID = Pattern.compile("^[0-9]+-[0-9]+$")

        /** Converts a Redis entry using its ID for the event ID and epoch millisecond timestamp. */
        @JvmStatic fun fromStreamEntry(entry: StreamEntry): PublicChatEvent {
            Objects.requireNonNull(entry, "entry")
            val streamId = Objects.requireNonNull(entry.id, "entry.id")
            val fields = Objects.requireNonNull(entry.fields, "entry.fields")
            return PublicChatEvent(streamId.toString(), streamId.time,
                fields["uuid"], fields["username"], fields["message"])
        }

        @JvmStatic fun isStreamId(value: String?): Boolean {
            if (value == null || !STREAM_ID.matcher(value).matches()) return false
            try {
                val id = StreamEntryID(value)
                return id.time >= 0L && id.sequence >= 0L
            } catch (e: IllegalArgumentException) {
                return false
            }
        }

        @JvmStatic fun compareIds(left: String, right: String): Int =
            StreamEntryID(left).compareTo(StreamEntryID(right))

        private fun requireText(value: String?, name: String): String {
            Objects.requireNonNull(value, name)
            if (value!!.codePoints().allMatch(Character::isWhitespace)) throw IllegalArgumentException(name + " must not be blank")
            return value
        }
    }
}
