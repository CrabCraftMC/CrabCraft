package crabcraft.net.crabUtilities.tags;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One consistent database view of a player's collection and saved choice. */
public record TagSnapshot(UUID playerId, long revision, String selectedTagId,
                          List<TagDefinition> ownedTags) {
    public TagSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        if (revision < 0) throw new IllegalArgumentException("Negative tag revision");
        ownedTags = List.copyOf(ownedTags);
        var ids = new HashSet<String>();
        for (TagDefinition tag : ownedTags) {
            if (!ids.add(tag.id())) throw new IllegalArgumentException("Duplicate owned tag");
        }
        if (selectedTagId != null && !ids.contains(selectedTagId)) {
            throw new IllegalArgumentException("Selected tag is not owned");
        }
    }

    /** A disabled choice is retained for later re-enabling, but must not render. */
    public TagDefinition selectedTag() {
        return ownedTags.stream().filter(tag -> tag.id().equals(selectedTagId)).findFirst().orElse(null);
    }
}
