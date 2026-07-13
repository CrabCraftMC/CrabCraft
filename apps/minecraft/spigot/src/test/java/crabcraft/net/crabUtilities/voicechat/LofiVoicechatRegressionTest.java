package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import net.crabcraft.customdiscs.Keys;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

final class LofiVoicechatRegressionTest {
    public static void main(String[] args) throws Exception {
        verifyGroupSelection();
        verifySpeechRoutingSelection();
        verifyVolumeScaling();
        verifyLofiChannelTargets();
        verifyDefaultsAndLegacyNamespace();
    }

    private static void verifyGroupSelection() {
        List<String> enabled = CrabVoicechatPlugin.persistentGroupNames(List.of("Global #1"), true);
        check(enabled.equals(List.of("Global #1", "24/7 Lofi")),
                "enabled lofi group was not added exactly once");
        List<String> disabled = CrabVoicechatPlugin.persistentGroupNames(
                List.of("Global #1", "24/7 Lofi"), false);
        check(disabled.equals(List.of("Global #1")), "disabled lofi group was still created");

        UUID first = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi");
        UUID second = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi");
        check(first.equals(second), "lofi group ID was not deterministic across backends");
    }

    private static void verifySpeechRoutingSelection() {
        UUID lofiId = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi");
        Group lofiGroup = proxy(Group.class, (proxy, method, args) -> switch (method.getName()) {
            case "getId" -> lofiId;
            default -> throw new AssertionError("unexpected group call: " + method.getName());
        });
        VoicechatConnection sender = proxy(VoicechatConnection.class,
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGroup" -> lofiGroup;
                    default -> throw new AssertionError("unexpected connection call: " + method.getName());
                });

        check(GroupSpeechAttenuator.shouldAttenuate(SoundPacketEvent.SOURCE_GROUP, lofiId, sender),
                "lofi group speech was not selected for attenuation");
        check(!GroupSpeechAttenuator.shouldAttenuate(SoundPacketEvent.SOURCE_PROXIMITY, lofiId, sender),
                "proximity speech was incorrectly selected for attenuation");
        check(!GroupSpeechAttenuator.shouldAttenuate(
                        SoundPacketEvent.SOURCE_GROUP, UUID.randomUUID(), sender),
                "another group was incorrectly selected for attenuation");
    }

    private static void verifyVolumeScaling() {
        short[] speech = {10_000, -10_000, Short.MAX_VALUE, Short.MIN_VALUE};
        OpusVolumeScaler.applyGain(speech, 0.25D);
        check(speech[0] == 2_500 && speech[1] == -2_500,
                "75% speech reduction did not produce a 25% signal");
        check(speech[2] == 8_192 && speech[3] == -8_192,
                "speech volume scaling changed endpoint rounding");
    }

    private static void verifyLofiChannelTargets() {
        UUID lofiId = CrabVoicechatPlugin.deterministicGroupId("24/7 Lofi");
        AtomicInteger added = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger cleared = new AtomicInteger();

        StaticAudioChannel channel = proxy(StaticAudioChannel.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "addTarget" -> added.incrementAndGet();
                case "removeTarget" -> removed.incrementAndGet();
                case "clearTargets" -> cleared.incrementAndGet();
                case "setBypassGroupIsolation", "setCategory", "setFilter", "flush" -> { }
                case "bypassesGroupIsolation" -> { return true; }
                default -> throw new AssertionError("unexpected channel call: " + method.getName());
            }
            return null;
        });
        VoicechatServerApi api = proxy(VoicechatServerApi.class, (proxy, method, args) -> {
            if (method.getName().equals("createStaticAudioChannel") && args.length == 1) return channel;
            throw new AssertionError("unexpected API call: " + method.getName());
        });

        UUID playerId = UUID.randomUUID();
        Group lofiGroup = group(lofiId);
        VoicechatConnection connection = connection(playerId, lofiGroup);
        LofiStreamPlayer player = new LofiStreamPlayer(
                api, lofiId, "https://example.invalid/live", 0.5F,
                Logger.getLogger("LofiVoicechatRegressionTest"));

        check(player.openChannel(), "lofi static channel was not opened");
        player.reconcileTarget(connection);
        check(added.get() == 1, "local lofi member was not registered as an audio target");

        player.updateTarget(connection, UUID.randomUUID());
        check(removed.get() == 1, "player leaving lofi was not removed as an audio target");

        player.updateTarget(connection, lofiId);
        player.removeTarget(playerId);
        check(added.get() == 2 && removed.get() == 2,
                "disconnect did not remove the tracked lofi audio target");

        player.close();
        check(cleared.get() == 1, "lofi targets were not cleared during shutdown");
    }

    private static Group group(UUID id) {
        return proxy(Group.class, (proxy, method, args) -> {
            if (method.getName().equals("getId")) return id;
            throw new AssertionError("unexpected group call: " + method.getName());
        });
    }

    private static VoicechatConnection connection(UUID playerId, Group group) {
        ServerPlayer serverPlayer = proxy(ServerPlayer.class, (proxy, method, args) -> {
            if (method.getName().equals("getUuid")) return playerId;
            throw new AssertionError("unexpected player call: " + method.getName());
        });
        return proxy(VoicechatConnection.class, (proxy, method, args) -> switch (method.getName()) {
            case "getPlayer" -> serverPlayer;
            case "getGroup" -> group;
            default -> throw new AssertionError("unexpected connection call: " + method.getName());
        });
    }

    private static void verifyDefaultsAndLegacyNamespace() throws Exception {
        String config;
        try (var input = LofiVoicechatRegressionTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            check(input != null, "bundled config.yml is missing");
            config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        check(config.contains("https://www.youtube.com/watch?v=8TIVlFtcRDU"),
                "configured YouTube Live default changed");
        check(config.contains("music-volume: 0.5") && config.contains("player-volume: 0.25"),
                "lofi volume defaults changed");
        check(Keys.REMOTE_DISC.key().getNamespace().equals("customdiscs"),
                "legacy custom-disc item namespace changed during the merge");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
