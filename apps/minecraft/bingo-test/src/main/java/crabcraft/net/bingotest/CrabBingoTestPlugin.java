package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoCardTwoListener;
import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CrabBingoTestPlugin extends JavaPlugin {
    private BingoTestManager manager;
    private BingoCardTwoListener listener;

    @Override
    public void onEnable() {
        List<BingoTask> tasks = BingoTask.cardTwo();
        manager = new BingoTestManager(this, tasks);
        listener = new BingoCardTwoListener(this, manager::isTracking, manager::complete);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(new BingoTestJoinListener(this, manager), this);

        PluginCommand command = Objects.requireNonNull(
                getCommand("bingotest"), "The bingotest command is missing from plugin.yml");
        BingoTestCommand commandHandler = new BingoTestCommand(manager, listener);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        getLogger().info("Loaded " + tasks.size() + " Bingo #2 detectors for Creative and Survival testing.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.clear();
        }
        if (manager != null) {
            manager.clear();
        }
    }
}
