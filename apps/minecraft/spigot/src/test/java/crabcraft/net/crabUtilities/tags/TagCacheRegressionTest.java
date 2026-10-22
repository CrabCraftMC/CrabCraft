package crabcraft.net.crabUtilities.tags;

import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TagCacheRegressionTest {
    private static final TagDefinition PUMPKIN = new TagDefinition("halloween-2026-pumpkin", "Halloween 2026",
            "Complete all three challenges.", "pumpkin", 10, true);

    public static void main(String[] args) throws Exception {
        UUID player = UUID.randomUUID();
        TagCache cache = new TagCache();
        check(cache.placeholder(player).isEmpty(), "unloaded placeholder was not exactly empty");
        cache.accept(new TagSnapshot(player, 0, null, List.of()), Map.of());
        check(cache.placeholder(player).length() == 0, "available empty collection gained padding");

        cache.accept(new TagSnapshot(player, 1, PUMPKIN.id(), List.of(PUMPKIN)), Map.of());
        check(cache.placeholder(player).isEmpty(), "missing local glyph leaked a raw tag");
        Map<String, Component> icons = new HashMap<>();
        icons.put(PUMPKIN.id(), Component.text("ꔌ"));
        cache.accept(new TagSnapshot(player, 1, PUMPKIN.id(), List.of(PUMPKIN)), icons);
        icons.clear();
        check("<white><glyph:pumpkin></white>".equals(cache.placeholder(player)),
                "selected placeholder changed, lost its glyph, or gained padding");

        cache.accept(new TagSnapshot(player, 0, null, List.of()), Map.of());
        check(cache.get(player).snapshot().revision() == 1, "late stale snapshot overwrote newer selection");
        TagDefinition disabled = new TagDefinition(PUMPKIN.id(), PUMPKIN.name(), PUMPKIN.description(),
                PUMPKIN.glyphId(), PUMPKIN.sortOrder(), false);
        cache.accept(new TagSnapshot(player, 1, PUMPKIN.id(), List.of(disabled)),
                Map.of(PUMPKIN.id(), Component.text("ꔌ")));
        check(cache.placeholder(player).isEmpty(), "equal-revision catalogue disable did not hide icon");
        check(PUMPKIN.id().equals(cache.get(player).snapshot().selectedTagId()), "disabled selection was lost");
        cache.accept(new TagSnapshot(player, 1, PUMPKIN.id(), List.of(PUMPKIN)),
                Map.of(PUMPKIN.id(), Component.text("ꔌ")));
        check(!cache.placeholder(player).isEmpty(), "re-enabled dormant selection did not recover");

        cache.accept(new TagSnapshot(player, 2, null, List.of(PUMPKIN)), Map.of(PUMPKIN.id(), Component.text("ꔌ")));
        check(cache.placeholder(player).length() == 0, "No tag retained formatting or an invisible separator");
        check(cache.get(player).snapshot().ownedTags().size() == 1, "clearing selection removed ownership");
        cache.remove(player);
        check(cache.placeholder(player).isEmpty(), "quit eviction retained a previous session's icon");

        Thread[] updates = new Thread[16];
        for (int i = 0; i < updates.length; i++) {
            long revision = i;
            updates[i] = new Thread(() -> cache.accept(new TagSnapshot(player, revision, null, List.of()), Map.of()));
            updates[i].start();
        }
        for (Thread update : updates) update.join();
        check(cache.get(player).snapshot().revision() == 15, "concurrent refreshes lost the newest revision");
        cache.clear();
        check(cache.get(player) == null, "shutdown retained cached tags");
        check(!TagCache.validGlyphId("pumpkin><red>") && !TagCache.validGlyphId("namespace:pumpkin"),
                "glyph arguments allowed MiniMessage syntax");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
