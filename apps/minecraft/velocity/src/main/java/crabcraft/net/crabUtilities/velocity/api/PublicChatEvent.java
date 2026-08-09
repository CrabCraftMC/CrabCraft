package crabcraft.net.crabUtilities.velocity.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A public chat message read from the CrabCraft Redis Stream. */
record PublicChatEvent(
        String id,
        long timestamp,
        String uuid,
        String username,
        String message
) {

    private static final Gson GSON = new Gson();
    private static final Pattern STREAM_ID = Pattern.compile("^[0-9]+-[0-9]+$");

    PublicChatEvent {
        id = requireText(id, "id");
        uuid = requireText(uuid, "uuid");
        username = requireText(username, "username");
        message = Objects.requireNonNull(message, "message");
        if (!isStreamId(id)) {
            throw new IllegalArgumentException("id must be a Redis Stream ID");
        }
        if (timestamp < 0L) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
    }

    /**
     * Converts a Redis entry using its ID for both the event ID and Unix epoch
     * millisecond timestamp.
     */
    static PublicChatEvent fromStreamEntry(StreamEntry entry) {
        Objects.requireNonNull(entry, "entry");
        StreamEntryID streamId = Objects.requireNonNull(entry.getID(), "entry.id");
        Map<String, String> fields = Objects.requireNonNull(entry.getFields(), "entry.fields");
        return new PublicChatEvent(
                streamId.toString(),
                streamId.getTime(),
                fields.get("uuid"),
                fields.get("username"),
                fields.get("message"));
    }

    private JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", timestamp);
        json.addProperty("uuid", uuid);
        json.addProperty("username", username);
        json.addProperty("message", message);
        return json;
    }

    String toJson() {
        return GSON.toJson(toJsonObject());
    }

    /** Returns one complete SSE message, including the terminating blank line. */
    String toSseFrame() {
        return "id: " + id + '\n'
                + "data: " + toJson() + "\n\n";
    }

    byte[] toSseBytes() {
        return toSseFrame().getBytes(StandardCharsets.UTF_8);
    }

    static boolean isStreamId(String value) {
        if (value == null || !STREAM_ID.matcher(value).matches()) return false;
        try {
            StreamEntryID id = new StreamEntryID(value);
            return id.getTime() >= 0L && id.getSequence() >= 0L;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static int compareIds(String left, String right) {
        return new StreamEntryID(left).compareTo(new StreamEntryID(right));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
