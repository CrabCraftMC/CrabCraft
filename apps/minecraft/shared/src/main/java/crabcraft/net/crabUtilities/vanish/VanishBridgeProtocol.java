package crabcraft.net.crabUtilities.vanish;

/** Wire format used to report EssentialsX vanish state from Paper to Velocity. */
public final class VanishBridgeProtocol {

    public static final String CHANNEL = "crabcraft:vanish";
    private static final byte VERSION = 1;
    private static final byte VISIBLE = 0;
    private static final byte VANISHED = 1;

    private VanishBridgeProtocol() {
    }

    public static byte[] status(boolean vanished) {
        return new byte[]{VERSION, vanished ? VANISHED : VISIBLE};
    }

    public static boolean decode(byte[] payload) {
        if (payload == null || payload.length != 2 || payload[0] != VERSION) {
            throw new IllegalArgumentException("Invalid vanish status payload");
        }
        return switch (payload[1]) {
            case VISIBLE -> false;
            case VANISHED -> true;
            default -> throw new IllegalArgumentException("Unknown vanish status value");
        };
    }
}
