package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.CrabMessages;
import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class BingoTestManager {
    private final JavaPlugin plugin;
    private final List<BingoTask> tasks;
    private final Set<BingoTask> taskSet;
    private final Map<UUID, PlayerProgress> progressByPlayer = new HashMap<>();

    BingoTestManager(JavaPlugin plugin, List<BingoTask> tasks) {
        this.plugin = plugin;
        this.tasks = List.copyOf(tasks);
        this.taskSet = Set.copyOf(tasks);
        if (this.tasks.size() != 16 || this.taskSet.size() != 16) {
            throw new IllegalArgumentException("The Bingo #3 test card must contain 16 unique tasks");
        }
    }

    boolean isTracking(Player player, BingoTask task) {
        return player != null && taskSet.contains(task);
    }

    void complete(Player player, BingoTask task) {
        if (!taskSet.contains(task)) {
            plugin.getLogger().warning("Ignored non-Card #3 detector completion: " + task.id());
            return;
        }

        PlayerProgress progress = progressFor(player);
        if (progress.complete(task)) {
            announceCompletion(player, task, progress);
        }
    }

    void sendChecklist(Player player) {
        PlayerProgress progress = progressFor(player);
        player.sendMessage(Component.empty());
        player.sendMessage(CrabMessages.text("Bingo #3 detector checklist")
                .append(Component.space())
                .append(CrabMessages.highlight(progress.completedCount() + "/" + tasks.size())));

        for (int index = 0; index < tasks.size(); index++) {
            BingoTask task = tasks.get(index);
            boolean complete = progress.isComplete(task);
            player.sendMessage(CrabMessages.muted((index + 1) + ". ")
                    .append(complete
                            ? CrabMessages.success("Complete: " + task.description())
                            : CrabMessages.text("Incomplete: " + task.description())));
        }

        player.sendMessage(CrabMessages.muted(
                "Use /bingotest details <number> for exact rules, or /bingotest reset for a fresh test window."));
        player.sendMessage(CrabMessages.muted(
                "Progress is memory-only and resets when this plugin or the server restarts."));
    }

    void sendDetails(Player player, int number) {
        if (number < 1 || number > tasks.size()) {
            player.sendMessage(CrabMessages.error(
                    "Choose a task number from 1 to " + tasks.size() + "."));
            return;
        }

        BingoTask task = tasks.get(number - 1);
        boolean complete = progressFor(player).isComplete(task);
        player.sendMessage(CrabMessages.highlight(number + ". " + task.description())
                .decorate(TextDecoration.BOLD));
        if (!task.detail().isBlank()) {
            player.sendMessage(CrabMessages.text(task.detail()));
        }
        player.sendMessage(complete
                ? CrabMessages.success("Status: complete")
                : CrabMessages.warning("Status: incomplete"));
        player.sendMessage(CrabMessages.muted("Detector ID: " + task.id()));
    }

    void reset(Player player) {
        progressByPlayer.remove(player.getUniqueId());
        player.sendMessage(CrabMessages.warning(
                "Your checklist and detector state have been reset."));
    }

    int taskCount() {
        return tasks.size();
    }

    void clear() {
        progressByPlayer.clear();
    }

    private PlayerProgress progressFor(Player player) {
        return progressByPlayer.computeIfAbsent(
                player.getUniqueId(), ignored -> new PlayerProgress(tasks));
    }

    private void announceCompletion(Player player, BingoTask task, PlayerProgress progress) {
        player.sendMessage(CrabMessages.muted("Completed")
                .append(Component.space())
                .append(CrabMessages.highlight(task.description())));
        plugin.getLogger().info(player.getName() + " completed Card #3 detector " + task.id());

        if (progress.isChecklistComplete()) {
            player.sendMessage(CrabMessages.success(
                            "All 16 Bingo #3 detectors have passed!")
                    .decorate(TextDecoration.BOLD));
        }
    }
}
