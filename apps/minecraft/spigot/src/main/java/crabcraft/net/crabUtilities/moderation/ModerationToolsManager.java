package crabcraft.net.crabUtilities.moderation;

import crabcraft.net.crabUtilities.CrabUtilities;

public class ModerationToolsManager {
    private final CrabUtilities plugin;

    public ModerationToolsManager(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public void register() {
        registerCommands();
    }

    private void registerCommands() {
        plugin.getCommand("smite").setExecutor(new SmiteCommand());
    }
}
