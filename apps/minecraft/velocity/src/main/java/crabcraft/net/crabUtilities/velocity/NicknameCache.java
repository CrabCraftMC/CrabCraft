package crabcraft.net.crabUtilities.velocity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class NicknameCache {

    // Matches all Minecraft color code formats and MiniMessage tags
    private static final Pattern COLOR_PATTERN = Pattern.compile(
            "(?i)§x(§[0-9a-f]){6}|&x(&[0-9a-f]){6}|&#[0-9a-f]{6}|[&§][0-9a-fk-orx]|<[^>]+>"
    );

    private final Map<UUID, String> nicknames = new ConcurrentHashMap<>();

    public void setNickname(UUID uuid, String rawNickname) {
        if (rawNickname == null || rawNickname.isEmpty()) {
            nicknames.remove(uuid);
        } else {
            nicknames.put(uuid, rawNickname);
        }
    }

    public String getRawNickname(UUID uuid) {
        return nicknames.get(uuid);
    }

    public String getPlainNickname(UUID uuid) {
        String raw = nicknames.get(uuid);
        if (raw == null) return null;
        return stripColors(raw);
    }

    public static String stripColors(String text) {
        if (text == null) return null;
        return COLOR_PATTERN.matcher(text).replaceAll("");
    }

    public void remove(UUID uuid) {
        nicknames.remove(uuid);
    }
}
