package crabcraft.net.crabUtilities.velocity.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.resps.StreamEntry

/** A public chat message read from the CrabCraft Redis Stream. */
data class PublicChatEvent(
    private val id: String,
    private val timestamp: Long,
    private val uuid: String,
    private val username: String,
    private val message: String,
) {
    init {
        require(id.any { !Character.isWhitespace(it) }) { "id must not be blank" }
        require(uuid.any { !Character.isWhitespace(it) }) { "uuid must not be blank" }
        require(username.any { !Character.isWhitespace(it) }) { "username must not be blank" }
        require(isStreamId(id)) { "id must be a Redis Stream ID" }
        require(timestamp >= 0L) { "timestamp must not be negative" }
    }

    fun id() = id

    fun timestamp() = timestamp

    fun uuid() = uuid

    fun username() = username

    fun message() = message

    private fun toJsonObject() =
        JsonObject().apply {
            addProperty("timestamp", timestamp)
            addProperty("uuid", uuid)
            addProperty("username", username)
            addProperty("message", message)
        }

    fun toJson(): String = GSON.toJson(toJsonObject())

    /** Returns one complete SSE message, including the terminating blank line. */
    fun toSseFrame() = "id: $id\ndata: ${toJson()}\n\n"

    fun toSseBytes(): ByteArray = toSseFrame().toByteArray(StandardCharsets.UTF_8)

    companion object {
        private val GSON = Gson()
        private val STREAM_ID = Pattern.compile("^[0-9]+-[0-9]+$")

        /** Uses the stream ID for both the event ID and Unix epoch millisecond timestamp. */
        @JvmStatic
        fun fromStreamEntry(entry: StreamEntry): PublicChatEvent {
            val streamId = java.util.Objects.requireNonNull(entry.id, "entry.id")
            val fields = java.util.Objects.requireNonNull(entry.fields, "entry.fields")
            return PublicChatEvent(
                streamId.toString(),
                streamId.time,
                java.util.Objects.requireNonNull(fields["uuid"], "uuid")!!,
                java.util.Objects.requireNonNull(fields["username"], "username")!!,
                java.util.Objects.requireNonNull(fields["message"], "message")!!,
            )
        }

        @JvmStatic
        fun isStreamId(value: String?): Boolean {
            if (value == null || !STREAM_ID.matcher(value).matches()) return false
            return try {
                val id = StreamEntryID(value)
                id.time >= 0L && id.sequence >= 0L
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        @JvmStatic
        fun compareIds(left: String, right: String): Int = StreamEntryID(left).compareTo(StreamEntryID(right))
    }
}
