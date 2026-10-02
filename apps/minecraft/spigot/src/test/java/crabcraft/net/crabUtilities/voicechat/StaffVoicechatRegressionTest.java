package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class StaffVoicechatRegressionTest {

    public static void main(String[] args) throws Exception {
        verifyPrivateGroup();
        verifyCommandGates();
        verifyToggleAndCancelledChanges();
        verifyGroupEntryGates();
        verifyRegistration();
    }

    private static void verifyPrivateGroup() {
        Map<String, Object> settings = new HashMap<>();
        Group.Builder builder = proxy(Group.Builder.class, (instance, method, arguments) -> {
            if (method.getName().equals("build")) return group((UUID) settings.get("setId"));
            settings.put(method.getName(), arguments[0]);
            return instance;
        });
        VoicechatServerApi api = proxy(VoicechatServerApi.class, (instance, method, arguments) -> {
            if (method.getName().equals("groupBuilder")) return builder;
            throw new AssertionError("unexpected API call: " + method.getName());
        });
        Group first = StaffVoicechatCommand.createGroup(api);
        Group second = StaffVoicechatCommand.createGroup(api);
        check(first.getId().equals(second.getId()), "staff group ID changed between backends");
        check(settings.get("setType") == Group.Type.ISOLATED, "staff group can hear proximity voice");
        check(Boolean.TRUE.equals(settings.get("setHidden")), "staff group is listed publicly");
        check(Boolean.TRUE.equals(settings.get("setPersistent")), "empty staff group would disappear");

        VoiceMessages.GroupDefinition definition = new VoiceMessages.GroupDefinition(
                first.getId(), "Staff VC", null, Group.Type.ISOLATED, true, true);
        check(GroupSynchronizer.isHiddenLocally(definition), "synced staff group is listed publicly");
    }

    private static void verifyCommandGates() {
        Fixture fixture = new Fixture();
        fixture.permitted = false;
        fixture.command().onCommand(fixture.player, null, "staffvc", new String[0]);
        check(fixture.apiReads == 0 && fixture.currentGroup == null,
                "unauthorised command accessed voice chat");
        check(fixture.lastMessage().contains("permission"), "permission denial was not reported");

        CommandSender console = proxy(CommandSender.class, (instance, method, arguments) -> switch (method.getName()) {
            case "hasPermission" -> true;
            case "sendMessage" -> null;
            default -> throw new AssertionError("unexpected console call: " + method.getName());
        });
        fixture.command().onCommand(console, null, "staffvc", new String[0]);
        check(fixture.apiReads == 0, "console command accessed a player connection");

        fixture.permitted = true;
        check(!fixture.command().onCommand(fixture.player, null, "staffvc", new String[]{"someone"}),
                "unexpected arguments were accepted");
        check(fixture.apiReads == 0, "invalid arguments changed a voice connection");

        new StaffVoicechatCommand(() -> null).onCommand(fixture.player, null, "staffvc", new String[0]);
        check(fixture.lastMessage().contains("unavailable"), "missing runtime was not reported");
        fixture.connected = false;
        fixture.run();
        check(fixture.currentGroup == null && fixture.lastMessage().contains("Connect"),
                "disconnected player joined staff voice chat");
        fixture.connected = true;
        fixture.groupAvailable = false;
        fixture.run();
        check(fixture.currentGroup == null && fixture.lastMessage().contains("unavailable"),
                "missing staff group was reported as a successful join");
    }

    private static void verifyToggleAndCancelledChanges() {
        Fixture fixture = new Fixture();
        Group previous = group(UUID.randomUUID());
        fixture.currentGroup = previous;
        fixture.acceptChanges = false;
        fixture.run();
        check(fixture.currentGroup == previous && fixture.lastMessage().contains("could not"),
                "cancelled join was reported as successful");

        fixture.acceptChanges = true;
        fixture.run();
        check(fixture.currentGroup == fixture.staffGroup && fixture.lastMessage().startsWith("Joined"),
                "staff member was not moved into the shared group");
        fixture.acceptChanges = false;
        fixture.run();
        check(fixture.currentGroup == fixture.staffGroup && fixture.lastMessage().contains("could not"),
                "cancelled leave was reported as successful");

        fixture.acceptChanges = true;
        fixture.run();
        check(fixture.currentGroup == null && fixture.lastMessage().startsWith("Left"),
                "second invocation did not leave staff voice chat");
        check(fixture.command().onTabComplete(fixture.player, null, "staffvc", new String[]{""}).isEmpty(),
                "command suggested unrelated player names");
    }

    private static void verifyGroupEntryGates() {
        Fixture fixture = new Fixture();
        fixture.permitted = false;
        check(!StaffVoicechatCommand.canJoin(fixture.player, StaffVoicechatCommand.GROUP_ID),
                "permission removal did not block automatic staff rejoining");
        check(guardCancelled(fixture.staffGroup, fixture.connection()),
                "invite or direct group join bypassed the staff permission");
        check(guardCancelled(fixture.staffGroup, null), "unknown connection could join staff group");
        check(!guardCancelled(group(UUID.randomUUID()), fixture.connection()),
                "ordinary voice group was restricted to staff");

        fixture.permitted = true;
        check(!guardCancelled(fixture.staffGroup, fixture.connection()), "staff group entry was blocked");
        check(StaffVoicechatCommand.canJoin(fixture.player, StaffVoicechatCommand.GROUP_ID),
                "authorised staff cannot rejoin after a server switch");
    }

    private static boolean guardCancelled(Group group, VoicechatConnection connection) {
        AtomicBoolean cancelled = new AtomicBoolean();
        JoinGroupEvent event = proxy(JoinGroupEvent.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getGroup" -> group;
            case "getConnection" -> connection;
            case "isCancelled" -> cancelled.get();
            case "cancel" -> { cancelled.set(true); yield true; }
            default -> throw new AssertionError("unexpected event call: " + method.getName());
        });
        StaffVoicechatCommand.guardGroupEntry(event);
        return cancelled.get();
    }

    private static void verifyRegistration() throws Exception {
        try (var input = StaffVoicechatRegressionTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            check(input != null, "plugin.yml missing");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            check(StaffVoicechatCommand.PERMISSION.equals(yaml.getString("commands.staffvc.permission")),
                    "staffvc command permission not registered");
            check("op".equals(yaml.getString("permissions.crabutilities.staffvc.default")),
                    "staffvc permission must default to operators only");
        }
    }

    private static final class Fixture {
        final UUID playerId = UUID.randomUUID();
        final Group staffGroup = group(StaffVoicechatCommand.GROUP_ID);
        final List<String> messages = new ArrayList<>();
        Group currentGroup;
        boolean permitted = true;
        boolean connected = true;
        boolean acceptChanges = true;
        boolean groupAvailable = true;
        int apiReads;
        final Player player = proxy(Player.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "hasPermission" -> {
                check(StaffVoicechatCommand.PERMISSION.equals(arguments[0]), "wrong staff permission checked");
                yield permitted;
            }
            case "sendMessage" -> {
                messages.add(PlainTextComponentSerializer.plainText().serialize((Component) arguments[0]));
                yield null;
            }
            default -> throw new AssertionError("unexpected player call: " + method.getName());
        });
        final VoicechatServerApi api = proxy(VoicechatServerApi.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getConnectionOf" -> { apiReads++; yield connection(); }
            case "getGroup" -> groupAvailable ? staffGroup : null;
            default -> throw new AssertionError("unexpected API call: " + method.getName());
        });

        StaffVoicechatCommand command() {
            return new StaffVoicechatCommand(() -> api);
        }

        void run() {
            check(command().onCommand(player, null, "staffvc", new String[0]), "command was not handled");
        }

        String lastMessage() {
            return messages.getLast();
        }

        VoicechatConnection connection() {
            Group snapshot = currentGroup;
            ServerPlayer serverPlayer = proxy(ServerPlayer.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getPlayer" -> player;
                default -> throw new AssertionError("unexpected server player call: " + method.getName());
            });
            return proxy(VoicechatConnection.class, (instance, method, arguments) -> switch (method.getName()) {
                case "getGroup" -> snapshot;
                case "getPlayer" -> serverPlayer;
                case "isConnected" -> connected;
                case "setGroup" -> {
                    if (acceptChanges) currentGroup = (Group) arguments[0];
                    yield null;
                }
                default -> throw new AssertionError("unexpected connection call: " + method.getName());
            });
        }
    }

    private static Group group(UUID id) {
        return proxy(Group.class, (instance, method, arguments) -> {
            if (method.getName().equals("getId")) return id;
            throw new AssertionError("unexpected group call: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
