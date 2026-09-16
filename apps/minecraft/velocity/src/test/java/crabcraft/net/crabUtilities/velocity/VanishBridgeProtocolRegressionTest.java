package crabcraft.net.crabUtilities.velocity;

import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol;

import java.util.List;

final class VanishBridgeProtocolRegressionTest {

    private VanishBridgeProtocolRegressionTest() {
    }

    public static void main(String[] args) {
        check(!VanishBridgeProtocol.decode(VanishBridgeProtocol.status(false)),
                "visible state did not round-trip");
        check(VanishBridgeProtocol.decode(VanishBridgeProtocol.status(true)),
                "vanished state did not round-trip");
        rejects(new byte[]{1}, "short payload was accepted");
        rejects(new byte[]{2, 0}, "unknown protocol version was accepted");
        rejects(new byte[]{1, 2}, "unknown status value was accepted");

        check(VanishManager.isPubliclyVisible("survival", false, "survival"),
                "current visible report was not public");
        check(!VanishManager.isPubliclyVisible("survival", true, "survival"),
                "vanished report was public");
        check(!VanishManager.isPubliclyVisible("survival", false, "creative"),
                "stale backend report remained public after a transfer");
        check(!VanishManager.isPubliclyVisible(null, false, "survival"),
                "missing backend report was not fail-closed");

        check(ConnectionListener.isSilentJoinHost(
                        "Mods.CrabCraft.Net.", List.of("mods.crabcraft.net")),
                "silent join hostname was not normalised");
        check(!ConnectionListener.isSilentJoinHost(
                        "crabcraft.net", List.of("mods.crabcraft.net")),
                "ordinary hostname was treated as silent");
    }

    private static void rejects(byte[] payload, String message) {
        try {
            VanishBridgeProtocol.decode(payload);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
