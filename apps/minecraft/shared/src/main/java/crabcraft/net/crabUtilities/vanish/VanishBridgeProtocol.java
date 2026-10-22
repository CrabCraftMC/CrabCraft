package crabcraft.net.crabUtilities.vanish;

import java.nio.ByteBuffer;

/** Velocity publishes authoritative state; Paper acknowledges the exact revision it applied. */
public final class VanishBridgeProtocol {

    public static final String CHANNEL = "crabcraft:vanish";
    public static final byte VERSION = 2;
    private static final byte READY = 0;
    private static final byte STATUS = 1;

    private VanishBridgeProtocol() {
    }

    public static byte[] ready() {
        return new byte[]{VERSION, READY};
    }

    public static byte[] status(long revision, boolean vanished) {
        if (revision < 0) throw new IllegalArgumentException("Negative vanish revision");
        return ByteBuffer.allocate(11)
                .put(VERSION).put(STATUS).putLong(revision).put((byte) (vanished ? 1 : 0)).array();
    }

    public static Message decode(byte[] payload) {
        if (payload == null || payload.length < 2 || payload[0] != VERSION) {
            throw new IllegalArgumentException("Invalid vanish payload");
        }
        if (payload[1] == READY && payload.length == 2) {
            return new Message(true, 0, false);
        }
        if (payload[1] != STATUS || payload.length != 11) {
            throw new IllegalArgumentException("Invalid vanish message type or length");
        }
        long revision = ByteBuffer.wrap(payload, 2, Long.BYTES).getLong();
        if (revision < 0 || (payload[10] != 0 && payload[10] != 1)) {
            throw new IllegalArgumentException("Invalid vanish revision or state");
        }
        return new Message(false, revision, payload[10] == 1);
    }

    public record Message(boolean ready, long revision, boolean vanished) {
    }
}
