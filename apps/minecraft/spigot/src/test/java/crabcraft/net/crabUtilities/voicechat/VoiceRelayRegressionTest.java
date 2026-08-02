package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

final class VoiceRelayRegressionTest {

    public static void main(String[] args) throws Exception {
        UUID speaker = UUID.fromString("11111111-1111-1111-1111-111111111111");

        check(!AudioRelay.isRelayPayload(null), "null audio must not be relayed");
        check(AudioRelay.isRelayPayload(new byte[0]), "stop marker must be relayed");

        VoiceMessages.AudioFrame stop = VoiceMessages.decodeAudioFrame(
                VoiceMessages.encodeAudioFrame("backend-b", speaker, false, new byte[0]));
        check(stop.speaker().equals(speaker), "stop-frame speaker changed");
        check(stop.opus().length == 0, "zero-length stop frame was not preserved");

        VoiceMessages.RosterJoin roster = VoiceMessages.decodeRosterJoin(
                VoiceMessages.encodeRosterJoin(UUID.randomUUID(), speaker, "Crabby", "survival"));
        check(roster != null && roster.name().equals("Crabby"),
                "voice roster did not preserve a nickname display label");

        String safeName = CrabVoicechatPlugin.safeRosterName(
                "Crab\0/\\:*?\"<>|\n", "Steve");
        check(safeName.chars().noneMatch(c -> c < 32 || c == 127 || "/\\:*?\"<>|".indexOf(c) >= 0),
                "voice roster nickname retained a delimiter or unsafe filename character");
        String cappedName = CrabVoicechatPlugin.safeRosterName("🦀".repeat(49), "Steve");
        check(cappedName.codePointCount(0, cappedName.length()) == 48,
                "voice roster nickname exceeded the display-name cap");

        verifyCallJoinWireFormat();
        verifyCallRingWireFormat();
        verifyCallRingtoneFrames();
        verifyCallRingtoneTargeting();
        verifyBundledRingtones();
        verifyCallGroupBuilder();
        verifyNativeStopUsesNextSequence();
    }

    private static void verifyCallJoinWireFormat() {
        UUID groupId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String password = "random_secret_1234567890";
        String encoded = VoiceMessages.encodeCallJoin(groupId, playerId, password);
        check(encoded.equals("CALL_JOIN\0" + groupId + "\0" + playerId + "\0" + password),
                "CALL_JOIN wire contract changed");

        VoiceMessages.CallJoin decoded = VoiceMessages.decodeCallJoin(encoded);
        check(decoded != null, "valid CALL_JOIN was rejected");
        check(decoded.groupId().equals(groupId), "CALL_JOIN group changed");
        check(decoded.playerId().equals(playerId), "CALL_JOIN player changed");
        check(decoded.password().equals(password), "CALL_JOIN password changed");
        check(VoiceMessages.callTargetKey(playerId)
                        .equals("crabcraft:svc:call-target:" + playerId),
                "durable call-target key changed");

        check(VoiceMessages.decodeCallJoin("CALL_JOIN\0" + groupId) == null,
                "truncated CALL_JOIN was accepted");
        check(VoiceMessages.decodeCallJoin("OTHER\0" + groupId + "\0" + playerId + "\0x") == null,
                "wrong CALL_JOIN opcode was accepted");
        check(VoiceMessages.decodeCallJoin("CALL_JOIN\0bad\0" + playerId + "\0" + password) == null,
                "invalid CALL_JOIN group UUID was accepted");
        check(VoiceMessages.decodeCallJoin("CALL_JOIN\0" + groupId + "\0bad\0" + password) == null,
                "invalid CALL_JOIN player UUID was accepted");
        check(VoiceMessages.decodeCallJoin("CALL_JOIN\0" + groupId + "\0" + playerId + "\0") == null,
                "blank CALL_JOIN password was accepted");
        check(VoiceMessages.decodeCallJoin(encoded + "\0extra") == null,
                "CALL_JOIN with extra fields was accepted");
        check(VoiceMessages.decodeCallJoin(
                        "CALL_JOIN\0" + groupId + "\0" + playerId + "\0not valid password!!!!") == null,
                "CALL_JOIN with an unsafe password was accepted");
    }

    private static void verifyCallRingWireFormat() {
        String token = "ring_token_1234567890123";
        UUID playerId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        long expiresAt = 123_456_789L;
        String start = VoiceMessages.encodeCallRingStart(token, playerId,
                VoiceMessages.RingDirection.INCOMING, expiresAt);
        check(start.equals("CALL_RING_START\0" + token + "\0" + playerId
                        + "\0INCOMING\0" + expiresAt),
                "CALL_RING_START wire contract changed");
        VoiceMessages.CallRingStart decodedStart = VoiceMessages.decodeCallRingStart(start);
        check(decodedStart != null && decodedStart.token().equals(token)
                        && decodedStart.playerId().equals(playerId)
                        && decodedStart.direction() == VoiceMessages.RingDirection.INCOMING
                        && decodedStart.expiresAtMillis() == expiresAt,
                "CALL_RING_START did not round-trip");

        String stop = VoiceMessages.encodeCallRingStop(token, playerId,
                VoiceMessages.RingDirection.OUTGOING);
        check(stop.equals("CALL_RING_STOP\0" + token + "\0" + playerId + "\0OUTGOING"),
                "CALL_RING_STOP wire contract changed");
        VoiceMessages.CallRingStop decodedStop = VoiceMessages.decodeCallRingStop(stop);
        check(decodedStop != null && decodedStop.token().equals(token)
                        && decodedStop.playerId().equals(playerId)
                        && decodedStop.direction() == VoiceMessages.RingDirection.OUTGOING,
                "CALL_RING_STOP did not round-trip");

        check(VoiceMessages.decodeCallRingStart(start + "\0extra") == null,
                "CALL_RING_START with extra fields was accepted");
        check(VoiceMessages.decodeCallRingStart(
                        "CALL_RING_START\0bad token\0" + playerId + "\0INCOMING\0" + expiresAt) == null,
                "CALL_RING_START with an unsafe token was accepted");
        check(VoiceMessages.decodeCallRingStart(
                        "CALL_RING_START\0" + token + "\0bad\0INCOMING\0" + expiresAt) == null,
                "CALL_RING_START with an invalid UUID was accepted");
        check(VoiceMessages.decodeCallRingStart(
                        "CALL_RING_START\0" + token + "\0" + playerId + "\0incoming\0" + expiresAt) == null,
                "CALL_RING_START with an invalid direction was accepted");
        check(VoiceMessages.decodeCallRingStart(
                        "CALL_RING_START\0" + token + "\0" + playerId + "\0INCOMING\00") == null,
                "CALL_RING_START with a non-positive deadline was accepted");
        check(VoiceMessages.decodeCallRingStop(stop + "\0extra") == null,
                "CALL_RING_STOP with extra fields was accepted");
        check(VoiceMessages.decodeCallRingStop(
                        "CALL_RING_STOP\0" + token + "\0" + playerId + "\0SIDEWAYS") == null,
                "CALL_RING_STOP with an invalid direction was accepted");
    }

    private static void verifyCallRingtoneFrames() {
        AtomicLong now = new AtomicLong(1_000L);
        CallRingtonePlayer.LoopingFrames frames = new CallRingtonePlayer.LoopingFrames(
                new short[]{1, 2, 3}, now::get);
        check(frames.addToken("first", 1_020L), "first ringtone token was rejected");
        check(frames.addToken("second", 1_040L), "second ringtone token was rejected");
        short[] looped = frames.get();
        check(looped.length == CallRingtonePlayer.FRAME_SAMPLES,
                "ringtone frame was not 20 ms long");
        check(looped[0] == 1 && looped[1] == 2 && looped[2] == 3 && looped[3] == 1,
                "short ringtone did not loop");
        check(!frames.removeToken("first"),
                "one outgoing token stopped another pending ringtone");
        check(!frames.removeToken("unknown"),
                "an unknown token stopped ringtone playback");

        now.set(1_039L);
        short[] deadlineFrame = frames.get();
        check(deadlineFrame[47] != 0 && deadlineFrame[48] == 0,
                "final ringtone frame was not silenced at its exact millisecond deadline");
        now.set(1_040L);
        check(frames.get() == null, "ringtone continued at its absolute deadline");
        check(!frames.addToken("too-late", 2_000L),
                "an ended frame source was unexpectedly restarted");

        CallRingtonePlayer.LoopingFrames stopped = new CallRingtonePlayer.LoopingFrames(
                new short[]{1}, now::get);
        stopped.addToken("one", 2_000L);
        stopped.addToken("two", 2_000L);
        check(!stopped.removeToken("one"), "first exact token stopped the shared ringtone");
        check(stopped.removeToken("two"), "last exact token did not stop the ringtone");
        check(stopped.get() == null, "token-stopped ringtone still supplied audio");
    }

    @SuppressWarnings("unchecked")
    private static void verifyCallRingtoneTargeting() {
        UUID playerId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        AtomicLong now = new AtomicLong(10_000L);
        AtomicInteger channelCreates = new AtomicInteger();
        AtomicInteger targetAdds = new AtomicInteger();
        AtomicInteger targetRemoves = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        AtomicInteger encoderCloses = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        AtomicReference<Runnable> latestStoppedCallback = new AtomicReference<>();
        AtomicReference<Supplier<short[]>> latestFrames = new AtomicReference<>();
        AtomicReference<Predicate<ServerPlayer>> filter = new AtomicReference<>();

        ServerPlayer serverPlayer = proxy(ServerPlayer.class, (proxy, method, args) -> {
            if (method.getName().equals("getUuid")) return playerId;
            throw new AssertionError("unexpected server-player call: " + method.getName());
        });
        VoicechatConnection connection = proxy(VoicechatConnection.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "isInstalled", "isConnected" -> true;
                    case "getPlayer" -> serverPlayer;
                    default -> throw new AssertionError(
                            "unexpected ringtone connection call: " + method.getName());
                });
        StaticAudioChannel channel = proxy(StaticAudioChannel.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "addTarget" -> {
                    check(args[0] == connection, "ringtone targeted another voice connection");
                    targetAdds.incrementAndGet();
                }
                case "removeTarget" -> targetRemoves.incrementAndGet();
                case "setFilter" -> filter.set((Predicate<ServerPlayer>) args[0]);
                case "clearTargets" -> clears.incrementAndGet();
                case "setBypassGroupIsolation", "setCategory", "flush" -> { }
                default -> throw new AssertionError(
                        "unexpected ringtone channel call: " + method.getName());
            }
            return null;
        });
        OpusEncoder encoder = proxy(OpusEncoder.class, (proxy, method, args) -> {
            if (method.getName().equals("close")) {
                encoderCloses.incrementAndGet();
                return null;
            }
            throw new AssertionError("unexpected ringtone encoder call: " + method.getName());
        });
        VoicechatServerApi api = proxy(VoicechatServerApi.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getConnectionOf" -> connection;
                    case "createStaticAudioChannel" -> {
                        channelCreates.incrementAndGet();
                        yield channel;
                    }
                    case "createEncoder" -> encoder;
                    case "createAudioPlayer" -> {
                        latestFrames.set((Supplier<short[]>) args[2]);
                        AtomicReference<Runnable> callback = new AtomicReference<>();
                        yield proxy(AudioPlayer.class, (audioProxy, audioMethod, audioArgs) -> {
                            switch (audioMethod.getName()) {
                                case "startPlaying" -> starts.incrementAndGet();
                                case "stopPlaying" -> {
                                    stops.incrementAndGet();
                                    Runnable stopped = callback.get();
                                    if (stopped != null) stopped.run();
                                }
                                case "setOnStopped" -> {
                                    callback.set((Runnable) audioArgs[0]);
                                    latestStoppedCallback.set((Runnable) audioArgs[0]);
                                }
                                default -> throw new AssertionError(
                                        "unexpected ringtone player call: " + audioMethod.getName());
                            }
                            return null;
                        });
                    }
                    default -> throw new AssertionError(
                            "unexpected ringtone API call: " + method.getName());
                });

        CallRingtonePlayer ringtone = new CallRingtonePlayer(api,
                new short[]{1}, new short[]{2}, now::get,
                Logger.getLogger("VoiceRelayRegressionTest"));
        String first = "first_token_123456789012";
        String second = "second_token_12345678901";
        ringtone.start(new VoiceMessages.CallRingStart(first, playerId,
                VoiceMessages.RingDirection.OUTGOING, Long.MAX_VALUE));
        Supplier<short[]> outgoingFrames = latestFrames.get();
        ringtone.start(new VoiceMessages.CallRingStart(second, playerId,
                VoiceMessages.RingDirection.OUTGOING, Long.MAX_VALUE));
        check(channelCreates.get() == 1 && starts.get() == 1,
                "multiple outgoing invites created overlapping playback");
        check(targetAdds.get() == 1 && filter.get().test(serverPlayer),
                "ringtone channel was not restricted to the requested local player");
        ringtone.stop(new VoiceMessages.CallRingStop(first, playerId,
                VoiceMessages.RingDirection.OUTGOING));
        check(stops.get() == 0, "one invite stopped another outgoing ringtone");
        ringtone.stop(new VoiceMessages.CallRingStop("unknown_token_1234567890", playerId,
                VoiceMessages.RingDirection.OUTGOING));
        check(stops.get() == 0, "an unknown invite token stopped a ringtone");

        now.set(39_999L);
        short[] finalFrame = outgoingFrames.get();
        check(finalFrame[47] != 0 && finalFrame[48] == 0,
                "backend did not clamp an excessive deadline to 30 seconds");
        now.set(40_000L);
        check(outgoingFrames.get() == null,
                "clamped ringtone continued beyond 30 seconds");
        latestStoppedCallback.get().run();
        ringtone.start(new VoiceMessages.CallRingStart(first, playerId,
                VoiceMessages.RingDirection.INCOMING, 70_000L));
        ringtone.start(new VoiceMessages.CallRingStart(second, playerId,
                VoiceMessages.RingDirection.OUTGOING, 70_000L));
        check(channelCreates.get() == 2 && starts.get() == 3,
                "incoming and outgoing ringtone sessions were not isolated");
        ringtone.removePlayer(playerId);
        check(stops.get() == 2 && targetRemoves.get() == 2,
                "voice disconnect did not stop both ringtone directions");
        check(encoderCloses.get() == 3 && clears.get() == 2,
                "voice disconnect did not fully release ringtone channels");

        ringtone.start(new VoiceMessages.CallRingStart(first, playerId,
                VoiceMessages.RingDirection.INCOMING, 70_000L));
        check(channelCreates.get() == 3 && starts.get() == 4 && targetAdds.get() == 3,
                "a disconnected player's ringtone channel was retained instead of recreated");
        ringtone.close();
        check(stops.get() == 3 && encoderCloses.get() == 4
                        && targetRemoves.get() == 3 && clears.get() == 3,
                "recreated ringtone channel was not released at shutdown");
    }

    private static void verifyBundledRingtones() throws Exception {
        for (String resource : List.of(
                "crabcraft/call/incoming_ringtone.mp3",
                "crabcraft/call/outgoing_ringtone.mp3")) {
            try (var input = VoiceRelayRegressionTest.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                check(input != null && input.readAllBytes().length > 1_000,
                        "bundled call ringtone is missing or empty: " + resource);
            }
        }
    }

    private static void verifyCallGroupBuilder() {
        UUID groupId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        String password = "builder_secret_123456789";
        Map<String, Object> flags = new HashMap<>();
        List<String> calls = new ArrayList<>();

        Group group = proxy(Group.class, (proxy, method, args) -> {
            throw new AssertionError("unexpected group call: " + method.getName());
        });
        Group.Builder builder = proxy(Group.Builder.class, (proxy, method, args) -> {
            if (method.getName().equals("build")) {
                calls.add("build");
                return group;
            }
            if (method.getName().startsWith("set")) {
                calls.add(method.getName());
                flags.put(method.getName(), args[0]);
                return proxy;
            }
            throw new AssertionError("unexpected builder call: " + method.getName());
        });
        VoicechatServerApi api = proxy(VoicechatServerApi.class, (proxy, method, args) -> {
            if (method.getName().equals("groupBuilder")) return builder;
            throw new AssertionError("unexpected API call: " + method.getName());
        });

        Group built = CrabVoicechatPlugin.buildCallGroup(api, groupId, password);
        check(built == group, "call builder result changed");
        check(flags.get("setId").equals(groupId), "call group did not use the requested ID");
        check(flags.get("setPassword").equals(password), "call group password changed");
        check(flags.get("setType") == Group.Type.OPEN, "call group was not OPEN");
        check(Boolean.TRUE.equals(flags.get("setHidden")), "call group was not hidden");
        check(Boolean.FALSE.equals(flags.get("setPersistent")), "call group was persistent");
        String name = (String) flags.get("setName");
        check(name.length() <= 16, "call group name exceeded 16 characters");
        check(name.matches("[A-Za-z0-9 ]+"), "call group name contained unsafe characters");
        check(calls.equals(List.of("setId", "setName", "setPassword", "setType",
                        "setHidden", "setPersistent", "build")),
                "call builder flags or order changed: " + calls);
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
