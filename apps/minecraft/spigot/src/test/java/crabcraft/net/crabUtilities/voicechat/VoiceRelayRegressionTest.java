package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

final class VoiceRelayRegressionTest {

    public static void main(String[] args) {
        UUID speaker = UUID.fromString("11111111-1111-1111-1111-111111111111");

        check(!AudioRelay.isRelayPayload(null), "null audio must not be relayed");
        check(AudioRelay.isRelayPayload(new byte[0]), "stop marker must be relayed");

        String route = "backend-b\0proxy:7";
        VoiceMessages.AudioFrame stop = VoiceMessages.decodeAudioFrame(
                VoiceMessages.encodeAudioFrame(route, speaker, false, new byte[0]));
        check(stop.speaker().equals(speaker), "stop-frame speaker changed");
        check(stop.opus().length == 0, "zero-length stop frame was not preserved");
        check(route.equals(stop.route()), "audio frame lost its hop token");
        check("backend-b".equals(VoiceMessages.routeBackend(stop.route())),
                "route backend decoding changed");

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

        verifyGroupDefinitionRoundTrip();
        verifyPasswordAccess();
        verifyMembershipJoinReplacesPreviousGroup();
        verifySequenceResetUsesNextSequence();
        verifyPrivateCallProtocol();
        verifyPrivateCallDefinition();
        verifyRingtoneFrames();
        verifyRingtoneResources();
    }

    private static void verifyGroupDefinitionRoundTrip() {
        for (Group.Type type : List.of(
                Group.Type.NORMAL, Group.Type.OPEN, Group.Type.ISOLATED)) {
            VoiceMessages.GroupDefinition expected = new VoiceMessages.GroupDefinition(
                    UUID.randomUUID(), "Crab Group", "secret\0password", type, true, false);
            String encoded = VoiceMessages.encodeGroupDefinition(expected);
            VoiceMessages.GroupDefinition actual =
                    VoiceMessages.decodeGroupDefinition(expected.id(), encoded);

            check(expected.equals(actual), "group definition changed during Redis round-trip");
            check(!VoiceMessages.encodeGroupChanged(expected.id()).contains(expected.password()),
                    "group invalidation exposed its password");
        }
    }

    private static void verifyPasswordAccess() {
        UUID groupId = UUID.randomUUID();
        try {
            check("crab-secret".equals(GroupSynchronizer.passwordOf(
                            new TestGroup(groupId, "crab-secret"))),
                    "password compatibility bridge did not read the SVC backing group");
            check(GroupSynchronizer.passwordOf(new TestGroup(groupId, null)) == null,
                    "password compatibility bridge invented an unprotected password");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("password compatibility bridge failed", e);
        }
    }

    private static void verifyMembershipJoinReplacesPreviousGroup() {
        MembershipTracker membership = new MembershipTracker();
        UUID playerId = UUID.randomUUID();
        UUID firstGroupId = UUID.randomUUID();
        UUID secondGroupId = UUID.randomUUID();

        membership.setLocalGroup(playerId, firstGroupId);
        membership.setLocalGroup(playerId, secondGroupId);

        check(membership.getLocalMembers(firstGroupId).isEmpty(),
                "join without leave retained the player's previous group");
        check(secondGroupId.equals(membership.getLocalGroupOf(playerId)),
                "player was not tracked in exactly the latest group");
    }

    private static void verifySequenceResetUsesNextSequence() {
        UUID channelId = UUID.randomUUID();
        try {
            StaticSoundPacket reset = AudioRelay.nextSequenceStop(
                    staticPacket(channelId, 41L, new byte[]{1, 2, 3}));
            check(reset.getSequenceNumber() == 42L,
                    "native stop did not use the next sequence");
            check(reset.getOpusEncodedData().length == 0,
                    "native stop retained audio data");
            check(channelId.equals(reset.getChannelId()),
                    "native stop changed the speaker channel");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("native stop packet could not be built", e);
        }
    }

    private static void verifyPrivateCallProtocol() {
        UUID groupId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        String token = "Abcdefghijklmnopqrstuvwx";

        String callJoinWire = VoiceMessages.encodeCallJoin(groupId, playerId, token);
        VoiceMessages.CallJoin callJoin = VoiceMessages.decodeCallJoin(callJoinWire);
        check(callJoin != null && groupId.equals(callJoin.groupId())
                        && playerId.equals(callJoin.playerId())
                        && token.equals(callJoin.generation()),
                "password-free call join did not round-trip");
        check(callJoinWire.split(VoiceMessages.SEP, -1).length == 4,
                "call join unexpectedly carries another field");
        check(VoiceMessages.decodeCallJoin(callJoinWire + VoiceMessages.SEP + "secret") == null,
                "call join accepted a password field");
        check(!callJoinWire.contains("random-secret"),
                "call join exposed a group password");
        check(VoiceMessages.decodeCallJoin(String.join(VoiceMessages.SEP,
                VoiceMessages.OP_CALL_JOIN, "0-0-0-0-0", playerId.toString(), token)) == null,
                "call join accepted a non-canonical UUID");

        VoiceMessages.CallTarget target = new VoiceMessages.CallTarget(groupId, token);
        check(target.equals(VoiceMessages.decodeCallTarget(
                        VoiceMessages.encodeCallTarget(target))),
                "durable call target generation did not round-trip");
        check(VoiceMessages.decodeCallTarget(groupId + "\0short") == null,
                "call target accepted a weak generation token");

        VoiceMessages.CallTarget oldTarget = new VoiceMessages.CallTarget(
                groupId, "abcdefghijklmnopqrstuv");
        VoiceMessages.CallTarget newTarget = new VoiceMessages.CallTarget(
                groupId, "zyxwvutsrqponmlkjihgfe");
        check(CallTargetSynchronizer.isNewAcceptedTarget(
                        newTarget, newTarget, oldTarget),
                "a newly accepted generation did not supersede an old manual suppression");
        check(!CallTargetSynchronizer.isNewAcceptedTarget(
                        oldTarget, oldTarget, oldTarget),
                "a delayed old hint superseded its manual suppression");
        check(CallTargetSynchronizer.manualSuppressionTarget(null, oldTarget).equals(oldTarget),
                "a pending call hint survived a manual group choice");
        check(CallTargetSynchronizer.manualSuppressionTarget(oldTarget, newTarget).equals(newTarget),
                "manual suppression did not prefer the latest pending generation");

        VoiceMessages.CallRingStart start = VoiceMessages.decodeCallRingStart(
                VoiceMessages.encodeCallRingStart(token, playerId,
                        VoiceMessages.RingDirection.INCOMING, 30_000L));
        check(start != null && start.expiresAtMillis() == 30_000L,
                "ring start did not preserve its absolute deadline");
        check(VoiceMessages.decodeCallRingStart(String.join(VoiceMessages.SEP,
                VoiceMessages.OP_CALL_RING_START, token, playerId.toString(),
                "INCOMING", "030000")) == null,
                "ring start accepted a non-canonical deadline");
        VoiceMessages.CallRingStop stop = VoiceMessages.decodeCallRingStop(
                VoiceMessages.encodeCallRingStop(token, playerId,
                        VoiceMessages.RingDirection.OUTGOING));
        check(stop != null && stop.direction() == VoiceMessages.RingDirection.OUTGOING,
                "ring stop did not preserve its direction");
    }

    private static void verifyPrivateCallDefinition() {
        VoiceMessages.GroupDefinition call = new VoiceMessages.GroupDefinition(
                UUID.randomUUID(), CallTargetSynchronizer.CALL_GROUP_NAME, "random-secret",
                Group.Type.OPEN, true, false);
        check(CallTargetSynchronizer.isCallGroup(call),
                "authoritative private call definition was rejected");
        check(!CallTargetSynchronizer.isCallGroup(new VoiceMessages.GroupDefinition(
                        call.id(), call.name(), call.password(), Group.Type.NORMAL, true, false)),
                "non-OPEN group was accepted as a private call");
        check(!CallTargetSynchronizer.isCallGroup(new VoiceMessages.GroupDefinition(
                        call.id(), call.name(), call.password(), Group.Type.OPEN, false, false)),
                "visible group was accepted as a private call");
        check(!CallTargetSynchronizer.isCallGroup(new VoiceMessages.GroupDefinition(
                        call.id(), call.name(), null, Group.Type.OPEN, true, false)),
                "passwordless group was accepted as a private call");
    }

    private static void verifyRingtoneFrames() {
        check(CallRingtonePlayer.RINGTONE_GAIN == 0.5D,
                "ringtone baseline volume is not halved");
        long[] now = {1_000L};
        CallRingtonePlayer.LoopingFrames partial = new CallRingtonePlayer.LoopingFrames(
                new short[]{1, 2}, () -> now[0]);
        partial.addToken("abcdefghijklmnopqrstuv", 1_010L);
        short[] finalFrame = partial.get();
        check(finalFrame != null && finalFrame.length == CallRingtonePlayer.FRAME_SAMPLES,
                "ringtone final frame had the wrong size");
        check(finalFrame[479] != 0 && finalFrame[480] == 0,
                "ringtone did not silence the exact partial frame at its deadline");
        now[0] = 1_010L;
        check(partial.get() == null, "ringtone continued at its absolute deadline");

        now[0] = 2_000L;
        CallRingtonePlayer.LoopingFrames shared = new CallRingtonePlayer.LoopingFrames(
                new short[]{1}, () -> now[0]);
        shared.addToken("abcdefghijklmnopqrstuv", 2_100L);
        shared.addToken("zyxwvutsrqponmlkjihgfe", 2_200L);
        check(!shared.removeToken("abcdefghijklmnopqrstuv"),
                "one invitation stopped another invitation's ringtone");
        check(shared.get() != null, "remaining invitation did not keep ringing");
        check(shared.removeToken("zyxwvutsrqponmlkjihgfe"),
                "last invitation did not stop its ringtone");
    }

    private static void verifyRingtoneResources() {
        check(VoiceMediaRegistry.CALL_CATEGORY.equals("crabcraft_calls"),
                "call volume category changed");
        for (String resource : List.of(
                "crabcraft/call/incoming_ringtone.mp3",
                "crabcraft/call/outgoing_ringtone.mp3")) {
            try (InputStream input = VoiceRelayRegressionTest.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                check(input != null, "missing bundled ringtone " + resource);
            } catch (Exception e) {
                throw new AssertionError("could not read bundled ringtone " + resource, e);
            }
        }
    }

    private static StaticSoundPacket staticPacket(
            UUID channelId, long sequenceNumber, byte[] opus) {
        return proxy(StaticSoundPacket.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getChannelId", "getSender" -> channelId;
                    case "getSequenceNumber" -> sequenceNumber;
                    case "getOpusEncodedData" -> opus;
                    case "getCategory" -> null;
                    case "staticSoundPacketBuilder" ->
                            new TestStaticBuilder(channelId, sequenceNumber, opus);
                    case "toStaticSoundPacket" -> proxy;
                    default -> throw new AssertionError(
                            "unexpected static packet call: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class TestGroup implements Group {
        private final UUID id;
        private final BackingGroup group;

        private TestGroup(UUID id, String password) {
            this.id = id;
            this.group = new BackingGroup(password);
        }

        public BackingGroup getGroup() {
            return group;
        }

        @Override
        public String getName() {
            return "Test";
        }

        @Override
        public boolean hasPassword() {
            return group.getPassword() != null;
        }

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public boolean isPersistent() {
            return false;
        }

        @Override
        public boolean isHidden() {
            return false;
        }

        @Override
        public Type getType() {
            return Type.NORMAL;
        }
    }

    public static final class BackingGroup {
        private final String password;

        private BackingGroup(String password) {
            this.password = password;
        }

        public String getPassword() {
            return password;
        }
    }

    private static final class TestStaticBuilder
            implements StaticSoundPacket.Builder<TestStaticBuilder> {
        private UUID channelId;
        private byte[] opus;
        @SuppressWarnings("unused")
        private long sequenceNumber;

        private TestStaticBuilder(UUID channelId, long sequenceNumber, byte[] opus) {
            this.channelId = channelId;
            this.sequenceNumber = sequenceNumber;
            this.opus = opus;
        }

        @Override
        public TestStaticBuilder channelId(UUID channelId) {
            this.channelId = channelId;
            return this;
        }

        @Override
        public TestStaticBuilder opusEncodedData(byte[] opus) {
            this.opus = opus;
            return this;
        }

        @Override
        public TestStaticBuilder category(String category) {
            return this;
        }

        @Override
        public StaticSoundPacket build() {
            return staticPacket(channelId, sequenceNumber, opus);
        }
    }
}
