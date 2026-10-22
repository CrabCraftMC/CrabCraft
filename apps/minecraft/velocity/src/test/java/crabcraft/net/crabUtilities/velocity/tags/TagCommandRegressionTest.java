package crabcraft.net.crabUtilities.velocity.tags;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class TagCommandRegressionTest {
    public static void main(String[] args) {
        permissionsAreOperationSpecific();
        onlyCanonicalUuidsAndExactOnlineNamesResolve();
        auditReasonsAreBounded();
    }

    private static void permissionsAreOperationSpecific() {
        var tree = TagCommand.tree(null).build();
        CommandSource ordinary = source(Set.of());
        CommandSource inspector = source(Set.of("crabutilities.tags.inspect"));
        CommandSource grant = source(Set.of("crabutilities.tags.grant"));
        CommandSource revoke = source(Set.of("crabutilities.tags.revoke"));
        check(!tree.canUse(ordinary), "ordinary players can access staff tag tools");
        check(tree.canUse(inspector) && tree.getChild("inspect").canUse(inspector), "inspection permission cannot reach inspect");
        check(!tree.getChild("grant").canUse(inspector) && !tree.getChild("revoke").canUse(inspector),
                "inspection permission allows ownership mutations");
        check(tree.canUse(grant) && tree.getChild("grant").canUse(grant)
                && !tree.getChild("revoke").canUse(grant), "grant permission is not isolated");
        check(tree.canUse(revoke) && tree.getChild("revoke").canUse(revoke)
                && !tree.getChild("grant").canUse(revoke), "revoke permission is not isolated");
        check(tree.getChildren().size() == 3, "unexpected staff tag operations were added");
    }

    private static void onlyCanonicalUuidsAndExactOnlineNamesResolve() {
        UUID id = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        Player online = mock(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUsername" -> "ExamplePlayer";
            case "getUniqueId" -> id;
            default -> null;
        });
        ProxyServer server = mock(ProxyServer.class, (proxy, method, args) ->
                method.getName().equals("getAllPlayers") ? List.of(online) : null);
        check(TagCommand.resolve(server, "exampleplayer").orElseThrow().equals(id), "exact online name did not resolve");
        check(TagCommand.resolve(server, "Example").isEmpty(), "a partial name was used as an identity");
        check(TagCommand.resolve(server, "Nickname").isEmpty(), "an unknown nickname was used as an identity");
        check(TagCommand.resolve(server, offline.toString()).orElseThrow().equals(offline), "offline UUID did not resolve");
        check(TagCommand.resolve(server, "1-1-1-1-1").isEmpty(), "abbreviated Java UUID was accepted");
    }

    private static void auditReasonsAreBounded() {
        check(TagCommand.validReason("Completed the event"), "normal audit reason was rejected");
        check(TagCommand.validReason("a".repeat(512)), "reason boundary was rejected");
        check(!TagCommand.validReason("a".repeat(513)), "unbounded reason was accepted");
        check(!TagCommand.validReason(" ") && !TagCommand.validReason(""), "empty audit reason was accepted");
        check(!TagCommand.validReason("one\ntwo") && !TagCommand.validReason("one\u0000two"), "control characters entered the audit log");
    }

    private static CommandSource source(Set<String> permissions) {
        return mock(CommandSource.class, (proxy, method, args) ->
                method.getName().equals("hasPermission") ? permissions.contains(args[0]) : null);
    }

    private static <T> T mock(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
