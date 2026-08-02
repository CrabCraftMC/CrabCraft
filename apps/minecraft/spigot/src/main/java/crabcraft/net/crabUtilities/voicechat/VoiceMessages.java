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
    static final String CALL_METADATA_KEY_PREFIX = "crabcraft:svc:call:";
    static final String CALL_TARGET_KEY_PREFIX = "crabcraft:svc:call-target:";
    static final String ROSTER_CHANNEL = "crabcraft:svc:roster";

    static final String SEP = "\0";

    static final String OP_ROSTER_JOIN = "ROSTER_JOIN";
    static final String OP_ROSTER_LEAVE = "ROSTER_LEAVE";
    static final String OP_CALL_JOIN = "CALL_JOIN";
    static final String OP_CALL_RING_START = "CALL_RING_START";
    static final String OP_CALL_RING_STOP = "CALL_RING_STOP";

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

    static String callMetadataKey(UUID groupId) {
        return CALL_METADATA_KEY_PREFIX + groupId;
    }

    static String callTargetKey(UUID playerId) {
        return CALL_TARGET_KEY_PREFIX + playerId;
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

    /**
     * {@code CALL_JOIN<NUL>group<NUL>player<NUL>password}
     */
    static String encodeCallJoin(UUID groupId, UUID playerId, String password) {
        if (!isValidCallPassword(password)) {
            throw new IllegalArgumentException("Call password must be a URL-safe 22-64 character secret");
        }
        return String.join(SEP, OP_CALL_JOIN, groupId.toString(),
                playerId.toString(), password);
    }

    static CallJoin decodeCallJoin(String message) {
        if (message == null) return null;
        String[] parts = message.split(SEP, -1);
        if (parts.length != 4 || !OP_CALL_JOIN.equals(parts[0])
                || !isValidCallPassword(parts[3])) return null;
        try {
            return new CallJoin(UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]), parts[3]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isValidCallPassword(String password) {
        if (password == null || password.length() < 22 || password.length() > 64) return false;
        for (int i = 0; i < password.length(); i++) {
            char character = password.charAt(i);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') return false;
        }
        return true;
    }

    record CallJoin(UUID groupId, UUID playerId, String password) {}

    /**
     * {@code CALL_RING_START<NUL>token<NUL>player<NUL>direction<NUL>expiresAtMillis}
     */
    static String encodeCallRingStart(String token, UUID playerId,
                                      RingDirection direction, long expiresAtMillis) {
        if (!isValidCallRingToken(token) || expiresAtMillis <= 0L) {
            throw new IllegalArgumentException("Invalid call ringtone start");
        }
        return String.join(SEP, OP_CALL_RING_START, token, playerId.toString(),
                direction.name(), Long.toString(expiresAtMillis));
    }

    static CallRingStart decodeCallRingStart(String message) {
        if (message == null) return null;
        String[] parts = message.split(SEP, -1);
        if (parts.length != 5 || !OP_CALL_RING_START.equals(parts[0])
                || !isValidCallRingToken(parts[1])) return null;
        try {
            UUID playerId = UUID.fromString(parts[2]);
            RingDirection direction = RingDirection.valueOf(parts[3]);
            long expiresAtMillis = Long.parseLong(parts[4]);
            if (!playerId.toString().equals(parts[2]) || expiresAtMillis <= 0L
                    || !Long.toString(expiresAtMillis).equals(parts[4])) return null;
            return new CallRingStart(parts[1], playerId, direction, expiresAtMillis);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code CALL_RING_STOP<NUL>token<NUL>player<NUL>direction}
     */
    static String encodeCallRingStop(String token, UUID playerId, RingDirection direction) {
        if (!isValidCallRingToken(token)) {
            throw new IllegalArgumentException("Invalid call ringtone stop");
        }
        return String.join(SEP, OP_CALL_RING_STOP, token, playerId.toString(), direction.name());
    }

    static CallRingStop decodeCallRingStop(String message) {
        if (message == null) return null;
        String[] parts = message.split(SEP, -1);
        if (parts.length != 4 || !OP_CALL_RING_STOP.equals(parts[0])
                || !isValidCallRingToken(parts[1])) return null;
        try {
            UUID playerId = UUID.fromString(parts[2]);
            if (!playerId.toString().equals(parts[2])) return null;
            return new CallRingStop(parts[1], playerId, RingDirection.valueOf(parts[3]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isValidCallRingToken(String token) {
        if (token == null || token.length() < 22 || token.length() > 64) return false;
        for (int i = 0; i < token.length(); i++) {
            char character = token.charAt(i);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') return false;
        }
        return true;
    }

    enum RingDirection {
        INCOMING,
        OUTGOING
    }

    record CallRingStart(String token, UUID playerId, RingDirection direction,
                         long expiresAtMillis) {}

    record CallRingStop(String token, UUID playerId, RingDirection direction) {}
}
