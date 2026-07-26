package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;

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
