package crabcraft.net.bingotest

import crabcraft.net.crabUtilities.bingo.BingoCardSixMechanicsListener
import crabcraft.net.crabUtilities.bingo.BingoCardSixMobListener
import crabcraft.net.crabUtilities.bingo.BingoCardSixWorldListener
import crabcraft.net.crabUtilities.bingo.BingoDetector
import crabcraft.net.crabUtilities.bingo.BingoTask
import org.bukkit.plugin.java.JavaPlugin

class CrabBingoTestPlugin : JavaPlugin() {
    private var manager: BingoTestManager? = null
    private var detectors: List<BingoDetector> = emptyList()

    override fun onEnable() {
        val tasks = BingoTask.cardSix()
        val manager = BingoTestManager(this, TEST_CARD_NUMBER, tasks)
        this.manager = manager
        detectors = listOf(
            BingoCardSixWorldListener(this, manager::isTracking, manager::complete) { TEST_CARD_ID },
            BingoCardSixMobListener(this, manager::isTracking, manager::complete) { TEST_CARD_ID },
            BingoCardSixMechanicsListener(this, manager::isTracking, manager::complete) { TEST_CARD_ID }
        )
        for (detector in detectors) {
            detector.clear()
            server.pluginManager.registerEvents(detector, this)
        }
        server.pluginManager.registerEvents(BingoTestJoinListener(this, manager), this)
        val command = java.util.Objects.requireNonNull(getCommand("bingotest"),
            "The bingotest command is missing from plugin.yml")!!
        val commandHandler = BingoTestCommand(manager, detectors)
        command.setExecutor(commandHandler)
        command.tabCompleter = commandHandler
        logger.info("Loaded ${tasks.size} Bingo #$TEST_CARD_NUMBER detectors for Creative and Survival testing.")
    }

    override fun onDisable() {
        detectors.forEach { it.clear() }
        manager?.clear()
    }

    companion object {
        private const val TEST_CARD_NUMBER = 6
        private const val TEST_CARD_ID = 6
    }
}
