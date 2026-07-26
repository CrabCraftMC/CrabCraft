package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Loads Simple Voice Chat API types only after the soft dependency is present. */
public final class VoicechatIntegration {

    private VoicechatIntegration() {}

    public static AutoCloseable register(CrabUtilities plugin) {
        BukkitVoicechatService service = plugin.getServer()
                .getServicesManager()
                .load(BukkitVoicechatService.class);
        if (service == null) {
            return null;
        }

        CrabVoicechatPlugin voicechatPlugin = new CrabVoicechatPlugin(plugin);
        service.registerPlugin(voicechatPlugin);
        Listener quitListener = new Listener() {};
        plugin.getServer().getPluginManager().registerEvent(
                PlayerQuitEvent.class, quitListener, EventPriority.LOWEST,
                (listener, event) -> voicechatPlugin.beforePlayerQuit(
                        ((PlayerQuitEvent) event).getPlayer().getUniqueId()),
                plugin);
        plugin.getLogger().info("Registered Simple Voice Chat plugin");

        return () -> {
            try {
                voicechatPlugin.shutdown();
            } finally {
                HandlerList.unregisterAll(quitListener);
                plugin.getServer().getServicesManager().unregister(voicechatPlugin);
            }
        };
    }
}
