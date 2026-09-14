package crabcraft.net.bingotest

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.bingo.BingoTask
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class BingoTestManager(private val plugin: JavaPlugin, private val cardNumber: Int, tasks: List<BingoTask>) {
    private val tasks = java.util.List.copyOf(tasks)
    private val taskSet = java.util.Set.copyOf(tasks)
    private val progressByPlayer = HashMap<UUID, PlayerProgress>()

    init {
        require(cardNumber >= 1) { "The test card number must be positive" }
        require(this.tasks.size == 16 && taskSet.size == 16) {
            "The Bingo #$cardNumber test card must contain 16 unique tasks"
        }
    }

    fun isTracking(player: Player?, task: BingoTask): Boolean = player != null && task in taskSet

    fun complete(player: Player, task: BingoTask) {
        if (task !in taskSet) {
            plugin.logger.warning("Ignored non-Card #$cardNumber detector completion: ${task.id()}")
            return
        }
        val progress = progressFor(player)
        if (progress.complete(task)) announceCompletion(player, task, progress)
    }

    fun sendChecklist(player: Player) {
        val progress = progressFor(player)
        player.sendMessage(Component.empty())
        player.sendMessage(CrabMessages.text("Bingo #$cardNumber detector checklist")
            .append(Component.space())
            .append(CrabMessages.highlight("${progress.completedCount()}/${tasks.size}")))
        tasks.forEachIndexed { index, task ->
            val complete = progress.isComplete(task)
            player.sendMessage(CrabMessages.muted("${index + 1}. ")
                .append(if (complete) CrabMessages.success("Complete: ${task.description()}")
                    else CrabMessages.text("Incomplete: ${task.description()}")))
        }
        player.sendMessage(CrabMessages.muted(
            "Use /bingotest details <number> for exact rules, or /bingotest reset for a fresh test window."))
        player.sendMessage(CrabMessages.muted(
            "Progress is memory-only and resets when this plugin or the server restarts."))
    }

    fun sendDetails(player: Player, number: Int) {
        if (number < 1 || number > tasks.size) {
            player.sendMessage(CrabMessages.error("Choose a task number from 1 to ${tasks.size}."))
            return
        }
        val task = tasks[number - 1]
        val complete = progressFor(player).isComplete(task)
        player.sendMessage(CrabMessages.highlight("$number. ${task.description()}").decorate(TextDecoration.BOLD))
        if (task.detail().isNotBlank()) player.sendMessage(CrabMessages.text(task.detail()))
        player.sendMessage(if (complete) CrabMessages.success("Status: complete") else CrabMessages.warning("Status: incomplete"))
        player.sendMessage(CrabMessages.muted("Detector ID: ${task.id()}"))
    }

    fun reset(player: Player) {
        progressByPlayer.remove(player.uniqueId)
        player.sendMessage(CrabMessages.warning("Your checklist and detector state have been reset."))
    }

    fun taskCount(): Int = tasks.size
    fun clear() { progressByPlayer.clear() }

    private fun progressFor(player: Player): PlayerProgress =
        progressByPlayer.computeIfAbsent(player.uniqueId) { PlayerProgress(tasks) }

    private fun announceCompletion(player: Player, task: BingoTask, progress: PlayerProgress) {
        player.sendMessage(CrabMessages.muted("Completed").append(Component.space())
            .append(CrabMessages.highlight(task.description())))
        plugin.logger.info("${player.name} completed Card #$cardNumber detector ${task.id()}")
        if (progress.isChecklistComplete()) {
            player.sendMessage(CrabMessages.success("All 16 Bingo #$cardNumber detectors have passed!")
                .decorate(TextDecoration.BOLD))
        }
    }
}
