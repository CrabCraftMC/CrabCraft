package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    static final String CALL_TARGET_KEY_PREFIX = "crabcraft:svc:call-target:";
    static final String GROUP_MEMBERS_KEY_PREFIX = "crabcraft:svc:group-members:";
    static final String GROUPS_REGISTRY_KEY = "crabcraft:svc:groups";
    static final String PERMANENT_GROUPS_KEY = "crabcraft:svc:groups:permanent";
    static final String ROSTER_CHANNEL = "crabcraft:svc:roster";
    static final String LIFECYCLE_CHANNEL = "crabcraft:svc:lifecycle";

    static final String SEP = "\0";

    static final String OP_GROUP_CHANGED = "GROUP_CHANGED";
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

    static String callTargetKey(UUID playerId) {
        return CALL_TARGET_KEY_PREFIX + playerId;
    }

    static String routeBackend(String route) {
        if (route == null) return null;
        int separator = route.indexOf(SEP);
        return separator < 0 ? route : route.substring(0, separator);
    }

    /* ----------------------- Audio ----------------------- */

    /**
     * Audio frame layout (binary):
     * <pre>
     *   2 bytes: route length (uint16)
     *   N bytes: route (backend + hop token, UTF-8)
     *  16 bytes: speaker UUID
     *   1 byte:  whispering flag (0/1)
     *   2 bytes: opus length (uint16)
     *   N bytes: opus data
     * </pre>
     */
    static byte[] encodeAudioFrame(String route, UUID speaker,
                                   boolean whispering, byte[] opus) {
        byte[] routeBytes = route.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + routeBytes.length + 16 + 1 + 2 + opus.length);
        buf.putShort((short) routeBytes.length);
        buf.put(routeBytes);
        buf.putLong(speaker.getMostSignificantBits());
        buf.putLong(speaker.getLeastSignificantBits());
        buf.put((byte) (whispering ? 1 : 0));
        buf.putShort((short) opus.length);
        buf.put(opus);
        return buf.array();
    }

    static AudioFrame decodeAudioFrame(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int routeLength = buf.getShort() & 0xFFFF;
        byte[] routeBytes = new byte[routeLength];
        buf.get(routeBytes);
        String route = new String(routeBytes, StandardCharsets.UTF_8);
        UUID speaker = new UUID(buf.getLong(), buf.getLong());
        boolean whispering = buf.get() != 0;
        int opusLen = buf.getShort() & 0xFFFF;
        byte[] opus = new byte[opusLen];
        buf.get(opus);
        return new AudioFrame(route, speaker, whispering, opus);
    }

    record AudioFrame(String route, UUID speaker, boolean whispering, byte[] opus) {}

    /* ----------------------- Group definitions ----------------------- */

    /**
     * Passwords live only in the authoritative Redis hash. Pub/sub carries
     * the group ID as an invalidation, so credentials never appear in
     * lifecycle messages or logs.
     */
    static String encodeGroupDefinition(GroupDefinition group) {
        return String.join(SEP,
                encodeText(group.name()),
                group.password() == null ? "0" : "1",
                group.password() == null ? "" : encodeText(group.password()),
                typeToString(group.type()),
                group.hidden() ? "1" : "0",
                group.permanent() ? "1" : "0");
    }

    static GroupDefinition decodeGroupDefinition(UUID id, String encoded) {
        if (encoded == null) return null;
        String[] parts = encoded.split(SEP, -1);
        if (parts.length != 6) return null;
        try {
            String password = "1".equals(parts[1]) ? decodeText(parts[2]) : null;
            return new GroupDefinition(id, decodeText(parts[0]), password,
                    typeFromString(parts[3]), "1".equals(parts[4]), "1".equals(parts[5]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String encodeGroupChanged(UUID groupId) {
        return String.join(SEP, OP_GROUP_CHANGED, groupId.toString());
    }

    static UUID decodeGroupChanged(String message) {
        String[] parts = message.split(SEP, -1);
        if (parts.length != 2 || !OP_GROUP_CHANGED.equals(parts[0])) return null;
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String typeToString(Group.Type type) {
        if (type == Group.Type.NORMAL) return "NORMAL";
        if (type == Group.Type.OPEN) return "OPEN";
        if (type == Group.Type.ISOLATED) return "ISOLATED";
        throw new IllegalArgumentException("Unknown group type");
    }

    static Group.Type typeFromString(String value) {
        return switch (value) {
            case "OPEN" -> Group.Type.OPEN;
            case "ISOLATED" -> Group.Type.ISOLATED;
            case "NORMAL" -> Group.Type.NORMAL;
            default -> throw new IllegalArgumentException("Unknown group type");
        };
    }

    private static String encodeText(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    record GroupDefinition(UUID id, String name, String password, Group.Type type,
                           boolean hidden, boolean permanent) {}

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

    /* ----------------------- Private calls ----------------------- */

    /** Password-free wake-up hint; the target and group secret are read from Redis. */
    static String encodeCallJoin(UUID groupId, UUID playerId, String generation) {
        requireOpaqueToken(generation);
        return String.join(SEP, OP_CALL_JOIN, groupId.toString(), playerId.toString(), generation);
    }

    static CallJoin decodeCallJoin(String message) {
        String[] parts = message.split(SEP, -1);
        if (parts.length != 4 || !OP_CALL_JOIN.equals(parts[0])
                || !isOpaqueToken(parts[3])) return null;
        try {
            return new CallJoin(parseCanonicalUuid(parts[1]),
                    parseCanonicalUuid(parts[2]), parts[3]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record CallJoin(UUID groupId, UUID playerId, String generation) {
        CallTarget target() {
            return new CallTarget(groupId, generation);
        }
    }

    static String encodeCallTarget(CallTarget target) {
        requireOpaqueToken(target.generation());
        return String.join(SEP, target.groupId().toString(), target.generation());
    }

    static CallTarget decodeCallTarget(String encoded) {
        if (encoded == null) return null;
        String[] parts = encoded.split(SEP, -1);
        if (parts.length != 2 || !isOpaqueToken(parts[1])) return null;
        try {
            return new CallTarget(parseCanonicalUuid(parts[0]), parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record CallTarget(UUID groupId, String generation) {}

    static String encodeCallRingStart(String token, UUID playerId,
                                      RingDirection direction, long expiresAtMillis) {
        requireOpaqueToken(token);
        if (playerId == null || direction == null || expiresAtMillis <= 0L) {
            throw new IllegalArgumentException("Invalid call ringtone start");
        }
        return String.join(SEP, OP_CALL_RING_START, token, playerId.toString(),
                direction.name(), Long.toString(expiresAtMillis));
    }

    static CallRingStart decodeCallRingStart(String message) {
        String[] parts = message.split(SEP, -1);
        if (parts.length != 5 || !OP_CALL_RING_START.equals(parts[0])
                || !isOpaqueToken(parts[1])) return null;
        try {
            long expiresAtMillis = parseCanonicalPositiveLong(parts[4]);
            return new CallRingStart(parts[1], parseCanonicalUuid(parts[2]),
                    RingDirection.valueOf(parts[3]), expiresAtMillis);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record CallRingStart(String token, UUID playerId, RingDirection direction,
                         long expiresAtMillis) {}

    static String encodeCallRingStop(String token, UUID playerId, RingDirection direction) {
        requireOpaqueToken(token);
        if (playerId == null || direction == null) {
            throw new IllegalArgumentException("Invalid call ringtone stop");
        }
        return String.join(SEP, OP_CALL_RING_STOP, token, playerId.toString(), direction.name());
    }

    static CallRingStop decodeCallRingStop(String message) {
        String[] parts = message.split(SEP, -1);
        if (parts.length != 4 || !OP_CALL_RING_STOP.equals(parts[0])
                || !isOpaqueToken(parts[1])) return null;
        try {
            return new CallRingStop(parts[1], parseCanonicalUuid(parts[2]),
                    RingDirection.valueOf(parts[3]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record CallRingStop(String token, UUID playerId, RingDirection direction) {}

    enum RingDirection { INCOMING, OUTGOING }

    private static void requireOpaqueToken(String token) {
        if (!isOpaqueToken(token)) throw new IllegalArgumentException("Invalid call token");
    }

    private static boolean isOpaqueToken(String token) {
        if (token == null || token.length() < 22 || token.length() > 64) return false;
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') {
                return false;
            }
        }
        return true;
    }

    private static UUID parseCanonicalUuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("Non-canonical UUID");
        }
        return parsed;
    }

    private static long parseCanonicalPositiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("Non-canonical positive long");
        }
        return parsed;
    }
}
