package crabcraft.net.crabUtilities.voicechat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Wire format for the Redis voice bus.
 *
 * <p>Audio frames on {@code crabcraft:svc:audio:&lt;uuid&gt;} are
 * length-prefixed binary because the opus payload is not text.
 *
 * <p>Roster lifecycle messages on {@code crabcraft:svc:roster} are
 * NUL-separated UTF-8 strings, the first field being the opcode. We
 * carry no profile data — cross-server group members rely on the
 * existing tab-list sync to populate the receiving client's
 * {@code playerInfoMap}, which is what SVC reads from for skins.
 */
final class VoiceMessages {

    static final String AUDIO_CHANNEL_PREFIX = "crabcraft:svc:audio:";
    static final String PLAYER_HOME_KEY_PREFIX = "crabcraft:svc:player-home:";
    static final String PLAYER_GROUP_KEY_PREFIX = "crabcraft:svc:player-group:";
    static final String ROSTER_CHANNEL = "crabcraft:svc:roster";

    static final String SEP = "\0";

    static final String OP_ROSTER_JOIN = "ROSTER_JOIN";
    static final String OP_ROSTER_LEAVE = "ROSTER_LEAVE";

    private VoiceMessages() {}

    static String audioChannel(UUID groupId) {
        return AUDIO_CHANNEL_PREFIX + groupId;
    }

    static String playerHomeKey(UUID playerId) {
        return PLAYER_HOME_KEY_PREFIX + playerId;
    }

    static String playerGroupKey(UUID playerId) {
        return PLAYER_GROUP_KEY_PREFIX + playerId;
    }

    /* ----------------------- Audio ----------------------- */

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

    /* ----------------------- Roster lifecycle ----------------------- */

    /**
     * {@code ROSTER_JOIN<NUL>group<NUL>player<NUL>name<NUL>backend}
     */
    static String encodeRosterJoin(UUID groupId, UUID playerId, String name, String backend) {
        return String.join(SEP, OP_ROSTER_JOIN, groupId.toString(),
                playerId.toString(), name == null ? "" : name, backend);
    }

    static RosterJoin decodeRosterJoin(String message) {
        String[] parts = message.split(SEP, -1);
        if (parts.length < 5) return null;
        try {
            return new RosterJoin(UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]), parts[3], parts[4]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record RosterJoin(UUID groupId, UUID playerId, String name, String backend) {}

    static String encodeRosterLeave(UUID groupId, UUID playerId, String backend) {
        return String.join(SEP, OP_ROSTER_LEAVE, groupId.toString(),
                playerId.toString(), backend);
    }

    static RosterLeave decodeRosterLeave(String message) {
        String[] parts = message.split(SEP, -1);
        if (parts.length < 4) return null;
        try {
            return new RosterLeave(UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]), parts[3]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record RosterLeave(UUID groupId, UUID playerId, String backend) {}
}
