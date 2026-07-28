package crabcraft.net.crabUtilities.voicechat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

final class SimpleVoiceAnimationsProtocolRegressionTest {

    public static void main(String[] args) {
        decodesClientPreferences();
        clampsClientPreferencesLikeTheMod();
        rejectsMalformedClientPreferences();
        encodesPlayerPreferences();
    }

    private static void decodesClientPreferences() {
        byte[] payload = ByteBuffer.allocate(9)
                .put((byte) 4)
                .putFloat(2.5F)
                .putFloat(1.25F)
                .array();

        var preferences = SimpleVoiceAnimationsIntegration.decodePreferences(payload);
        check(preferences.headAnimationStyle() == 4, "head animation style was decoded incorrectly");
        check(preferences.splitHeight() == 2.5F, "split height was decoded incorrectly");
        check(preferences.intensity() == 1.25F, "intensity was decoded incorrectly");
    }

    private static void clampsClientPreferencesLikeTheMod() {
        byte[] payload = ByteBuffer.allocate(9)
                .put((byte) 1)
                .putFloat(Float.POSITIVE_INFINITY)
                .putFloat(Float.NaN)
                .array();

        var preferences = SimpleVoiceAnimationsIntegration.decodePreferences(payload);
        check(preferences.splitHeight() == 0.5F, "non-finite split height did not use the mod fallback");
        check(preferences.intensity() == 1F, "non-finite intensity did not use the mod fallback");
    }

    private static void rejectsMalformedClientPreferences() {
        expectInvalid(new byte[]{6, 0, 0, 0, 0, 0, 0, 0, 0});
        expectInvalid(new byte[]{1, 0, 0, 0, 0, 0, 0, 0});
        expectInvalid(new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    }

    private static void encodesPlayerPreferences() {
        UUID playerId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        var preferences = new SimpleVoiceAnimationsIntegration.Preferences(5, 2F, 1.25F);

        byte[] expected = new byte[]{
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB,
                (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF,
                0x05,
                0x40, 0x00, 0x00, 0x00,
                0x3F, (byte) 0xA0, 0x00, 0x00
        };
        byte[] actual = SimpleVoiceAnimationsIntegration.encodePlayerPreferences(
                playerId,
                preferences);
        check(Arrays.equals(actual, expected), "player preferences payload was encoded incorrectly");
    }

    private static void expectInvalid(byte[] payload) {
        try {
            SimpleVoiceAnimationsIntegration.decodePreferences(payload);
            throw new AssertionError("malformed preferences payload was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
