package crabcraft.net.crabUtilities.tags;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/** Bounded player-bound messages shared by Paper and Velocity. */
public final class TagBridgeProtocol {
    public static final String CHANNEL = "crabcraft:tags";
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 30_000;
    private static final int MAX_STRING_BYTES = 4096;
    private static final int MAX_TAGS = 128;

    private TagBridgeProtocol() {}

    public enum Type { QUERY, SELECT, RESULT, STATE }
    public enum Status { OK, NOT_OWNED, DISABLED, UNKNOWN_TAG, STALE_STATE, UNAVAILABLE }

    public record Packet(Type type, UUID requestId, UUID playerId, String selectedTagId,
                         long expectedRevision, Status status, TagSnapshot snapshot) {}

    public static byte[] query(UUID requestId) {
        return encode(Type.QUERY, out -> writeUuid(out, requestId));
    }

    public static byte[] select(UUID requestId, String selectedTagId, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException("Negative tag revision");
        if (selectedTagId != null && !selectedTagId.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
            throw new IllegalArgumentException("Invalid selected tag ID");
        }
        return encode(Type.SELECT, out -> {
            writeUuid(out, requestId);
            writeNullableString(out, selectedTagId);
            out.writeLong(expectedRevision);
        });
    }

    public static byte[] result(UUID requestId, Status status, TagSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Result requires player identity");
        return result(requestId, snapshot.playerId(), status, snapshot);
    }

    public static byte[] result(UUID requestId, UUID playerId, Status status, TagSnapshot snapshot) {
        if (status == null || (snapshot == null && status != Status.UNAVAILABLE)) {
            throw new IllegalArgumentException("Invalid tag result");
        }
        if (snapshot != null && !snapshot.playerId().equals(playerId)) {
            throw new IllegalArgumentException("Tag result belongs to another player");
        }
        return encode(Type.RESULT, out -> {
            writeUuid(out, requestId);
            writeUuid(out, playerId);
            out.writeByte(status.ordinal());
            out.writeBoolean(snapshot != null);
            if (snapshot != null) writeSnapshot(out, snapshot);
        });
    }

    public static byte[] state(TagSnapshot snapshot) {
        return encode(Type.STATE, out -> writeSnapshot(out, snapshot));
    }

    public static Packet decode(byte[] payload) {
        if (payload == null || payload.length < 2 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid tag payload length");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readUnsignedByte() != VERSION) throw new IllegalArgumentException("Unknown tag protocol version");
            int typeId = in.readUnsignedByte();
            if (typeId >= Type.values().length) throw new IllegalArgumentException("Unknown tag packet type");
            Type type = Type.values()[typeId];
            Packet packet = switch (type) {
                case QUERY -> new Packet(type, readUuid(in), null, null, 0, null, null);
                case SELECT -> {
                    UUID request = readUuid(in);
                    String selected = readNullableString(in);
                    long revision = in.readLong();
                    if (revision < 0) throw new IllegalArgumentException("Negative tag revision");
                    if (selected != null && !selected.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
                        throw new IllegalArgumentException("Invalid selected tag ID");
                    }
                    yield new Packet(type, request, null, selected, revision, null, null);
                }
                case RESULT -> {
                    UUID request = readUuid(in);
                    UUID player = readUuid(in);
                    int statusId = in.readUnsignedByte();
                    if (statusId >= Status.values().length) throw new IllegalArgumentException("Unknown tag status");
                    Status status = Status.values()[statusId];
                    TagSnapshot snapshot = readBoolean(in) ? readSnapshot(in) : null;
                    if ((snapshot == null && status != Status.UNAVAILABLE)
                            || (snapshot != null && !snapshot.playerId().equals(player))) {
                        throw new IllegalArgumentException("Invalid tag result snapshot");
                    }
                    yield new Packet(type, request, player, null, 0, status, snapshot);
                }
                case STATE -> {
                    TagSnapshot snapshot = readSnapshot(in);
                    yield new Packet(type, null, snapshot.playerId(), null, 0, null, snapshot);
                }
            };
            if (in.available() != 0) throw new IllegalArgumentException("Trailing tag payload data");
            return packet;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed tag payload", e);
        }
    }

    private static void writeSnapshot(DataOutputStream out, TagSnapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.ownedTags().size() > MAX_TAGS) {
            throw new IllegalArgumentException("Tag collection is too large");
        }
        writeUuid(out, snapshot.playerId());
        out.writeLong(snapshot.revision());
        writeNullableString(out, snapshot.selectedTagId());
        out.writeInt(snapshot.ownedTags().size());
        for (TagDefinition tag : snapshot.ownedTags()) {
            writeString(out, tag.id());
            writeString(out, tag.name());
            writeString(out, tag.description());
            writeString(out, tag.glyphId());
            out.writeInt(tag.sortOrder());
            out.writeBoolean(tag.enabled());
        }
    }

    private static TagSnapshot readSnapshot(DataInputStream in) throws IOException {
        UUID player = readUuid(in);
        long revision = in.readLong();
        String selected = readNullableString(in);
        int count = in.readInt();
        if (count < 0 || count > MAX_TAGS) throw new IllegalArgumentException("Invalid tag count");
        var tags = new ArrayList<TagDefinition>(count);
        for (int i = 0; i < count; i++) {
            tags.add(new TagDefinition(readString(in), readString(in), readString(in), readString(in),
                    in.readInt(), readBoolean(in)));
        }
        return new TagSnapshot(player, revision, selected, tags);
    }

    private static byte[] encode(Type type, Writer writer) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeByte(VERSION);
                out.writeByte(type.ordinal());
                writer.write(out);
            }
            if (bytes.size() > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Tag payload is too large");
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to encode tag payload", e);
        }
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) writeString(out, value);
    }

    private static String readNullableString(DataInputStream in) throws IOException {
        return readBoolean(in) ? readString(in) : null;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) throw new IllegalArgumentException("Null tag text");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("Tag text is too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > in.available()) {
            throw new IllegalArgumentException("Invalid tag text length");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8 tag text", e);
        }
    }

    private static boolean readBoolean(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw new IllegalArgumentException("Invalid tag boolean");
        return value == 1;
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        if (value == null) throw new IllegalArgumentException("Missing tag identity");
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
