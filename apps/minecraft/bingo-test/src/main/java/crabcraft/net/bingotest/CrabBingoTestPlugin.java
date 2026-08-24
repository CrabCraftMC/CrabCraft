package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoCardFourCombatListener;
import crabcraft.net.crabUtilities.bingo.BingoCardFourMechanicsListener;
import crabcraft.net.crabUtilities.bingo.BingoCardFourMobListener;
import crabcraft.net.crabUtilities.bingo.BingoCardFourWorldListener;
import crabcraft.net.crabUtilities.bingo.BingoDetector;
import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CrabBingoTestPlugin extends JavaPlugin {
    private BingoTestManager manager;
    private List<BingoDetector> detectors = List.of();

    @Override
    public void onEnable() {
        List<BingoTask> tasks = BingoTask.cardFour();
        manager = new BingoTestManager(this, tasks);
        detectors = List.of(
                new BingoCardFourMobListener(this, manager::isTracking, manager::complete),
                new BingoCardFourWorldListener(this, manager::isTracking, manager::complete),
                new BingoCardFourCombatListener(this, manager::isTracking, manager::complete),
                new BingoCardFourMechanicsListener(this, manager::isTracking, manager::complete));
        for (BingoDetector detector : detectors) {
            detector.clear();
            getServer().getPluginManager().registerEvents(detector, this);
        }
        getServer().getPluginManager().registerEvents(new BingoTestJoinListener(this, manager), this);

        PluginCommand command = Objects.requireNonNull(
                getCommand("bingotest"), "The bingotest command is missing from plugin.yml");
        BingoTestCommand commandHandler = new BingoTestCommand(manager, detectors);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        getLogger().info("Loaded " + tasks.size() + " Bingo #4 detectors for Creative and Survival testing.");
    }

    @Override
    public void onDisable() {
        for (BingoDetector detector : detectors) {
            detector.clear();
        }
        if (manager != null) {
            manager.clear();
        }
    }
}
