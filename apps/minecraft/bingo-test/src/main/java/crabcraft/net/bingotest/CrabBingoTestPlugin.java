package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoCardFiveChallengeListener;
import crabcraft.net.crabUtilities.bingo.BingoCardFiveMechanicsListener;
import crabcraft.net.crabUtilities.bingo.BingoCardFiveMobListener;
import crabcraft.net.crabUtilities.bingo.BingoCardFiveWorldListener;
import crabcraft.net.crabUtilities.bingo.BingoDetector;
import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CrabBingoTestPlugin extends JavaPlugin {
    private static final int TEST_CARD_ID = 5;

    private BingoTestManager manager;
    private List<BingoDetector> detectors = List.of();

    @Override
    public void onEnable() {
        List<BingoTask> tasks = BingoTask.cardFive();
        manager = new BingoTestManager(this, tasks);
        detectors = List.of(
                new BingoCardFiveWorldListener(
                        this, manager::isTracking, manager::complete, () -> TEST_CARD_ID),
                new BingoCardFiveMobListener(
                        this, manager::isTracking, manager::complete, () -> TEST_CARD_ID),
                new BingoCardFiveMechanicsListener(this, manager::isTracking, manager::complete),
                new BingoCardFiveChallengeListener(
                        this,
                        manager::isTracking,
                        manager::complete,
                        () -> TEST_CARD_ID,
                        true));
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

        getLogger().info("Loaded " + tasks.size() + " Bingo #5 detectors for Creative and Survival testing.");
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
