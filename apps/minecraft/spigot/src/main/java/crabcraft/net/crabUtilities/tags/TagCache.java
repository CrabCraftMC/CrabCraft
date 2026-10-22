package crabcraft.net.crabUtilities.tags;

import net.kyori.adventure.text.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Immutable, memory-only state shared by the picker and PlaceholderAPI. */
public final class TagCache {

    private static final Pattern GLYPH_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,127}");
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public record Entry(TagSnapshot snapshot, Map<String, Component> icons) {
        public Entry {
            icons = Map.copyOf(icons);
        }

        public String placeholder() {
            TagDefinition selected = snapshot.selectedTag();
            if (selected == null || !selected.enabled() || !validGlyphId(selected.glyphId())
                    || !icons.containsKey(selected.id())) return "";
            return "<white><glyph:" + selected.glyphId() + "></white>";
        }
    }

    public Entry get(UUID playerId) {
        return entries.get(playerId);
    }

    /** Equal revisions may contain refreshed catalogue metadata or reloaded glyphs. */
    public Entry accept(TagSnapshot snapshot, Map<String, Component> icons) {
        Entry incoming = new Entry(snapshot, icons);
        return entries.compute(snapshot.playerId(), (id, existing) ->
                existing != null && existing.snapshot().revision() > snapshot.revision()
                        ? existing : incoming);
    }

    public String placeholder(UUID playerId) {
        Entry entry = entries.get(playerId);
        return entry == null ? "" : entry.placeholder();
    }

    public void remove(UUID playerId) {
        entries.remove(playerId);
    }

    public void clear() {
        entries.clear();
    }

    static boolean validGlyphId(String glyphId) {
        return glyphId != null && GLYPH_ID.matcher(glyphId).matches();
    }
}
