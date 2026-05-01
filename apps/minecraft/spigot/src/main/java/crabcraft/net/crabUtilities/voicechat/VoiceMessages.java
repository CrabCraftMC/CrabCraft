package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Wire format for the Redis voice bus.
 *
 * <p>Lifecycle messages on {@code crabcraft:svc:groups} are NUL-separated
 * UTF-8 strings, the first field being the opcode. Audio frames on
 * {@code crabcraft:svc:audio:<uuid>} are length-prefixed binary because
 * the opus payload is not text.
 */
final class VoiceMessages {

    static final String SEP = "\0";

    static final String OP_GROUP_CREATE = "GROUP_CREATE";
    static final String OP_GROUP_REMOVE = "GROUP_REMOVE";
    static final String OP_MEMBER_JOIN = "MEMBER_JOIN";
    static final String OP_MEMBER_LEAVE = "MEMBER_LEAVE";
    static final String OP_SPEAKER_LEFT = "SPEAKER_LEFT";

    static final String LIFECYCLE_CHANNEL = "crabcraft:svc:groups";
    static final String GROUPS_REGISTRY_KEY = "crabcraft:svc:groups:registry";
    static final String AUDIO_CHANNEL_PREFIX = "crabcraft:svc:audio:";
    static final String PLAYER_HOME_KEY_PREFIX = "crabcraft:svc:player-home:";

    private VoiceMessages() {}

    static String audioChannel(UUID groupId) {
        return AUDIO_CHANNEL_PREFIX + groupId;
    }

    static String playerHomeKey(UUID playerId) {
        return PLAYER_HOME_KEY_PREFIX + playerId;
    }

    static String typeToString(Group.Type type) {
        if (type == Group.Type.OPEN) return "OPEN";
        if (type == Group.Type.ISOLATED) return "ISOLATED";
        return "NORMAL";
    }

    static Group.Type typeFromString(String s) {
        if ("OPEN".equals(s)) return Group.Type.OPEN;
        if ("ISOLATED".equals(s)) return Group.Type.ISOLATED;
        return Group.Type.NORMAL;
    }

    static String encodeGroupCreate(UUID id, String name, String password,
                                    Group.Type type, String originator) {
        return String.join(SEP,
                OP_GROUP_CREATE,
                id.toString(),
                name == null ? "" : name,
                password == null ? "" : password,
                typeToString(type),
                originator);
    }

    static String encodeGroupRemove(UUID id, String originator) {
        return String.join(SEP, OP_GROUP_REMOVE, id.toString(), originator);
    }

    static String encodeMemberJoin(UUID groupId, UUID playerId, String backend) {
        return String.join(SEP, OP_MEMBER_JOIN, groupId.toString(), playerId.toString(), backend);
    }

    static String encodeMemberLeave(UUID groupId, UUID playerId, String backend) {
        return String.join(SEP, OP_MEMBER_LEAVE, groupId.toString(), playerId.toString(), backend);
    }

    static String encodeSpeakerLeft(UUID playerId, String backend) {
        return String.join(SEP, OP_SPEAKER_LEFT, playerId.toString(), backend);
    }

    /**
     * Audio frame layout (binary):
     * <pre>
     *   2 bytes: backend name length (uint16)
     *   N bytes: backend name (UTF-8)
     *  16 bytes: speaker UUID
     *   1 byte:  whispering flag (0/1)
     *   2 bytes: opus length (uint16)
     *   N bytes: opus data
     * </pre>
     */
    static byte[] encodeAudioFrame(String backend, UUID speaker,
                                   boolean whispering, byte[] opus) {
        byte[] backendBytes = backend.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + backendBytes.length + 16 + 1 + 2 + opus.length);
        buf.putShort((short) backendBytes.length);
        buf.put(backendBytes);
        buf.putLong(speaker.getMostSignificantBits());
        buf.putLong(speaker.getLeastSignificantBits());
        buf.put((byte) (whispering ? 1 : 0));
        buf.putShort((short) opus.length);
        buf.put(opus);
        return buf.array();
    }

    static AudioFrame decodeAudioFrame(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int backendLen = buf.getShort() & 0xFFFF;
        byte[] backendBytes = new byte[backendLen];
        buf.get(backendBytes);
        String backend = new String(backendBytes, StandardCharsets.UTF_8);
        UUID speaker = new UUID(buf.getLong(), buf.getLong());
        boolean whispering = buf.get() != 0;
        int opusLen = buf.getShort() & 0xFFFF;
        byte[] opus = new byte[opusLen];
        buf.get(opus);
        return new AudioFrame(backend, speaker, whispering, opus);
    }

    record AudioFrame(String homeBackend, UUID speaker, boolean whispering, byte[] opus) {}
}
