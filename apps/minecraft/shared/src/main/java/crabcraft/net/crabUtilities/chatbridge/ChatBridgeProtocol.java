package crabcraft.net.crabUtilities.chatbridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Wire format shared by the Paper and Velocity CrabUtilities plugins.
 */
public final class ChatBridgeProtocol {

    public static final String CHANNEL = "crabcraft:chat_bridge";
    private static final int MAX_PAYLOAD_BYTES = 30_000;
    private static final int MAX_STRING_BYTES = 28_000;

    private ChatBridgeProtocol() {}

    public enum Type {
        PRIVATE_REQUEST(1),
        REPLY_REQUEST(2),
        STAFF_REQUEST(3),
        DELIVERY(4),
        STAFF_STATE(5);

        private final int id;

        Type(int id) {
            this.id = id;
        }

        private static Type fromId(int id) {
            for (Type type : values()) {
                if (type.id == id) return type;
            }
            throw new IllegalArgumentException("Unknown chat bridge packet type: " + id);
        }
    }

    public record Packet(Type type, UUID playerId, String target, String content, boolean enabled) {}

    public static byte[] privateRequest(String target, String message) {
        return encode(Type.PRIVATE_REQUEST, out -> {
            writeString(out, target);
            writeString(out, message);
        });
    }

    public static byte[] replyRequest(String message) {
        return encode(Type.REPLY_REQUEST, out -> writeString(out, message));
    }

    public static byte[] staffRequest(String componentJson) {
        return encode(Type.STAFF_REQUEST, out -> writeString(out, componentJson));
    }

    public static byte[] delivery(UUID playerId, String componentJson) {
        return encode(Type.DELIVERY, out -> {
            writeUuid(out, playerId);
            writeString(out, componentJson);
        });
    }

    public static byte[] staffState(UUID playerId, boolean enabled) {
        return encode(Type.STAFF_STATE, out -> {
            writeUuid(out, playerId);
            out.writeBoolean(enabled);
        });
    }

    public static Packet decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid chat bridge payload length");
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            Type type = Type.fromId(in.readUnsignedByte());
            Packet packet = switch (type) {
                case PRIVATE_REQUEST ->
                        new Packet(type, null, readString(in), readString(in), false);
                case REPLY_REQUEST, STAFF_REQUEST ->
                        new Packet(type, null, null, readString(in), false);
                case DELIVERY ->
                        new Packet(type, readUuid(in), null, readString(in), false);
                case STAFF_STATE ->
                        new Packet(type, readUuid(in), null, null, in.readBoolean());
            };
            if (in.available() != 0) {
                throw new IllegalArgumentException("Trailing chat bridge payload data");
            }
            return packet;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed chat bridge payload", e);
        }
    }

    private static byte[] encode(Type type, OutputWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(type.id);
                writer.write(out);
            }
            byte[] payload = bytes.toByteArray();
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Chat bridge payload is too large");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to encode chat bridge payload", e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Chat bridge strings cannot be null");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Chat bridge string is too large");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > in.available()) {
            throw new IllegalArgumentException("Invalid chat bridge string length");
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        if (uuid == null) {
            throw new IllegalArgumentException("Chat bridge UUID cannot be null");
        }
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    @FunctionalInterface
    private interface OutputWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
