package crabcraft.net.crabUtilities.velocity;

import java.util.Arrays;

final class JadeClientProtocolPayloadRegressionTest {

    public static void main(String[] args) {
        byte[] encoded = ConnectionListener.encodeClientProtocol(776);
        check(Arrays.equals(encoded, new byte[]{0, 0, 3, 8}),
                "the proxy encoded the 26.2 protocol payload incorrectly");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
