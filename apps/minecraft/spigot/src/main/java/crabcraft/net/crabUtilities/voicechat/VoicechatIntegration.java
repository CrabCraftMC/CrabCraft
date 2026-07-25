package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;

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
        plugin.getLogger().info("Registered Simple Voice Chat plugin");

        return () -> {
            try {
                voicechatPlugin.shutdown();
            } finally {
                plugin.getServer().getServicesManager().unregister(voicechatPlugin);
            }
        };
    }
}
