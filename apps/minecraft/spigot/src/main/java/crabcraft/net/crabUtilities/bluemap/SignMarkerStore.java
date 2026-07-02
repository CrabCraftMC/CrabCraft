package crabcraft.net.crabUtilities.bluemap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * YAML-backed store of player-made sign markers, keyed by world UUID and block
 * position. BlueMap's own markers are not persistent — they vanish whenever
 * BlueMap reloads — so this file is the source of truth: the service replays
 * it into BlueMap every time the API enables.
 */
final class SignMarkerStore {

    private final JavaPlugin plugin;
    private final File file;
    // world UUID -> "x_y_z" -> marker label
    private final Map<UUID, Map<String, String>> markers = new ConcurrentHashMap<>();

    SignMarkerStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bluemap-sign-markers.yml");
    }

    static String key(int x, int y, int z) {
        return x + "_" + y + "_" + z;
    }

    /** Inverse of {@link #key}; {@code null} for keys that don't parse. */
    static int[] parseKey(String key) {
        String[] parts = key.split("_");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void load() {
        markers.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("markers");
        if (root == null) {
            return;
        }
        for (String worldKey : root.getKeys(false)) {
            UUID worldId;
            try {
                worldId = UUID.fromString(worldKey);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping sign markers for invalid world id: " + worldKey);
                continue;
            }
            ConfigurationSection worldSection = root.getConfigurationSection(worldKey);
            if (worldSection == null) {
                continue;
            }
            Map<String, String> worldMarkers = new ConcurrentHashMap<>();
            for (String posKey : worldSection.getKeys(false)) {
                String label = worldSection.getString(posKey);
                if (label != null && parseKey(posKey) != null) {
                    worldMarkers.put(posKey, label);
                }
            }
            if (!worldMarkers.isEmpty()) {
                markers.put(worldId, worldMarkers);
            }
        }
    }

    void put(UUID worldId, int x, int y, int z, String label) {
        markers.computeIfAbsent(worldId, id -> new ConcurrentHashMap<>()).put(key(x, y, z), label);
        save();
    }

    boolean remove(UUID worldId, int x, int y, int z) {
        Map<String, String> worldMarkers = markers.get(worldId);
        if (worldMarkers == null || worldMarkers.remove(key(x, y, z)) == null) {
            return false;
        }
        save();
        return true;
    }

    boolean contains(UUID worldId, int x, int y, int z) {
        Map<String, String> worldMarkers = markers.get(worldId);
        return worldMarkers != null && worldMarkers.containsKey(key(x, y, z));
    }

    Map<UUID, Map<String, String>> snapshot() {
        Map<UUID, Map<String, String>> copy = new HashMap<>();
        markers.forEach((id, worldMarkers) -> copy.put(id, new HashMap<>(worldMarkers)));
        return Collections.unmodifiableMap(copy);
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        markers.forEach((worldId, worldMarkers) ->
                worldMarkers.forEach((posKey, label) ->
                        yaml.set("markers." + worldId + "." + posKey, label)));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save " + file.getName(), e);
        }
    }
}
