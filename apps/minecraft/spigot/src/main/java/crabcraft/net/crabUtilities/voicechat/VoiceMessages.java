package crabcraft.net.crabUtilities.voicechat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Wire format for the Redis voice bus.
 *
 * <p>Audio frames on {@code crabcraft:svc:audio:&lt;uuid&gt;} are
 * length-prefixed binary because the opus payload is not text.
 *
 * <p>Roster lifecycle messages on {@code crabcraft:svc:roster} are
 * NUL-separated UTF-8 strings, the first field being the opcode. Any
 * field that might itself contain NUL (the profile snapshot) is
 * base64-encoded before being placed in the outer envelope.
 */
final class VoiceMessages {

    static final String AUDIO_CHANNEL_PREFIX = "crabcraft:svc:audio:";
    static final String PLAYER_HOME_KEY_PREFIX = "crabcraft:svc:player-home:";
    static final String ROSTER_CHANNEL = "crabcraft:svc:roster";

    static final String SEP = "\0";

    static final String OP_ROSTER_JOIN = "ROSTER_JOIN";
    static final String OP_ROSTER_LEAVE = "ROSTER_LEAVE";
    static final String OP_ROSTER_HEARTBEAT = "ROSTER_HEARTBEAT";

    private VoiceMessages() {}

    static String audioChannel(UUID groupId) {
        return AUDIO_CHANNEL_PREFIX + groupId;
    }

    static String playerHomeKey(UUID playerId) {
        return PLAYER_HOME_KEY_PREFIX + playerId;
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
     * {@code ROSTER_JOIN<NUL>group<NUL>player<NUL>backend<NUL>profileBase64}
     * The profile is itself NUL-separated internally so we base64-encode it
     * before stuffing into the outer NUL-separated envelope.
     */
    static String encodeRosterJoin(UUID groupId, UUID playerId, String backend,
                                   String encodedProfile) {
        String profileBase64 = Base64.getEncoder().encodeToString(
                encodedProfile.getBytes(StandardCharsets.UTF_8));
        return String.join(SEP, OP_ROSTER_JOIN, groupId.toString(),
                playerId.toString(), backend, profileBase64);
    }

    static RosterJoin decodeRosterJoin(String message) {
        String[] parts = message.split(SEP, -1);
        if (parts.length < 5) return null;
        try {
            UUID groupId = UUID.fromString(parts[1]);
            UUID playerId = UUID.fromString(parts[2]);
            byte[] profileBytes = Base64.getDecoder().decode(parts[4]);
            String encodedProfile = new String(profileBytes, StandardCharsets.UTF_8);
            return new RosterJoin(groupId, playerId, parts[3], encodedProfile);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record RosterJoin(UUID groupId, UUID playerId, String backend, String encodedProfile) {}

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
     * {@code ROSTER_HEARTBEAT<NUL>backend<NUL>group:player[,group:player...]}
     * Used to reconcile after a backend crash — receivers drop entries
     * from {@code backend} that aren't in the heartbeat list.
     */
    static String encodeRosterHeartbeat(String backend, List<UUID[]> groupPlayerPairs) {
        StringBuilder pairs = new StringBuilder();
        for (int i = 0; i < groupPlayerPairs.size(); i++) {
            if (i > 0) pairs.append(',');
            UUID[] pair = groupPlayerPairs.get(i);
            pairs.append(pair[0]).append(':').append(pair[1]);
        }
        return String.join(SEP, OP_ROSTER_HEARTBEAT, backend, pairs.toString());
    }

    static RosterHeartbeat decodeRosterHeartbeat(String message) {
        String[] parts = message.split(SEP, -1);
        if (parts.length < 3) return null;
        String backend = parts[1];
        List<UUID[]> pairs = new ArrayList<>();
        if (!parts[2].isEmpty()) {
            for (String pair : parts[2].split(",")) {
                String[] gp = pair.split(":", 2);
                if (gp.length != 2) continue;
                try {
                    pairs.add(new UUID[]{UUID.fromString(gp[0]), UUID.fromString(gp[1])});
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return new RosterHeartbeat(backend, pairs);
    }

    record RosterHeartbeat(String backend, List<UUID[]> groupPlayerPairs) {}
}
