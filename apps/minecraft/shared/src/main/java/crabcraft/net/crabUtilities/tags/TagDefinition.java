package crabcraft.net.crabUtilities.tags;

import java.util.Objects;

/** Display metadata for an earned cosmetic, independent of its ownership. */
public record TagDefinition(String id, String name, String description, String glyphId,
                            int sortOrder, boolean enabled) {
    public TagDefinition {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,95}")) {
            throw new IllegalArgumentException("Invalid tag ID");
        }
        if (glyphId == null || !glyphId.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Invalid glyph ID");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (name.isBlank() || name.length() > 160 || description.length() > 1024) {
            throw new IllegalArgumentException("Invalid tag display text");
        }
    }
}
