package crabcraft.net.crabUtilities.velocity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class NicknameCache {

    public record Snapshot(boolean loaded, String rawNickname, long version) {}

    private record Entry(boolean loaded, String rawNickname, long version) {}

    private final Map<UUID, Entry> nicknames = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong();

    public void setNickname(UUID uuid, String rawNickname) {
        String normalized = rawNickname == null ? "" : rawNickname;
        nicknames.compute(uuid, (id, current) ->
                new Entry(true, normalized, versions.incrementAndGet()));
    }

    public String getRawNickname(UUID uuid) {
        Entry entry = nicknames.get(uuid);
        return entry == null || !entry.loaded() || entry.rawNickname().isEmpty()
                ? null
                : entry.rawNickname();
    }

    public String getPlainNickname(UUID uuid) {
        String raw = getRawNickname(uuid);
        if (raw == null) return null;
        return stripColors(raw);
    }

    public boolean isLoaded(UUID uuid) {
        Entry entry = nicknames.get(uuid);
        return entry != null && entry.loaded();
    }

    public Snapshot snapshot(UUID uuid) {
        Entry entry = nicknames.get(uuid);
        return entry == null
                ? new Snapshot(false, null, 0L)
                : snapshot(entry);
    }

    public Snapshot beginLoad(UUID uuid) {
        Entry entry = nicknames.computeIfAbsent(uuid,
                id -> new Entry(false, "", versions.incrementAndGet()));
        return snapshot(entry);
    }

    public boolean commitIfVersion(UUID uuid, long expectedVersion, String rawNickname) {
        String normalized = rawNickname == null ? "" : rawNickname;
        AtomicBoolean committed = new AtomicBoolean();
        nicknames.compute(uuid, (id, current) -> {
            if (current == null || current.version() != expectedVersion) return current;
            committed.set(true);
            return new Entry(true, normalized, versions.incrementAndGet());
        });
        return committed.get();
    }

    public boolean discardIfUnloadedVersion(UUID uuid, long expectedVersion) {
        AtomicBoolean discarded = new AtomicBoolean();
        nicknames.computeIfPresent(uuid, (id, current) -> {
            if (current.loaded() || current.version() != expectedVersion) return current;
            discarded.set(true);
            return null;
        });
        return discarded.get();
    }

    public boolean isVersion(UUID uuid, long version) {
        Entry entry = nicknames.get(uuid);
        return entry != null && entry.loaded() && entry.version() == version;
    }

    public static String stripColors(String text) {
        return NicknameComponentParser.plain(text);
    }

    public void remove(UUID uuid) {
        nicknames.remove(uuid);
    }

    private static Snapshot snapshot(Entry entry) {
        return new Snapshot(entry.loaded(), entry.loaded() ? entry.rawNickname() : null, entry.version());
    }
}
