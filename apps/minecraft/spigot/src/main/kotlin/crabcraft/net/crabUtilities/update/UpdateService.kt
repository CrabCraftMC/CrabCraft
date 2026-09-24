package crabcraft.net.crabUtilities.update

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.CrabUtilities
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask

open class UpdateService(private val plugin: CrabUtilities) {
    enum class State {
        IDLE,
        CHECKING,
        DOWNLOADING,
        READY,
        UP_TO_DATE,
        ERROR,
    }

    private val state = AtomicReference(State.IDLE)
    @Volatile private var lastCheck: Instant? = null
    @Volatile private var lastSeen: ReleaseInfo? = null
    @Volatile private var lastError: String? = null
    private var periodic: BukkitTask? = null

    open fun start() {
        val hours = maxOf(1L, plugin.getConfig().getLong("auto-update.check-interval-hours", 6L))
        val ticks = hours * 60L * 60L * 20L
        // Initial check after 2 minutes; then every `ticks` thereafter.
        periodic =
            Bukkit.getScheduler()
                .runTaskTimerAsynchronously(
                    plugin,
                    Runnable { runCheck(true, null) },
                    20L * 120L,
                    ticks,
                )
        plugin.getLogger().info("Auto-update enabled; checking every ${hours}h")
    }

    open fun shutdown() {
        periodic?.cancel()
        periodic = null
    }

    open fun getState(): State = state.get()

    open fun getLastCheck(): Instant? = lastCheck

    open fun getLastSeen(): ReleaseInfo? = lastSeen

    open fun getLastError(): String? = lastError

    /**
     * Runs a check (and optional download). Must be called on an async thread. Reporter receives human-readable status
     * lines if non-null.
     */
    open fun runCheck(download: Boolean, reporter: Consumer<String>?) {
        if (
            !state.compareAndSet(State.IDLE, State.CHECKING) &&
                !state.compareAndSet(State.READY, State.CHECKING) &&
                !state.compareAndSet(State.UP_TO_DATE, State.CHECKING) &&
                !state.compareAndSet(State.ERROR, State.CHECKING)
        ) {
            reporter?.accept("Update ${state.get().name.lowercase(Locale.getDefault())} already in progress.")
            return
        }
        try {
            val repo = plugin.getConfig().getString("auto-update.github-repo", "CrabCraftMC/CrabCraft")!!
            val token = plugin.getConfig().getString("auto-update.github-token", "")!!
            val includePre = plugin.getConfig().getBoolean("auto-update.include-prereleases", false)
            val current = plugin.getDescription().version
            val userAgent = "CrabUtilities/$current (+https://github.com/$repo)"
            val info = UpdateChecker(repo, token, "CrabUtilities.jar", userAgent).fetchLatest()
            lastSeen = info
            lastCheck = Instant.now()
            val currentVersion = SemVer.parse(current)
            if (currentVersion == null) {
                state.set(State.UP_TO_DATE)
                plugin
                    .getLogger()
                    .info(
                        "Auto-update: running a SNAPSHOT/non-semver build ($current); skipping update check against ${info.tag()}"
                    )
                reporter?.accept("Running a development build ($current); updates only applied to tagged builds.")
                return
            }
            val releaseVersion = info.version()
            if (releaseVersion == null) {
                state.set(State.ERROR)
                lastError = "Could not parse release tag: ${info.tag()}"
                reporter?.accept("Latest release tag '${info.tag()}' is not a semver.")
                return
            }
            if (info.prerelease() && !includePre) {
                state.set(State.UP_TO_DATE)
                reporter?.accept("Latest release ${info.tag()} is a pre-release; skipping.")
                return
            }
            if (releaseVersion.compareTo(currentVersion) <= 0) {
                state.set(State.UP_TO_DATE)
                reporter?.accept("Already up to date ($current).")
                plugin.getLogger().info("Auto-update: already up to date ($current).")
                return
            }
            reporter?.accept("Update available: ${info.tag()} (current: $current).")
            plugin.getLogger().info("Auto-update: ${info.tag()} available (current: $current)")
            if (!download) {
                state.set(State.IDLE)
                return
            }
            state.set(State.DOWNLOADING)
            val updateDir = plugin.getDataFolder().parentFile.toPath().resolve("update")
            val output = UpdateDownloader(token, userAgent).download(info, updateDir, plugin.getPluginJarFile().name)
            state.set(State.READY)
            val message = "Staged ${info.tag()} at $output; restart the server to apply."
            plugin.getLogger().info("Auto-update: $message")
            reporter?.accept(message)
            if (plugin.getConfig().getBoolean("auto-update.notify-ops", true)) {
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        Runnable {
                            for (player in Bukkit.getOnlinePlayers()) {
                                if (player.isOp)
                                    player.sendMessage(
                                        CrabMessages.success("Update ")
                                            .append(CrabMessages.highlight(info.tag()))
                                            .append(CrabMessages.success(" staged. Restart the server to apply."))
                                    )
                            }
                        },
                    )
            }
        } catch (error: UpdateExceptions.RateLimitedException) {
            state.set(State.ERROR)
            lastError = error.message
            plugin.getLogger().warning("Auto-update: ${error.message}")
            reporter?.accept(error.message!!)
        } catch (error: UpdateExceptions.NoReleaseException) {
            state.set(State.UP_TO_DATE)
            lastError = null
            plugin.getLogger().info("Auto-update: no releases published yet for this repo.")
            reporter?.accept("No releases published yet.")
        } catch (error: Exception) {
            state.set(State.ERROR)
            lastError = "${error.javaClass.simpleName}: ${error.message}"
            plugin.getLogger().warning("Auto-update failed: $lastError")
            reporter?.accept("Update failed: $lastError")
        }
    }
}
