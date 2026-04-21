package crabcraft.net.crabUtilities.update;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class UpdateService {

    public enum State {
        IDLE, CHECKING, DOWNLOADING, READY, UP_TO_DATE, ERROR
    }

    private final CrabUtilities plugin;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    private volatile Instant lastCheck;
    private volatile ReleaseInfo lastSeen;
    private volatile String lastError;
    private BukkitTask periodic;

    public UpdateService(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long hours = Math.max(1L, plugin.getConfig().getLong("auto-update.check-interval-hours", 6L));
        long ticks = hours * 60L * 60L * 20L;
        // initial check after 2 minutes; then every `ticks` thereafter.
        periodic = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                () -> runCheck(true, null), 20L * 120L, ticks);
        plugin.getLogger().info("Auto-update enabled; checking every " + hours + "h");
    }

    public void shutdown() {
        if (periodic != null) {
            periodic.cancel();
            periodic = null;
        }
    }

    public State getState() { return state.get(); }
    public Instant getLastCheck() { return lastCheck; }
    public ReleaseInfo getLastSeen() { return lastSeen; }
    public String getLastError() { return lastError; }

    /**
     * Runs a check (and optional download). Must be called on an async thread.
     * Reporter receives human-readable status lines if non-null.
     */
    public void runCheck(boolean download, Consumer<String> reporter) {
        if (!state.compareAndSet(State.IDLE, State.CHECKING)
                && !state.compareAndSet(State.READY, State.CHECKING)
                && !state.compareAndSet(State.UP_TO_DATE, State.CHECKING)
                && !state.compareAndSet(State.ERROR, State.CHECKING)) {
            if (reporter != null) reporter.accept("Update " + state.get().name().toLowerCase() + " already in progress.");
            return;
        }

        try {
            String repo = plugin.getConfig().getString("auto-update.github-repo", "CrabCraftMC/CrabCraft");
            String token = plugin.getConfig().getString("auto-update.github-token", "");
            boolean includePre = plugin.getConfig().getBoolean("auto-update.include-prereleases", false);
            String current = plugin.getDescription().getVersion();
            String ua = "CrabUtilities/" + current + " (+https://github.com/" + repo + ")";

            UpdateChecker checker = new UpdateChecker(repo, token, "CrabUtilities.jar", ua);
            ReleaseInfo info = checker.fetchLatest();
            lastSeen = info;
            lastCheck = Instant.now();

            SemVer currentVer = SemVer.parse(current);
            if (currentVer == null) {
                state.set(State.UP_TO_DATE);
                plugin.getLogger().info("Auto-update: running a SNAPSHOT/non-semver build ("
                        + current + "); skipping update check against " + info.tag());
                if (reporter != null) reporter.accept("Running a development build (" + current
                        + "); updates only applied to tagged builds.");
                return;
            }
            if (info.version() == null) {
                state.set(State.ERROR);
                lastError = "Could not parse release tag: " + info.tag();
                if (reporter != null) reporter.accept("Latest release tag '" + info.tag() + "' is not a semver.");
                return;
            }
            if (info.prerelease() && !includePre) {
                state.set(State.UP_TO_DATE);
                if (reporter != null) reporter.accept("Latest release " + info.tag() + " is a pre-release; skipping.");
                return;
            }
            if (info.version().compareTo(currentVer) <= 0) {
                state.set(State.UP_TO_DATE);
                if (reporter != null) reporter.accept("Already up to date (" + current + ").");
                plugin.getLogger().info("Auto-update: already up to date (" + current + ").");
                return;
            }

            if (reporter != null) reporter.accept("Update available: " + info.tag() + " (current: " + current + ").");
            plugin.getLogger().info("Auto-update: " + info.tag() + " available (current: " + current + ")");

            if (!download) {
                state.set(State.IDLE);
                return;
            }

            state.set(State.DOWNLOADING);
            UpdateDownloader dl = new UpdateDownloader(token, ua);
            File pluginsDir = plugin.getDataFolder().getParentFile();
            Path updateDir = pluginsDir.toPath().resolve("update");
            String targetName = plugin.getPluginJarFile().getName();
            Path out = dl.download(info, updateDir, targetName);

            state.set(State.READY);
            String msg = "Staged " + info.tag() + " at " + out
                    + "; restart the server to apply.";
            plugin.getLogger().info("Auto-update: " + msg);
            if (reporter != null) reporter.accept(msg);

            if (plugin.getConfig().getBoolean("auto-update.notify-ops", true)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.isOp()) {
                            p.sendMessage(ChatColor.GOLD + "[CrabUtilities] "
                                    + ChatColor.YELLOW + "Update "
                                    + ChatColor.WHITE + info.tag()
                                    + ChatColor.YELLOW + " staged. Restart the server to apply.");
                        }
                    }
                });
            }
        } catch (UpdateExceptions.RateLimitedException e) {
            state.set(State.ERROR);
            lastError = e.getMessage();
            plugin.getLogger().warning("Auto-update: " + e.getMessage());
            if (reporter != null) reporter.accept(e.getMessage());
        } catch (UpdateExceptions.NoReleaseException e) {
            state.set(State.UP_TO_DATE);
            lastError = null;
            plugin.getLogger().info("Auto-update: no releases published yet for this repo.");
            if (reporter != null) reporter.accept("No releases published yet.");
        } catch (Exception e) {
            state.set(State.ERROR);
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            plugin.getLogger().warning("Auto-update failed: " + lastError);
            if (reporter != null) reporter.accept("Update failed: " + lastError);
        }
    }
}
