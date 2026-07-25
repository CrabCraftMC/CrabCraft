package crabcraft.net.crabUtilities.jade;

final class JadeClientProtocolPayloadRegressionTest {

    public static void main(String[] args) {
        check(JadeBootstrap.decodeClientProtocol(new byte[]{0, 0, 3, 8}) == 776,
                "the proxy's 26.2 protocol payload was decoded incorrectly");
        check(JadeBootstrap.decodeClientProtocol(new byte[]{0, 0, 3}) == -1,
                "a truncated client protocol payload was accepted");
        check(JadeBootstrap.decodeClientProtocol(new byte[]{0, 0, 0, 0}) == -1,
                "an invalid client protocol was accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
