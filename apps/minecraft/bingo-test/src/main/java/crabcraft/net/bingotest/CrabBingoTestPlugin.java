package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoCardThreeAdventureListener;
import crabcraft.net.crabUtilities.bingo.BingoCardThreeChallengeListener;
import crabcraft.net.crabUtilities.bingo.BingoCardThreeCombatListener;
import crabcraft.net.crabUtilities.bingo.BingoCardThreeCoreListener;
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
        List<BingoTask> tasks = BingoTask.cardThree();
        manager = new BingoTestManager(this, tasks);
        detectors = List.of(
                new BingoCardThreeCoreListener(this, manager::isTracking, manager::complete),
                new BingoCardThreeAdventureListener(this, manager::isTracking, manager::complete),
                new BingoCardThreeCombatListener(this, manager::isTracking, manager::complete),
                new BingoCardThreeChallengeListener(this, manager::isTracking, manager::complete));
        for (BingoDetector detector : detectors) {
            getServer().getPluginManager().registerEvents(detector, this);
        }
        getServer().getPluginManager().registerEvents(new BingoTestJoinListener(this, manager), this);

        PluginCommand command = Objects.requireNonNull(
                getCommand("bingotest"), "The bingotest command is missing from plugin.yml");
        BingoTestCommand commandHandler = new BingoTestCommand(manager, detectors);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        getLogger().info("Loaded " + tasks.size() + " Bingo #3 detectors for Creative and Survival testing.");
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
