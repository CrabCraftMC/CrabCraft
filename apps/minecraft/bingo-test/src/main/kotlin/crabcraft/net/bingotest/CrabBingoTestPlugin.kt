package crabcraft.net.bingotest

import crabcraft.net.crabUtilities.bingo.BingoCardSixMechanicsListener
import crabcraft.net.crabUtilities.bingo.BingoCardSixMobListener
import crabcraft.net.crabUtilities.bingo.BingoCardSixWorldListener
import crabcraft.net.crabUtilities.bingo.BingoDetector
import crabcraft.net.crabUtilities.bingo.BingoTask
import org.bukkit.plugin.java.JavaPlugin

class CrabBingoTestPlugin : JavaPlugin() {
    private var manager: BingoTestManager? = null
    private var detectors = emptyList<BingoDetector>()

    override fun onEnable() {
        val tasks = BingoTask.cardSix()
        val tracker = BingoTestManager(this, TEST_CARD_NUMBER, tasks)
        manager = tracker
        detectors =
            listOf(
                BingoCardSixWorldListener(this, tracker::isTracking, tracker::complete) { TEST_CARD_ID },
                BingoCardSixMobListener(this, tracker::isTracking, tracker::complete) { TEST_CARD_ID },
                BingoCardSixMechanicsListener(this, tracker::isTracking, tracker::complete) { TEST_CARD_ID },
            )
        detectors.forEach {
            it.clear()
            server.pluginManager.registerEvents(it, this)
        }
        server.pluginManager.registerEvents(BingoTestJoinListener(this, tracker), this)
        val command = requireNotNull(getCommand("bingotest")) { "The bingotest command is missing from plugin.yml" }
        val handler = BingoTestCommand(tracker, detectors)
        command.setExecutor(handler)
        command.tabCompleter = handler
        logger.info("Loaded ${tasks.size} Bingo #$TEST_CARD_NUMBER detectors for Creative and Survival testing.")
    }

    override fun onDisable() {
        detectors.forEach { it.clear() }
        manager?.clear()
    }

    private companion object {
        const val TEST_CARD_NUMBER = 6
        const val TEST_CARD_ID = 6
    }
}
