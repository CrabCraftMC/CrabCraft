package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoCardSixMechanicsListener;
import crabcraft.net.crabUtilities.bingo.BingoCardSixMobListener;
import crabcraft.net.crabUtilities.bingo.BingoCardSixWorldListener;
import crabcraft.net.crabUtilities.bingo.BingoDetector;
import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CrabBingoTestPlugin extends JavaPlugin {
    private static final int TEST_CARD_NUMBER = 6;
    private static final int TEST_CARD_ID = 6;

    private BingoTestManager manager;
    private List<BingoDetector> detectors = List.of();

    @Override
    public void onEnable() {
        List<BingoTask> tasks = BingoTask.cardSix();
        manager = new BingoTestManager(this, TEST_CARD_NUMBER, tasks);
        detectors = List.of(
                new BingoCardSixWorldListener(
                        this, manager::isTracking, manager::complete, () -> TEST_CARD_ID),
                new BingoCardSixMobListener(
                        this, manager::isTracking, manager::complete, () -> TEST_CARD_ID),
                new BingoCardSixMechanicsListener(
                        this, manager::isTracking, manager::complete, () -> TEST_CARD_ID));
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

        getLogger().info("Loaded " + tasks.size()
                + " Bingo #" + TEST_CARD_NUMBER + " detectors for Creative and Survival testing.");
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
