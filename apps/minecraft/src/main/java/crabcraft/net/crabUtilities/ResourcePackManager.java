package crabcraft.net.crabUtilities;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ResourcePackManager {
    private final CrabUtilities plugin;
    private String url;
    private String hashHex; // optional, informational only for now
    private final Set<UUID> autoEnabled = new HashSet<>();

    public ResourcePackManager(CrabUtilities plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        ConfigurationSection rp = plugin.getConfig().getConfigurationSection("resource-pack");
        if (rp == null) {
            plugin.getConfig().createSection("resource-pack");
            rp = plugin.getConfig().getConfigurationSection("resource-pack");
        }
        this.url = rp.getString("url", "");
        this.hashHex = rp.getString("hash", "");
        autoEnabled.clear();
        List<String> list = rp.getStringList("auto-enabled");
        for (String s : list) {
            try {
                autoEnabled.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    public boolean isEnabled(UUID uuid) {
        return autoEnabled.contains(uuid);
    }

    public void setEnabled(UUID uuid, boolean enabled) {
        if (enabled) autoEnabled.add(uuid); else autoEnabled.remove(uuid);
        persist();
    }

    public boolean enableFor(Player player) {
        if (!isConfigured()) return false;
        sendPack(player);
        setEnabled(player.getUniqueId(), true);
        return true;
    }

    public void disableFor(Player player) {
        setEnabled(player.getUniqueId(), false);
    }

    public void sendIfOptedIn(Player player) {
        if (!isConfigured()) return;
        if (isEnabled(player.getUniqueId())) {
            sendPack(player);
        }
    }

    private void persist() {
        ConfigurationSection rp = plugin.getConfig().getConfigurationSection("resource-pack");
        if (rp == null) rp = plugin.getConfig().createSection("resource-pack");
        List<String> list = autoEnabled.stream().map(UUID::toString).toList();
        rp.set("auto-enabled", list);
        plugin.saveConfig();
    }

    private void sendPack(Player player) {
        // Use the simplest API to maximize compatibility across Paper builds
        // The hash is optional here; if your server needs hash verification, we can extend this.
        player.setResourcePack(url);
    }
}

