package crabcraft.net.crabUtilities.voicechat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Wire format for the Redis voice bus.
 *
 * <p>Audio frames on {@code crabcraft:svc:audio:&lt;uuid&gt;} are
 * length-prefixed binary because the opus payload is not text.
 */
final class VoiceMessages {

    static final String AUDIO_CHANNEL_PREFIX = "crabcraft:svc:audio:";
    static final String PLAYER_HOME_KEY_PREFIX = "crabcraft:svc:player-home:";

    private VoiceMessages() {}

    static String audioChannel(UUID groupId) {
        return AUDIO_CHANNEL_PREFIX + groupId;
    }

    static String playerHomeKey(UUID playerId) {
        return PLAYER_HOME_KEY_PREFIX + playerId;
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
