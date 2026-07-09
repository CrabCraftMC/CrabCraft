package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class VoiceRelayRegressionTest {

    public static void main(String[] args) {
        UUID speaker = UUID.fromString("11111111-1111-1111-1111-111111111111");

        check(!AudioRelay.isRelayPayload(null), "null audio must not be relayed");
        check(AudioRelay.isRelayPayload(new byte[0]), "stop marker must be relayed");

        VoiceMessages.AudioFrame stop = VoiceMessages.decodeAudioFrame(
                VoiceMessages.encodeAudioFrame("backend-b", speaker, false, new byte[0]));
        check(stop.speaker().equals(speaker), "stop-frame speaker changed");
        check(stop.opus().length == 0, "zero-length stop frame was not preserved");

        verifyNativeStopUsesNextSequence();
    }

    private static void verifyNativeStopUsesNextSequence() {
        List<String> calls = new ArrayList<>();
        AtomicLong sentSequence = new AtomicLong(-1L);

        VoicechatConnection connection = proxy(VoicechatConnection.class,
                (proxy, method, args) -> {
                    throw new AssertionError("unexpected connection call: " + method.getName());
                });

        AudioSender sender = proxy(AudioSender.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "sequenceNumber" -> {
                    calls.add("sequence");
                    sentSequence.set((long) args[0]);
                    yield proxy;
                }
                case "reset" -> {
                    calls.add("reset");
                    yield true;
                }
                default -> throw new AssertionError("unexpected sender call: " + method.getName());
            };
        });

        VoicechatServerApi api = proxy(VoicechatServerApi.class, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "createAudioSender" -> {
                    calls.add("create");
                    check(args[0] == connection, "wrong reset connection");
                    yield sender;
                }
                case "registerAudioSender" -> {
                    calls.add("register");
                    yield true;
                }
                case "unregisterAudioSender" -> {
                    calls.add("unregister");
                    yield true;
                }
                default -> throw new AssertionError("unexpected API call: " + method.getName());
            };
        });

        check(AudioRelay.sendNativeStop(api, connection, 41L), "native stop was not sent");
        check(sentSequence.get() == 42L, "native stop did not use the next sequence");
        check(calls.equals(List.of("create", "sequence", "register", "reset", "unregister")),
                "native stop call order changed: " + calls);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
