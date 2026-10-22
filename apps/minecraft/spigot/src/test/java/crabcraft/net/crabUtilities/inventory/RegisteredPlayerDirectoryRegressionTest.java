package crabcraft.net.crabUtilities.inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class RegisteredPlayerDirectoryRegressionTest {

    public static void main(String[] args) {
        UUID mainAccount = UUID.randomUUID();
        UUID altAccount = UUID.randomUUID();
        Map<String, String> registered = new HashMap<>();
        registered.put(mainAccount.toString(), "Zebra");
        registered.put(altAccount.toString(), "alice_alt");
        registered.put("invalid-uuid", "Broken");
        registered.put(UUID.randomUUID().toString(), "");
        registered.put(UUID.randomUUID().toString(), null);

        List<RegisteredPlayerDirectory.Entry> entries = RegisteredPlayerDirectory.parseEntries(registered);
        check(entries.size() == 2, "invalid cache entries should not hide registered players");
        check(RegisteredPlayerDirectory.find(entries, "ALICE_ALT").orElseThrow().uuid().equals(altAccount),
                "offline alt lookup must use the registered UUID and ignore name case");
        check(RegisteredPlayerDirectory.find(entries, "alice").isEmpty(), "lookup must require the full name");
        check(RegisteredPlayerDirectory.names(entries, "").equals(List.of("alice_alt", "Zebra")),
                "completion must include all registered accounts in name order");
        check(RegisteredPlayerDirectory.names(entries, "AL").equals(List.of("alice_alt")),
                "completion must filter prefixes without case sensitivity");

        registered.put(UUID.randomUUID().toString(), "ALICE_ALT");
        List<RegisteredPlayerDirectory.Entry> ambiguous = RegisteredPlayerDirectory.parseEntries(registered);
        check(RegisteredPlayerDirectory.find(ambiguous, "alice_alt").isEmpty(),
                "a stale duplicate username must not resolve to an arbitrary inventory");
        check(RegisteredPlayerDirectory.names(ambiguous, "al").size() == 1,
                "completion must not repeat names differing only in case");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
