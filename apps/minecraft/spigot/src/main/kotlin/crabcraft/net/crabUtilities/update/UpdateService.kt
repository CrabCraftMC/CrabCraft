package crabcraft.net.crabUtilities.update

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.CrabMessages
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

open class UpdateService(private val plugin: CrabUtilities) {
    enum class State { IDLE, CHECKING, DOWNLOADING, READY, UP_TO_DATE, ERROR }
    private val state = AtomicReference(State.IDLE)
    @Volatile private var lastCheck: Instant? = null
    @Volatile private var lastSeen: ReleaseInfo? = null
    @Volatile private var lastError: String? = null
    private var periodic: BukkitTask? = null
    open fun start() {
        val hours = maxOf(1L, plugin.getConfig().getLong("auto-update.check-interval-hours", 6L))
        val ticks = hours * 60L * 60L * 20L
        periodic = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable { runCheck(true, null) }, 20L * 120L, ticks)
        plugin.getLogger().info("Auto-update enabled; checking every ${hours}h")
    }
    open fun shutdown() { periodic?.cancel(); periodic = null }
    open fun getState(): State = state.get()
    open fun getLastCheck(): Instant? = lastCheck
    open fun getLastSeen(): ReleaseInfo? = lastSeen
    open fun getLastError(): String? = lastError
    /** Run on an async thread; the optional reporter receives status lines. */
    open fun runCheck(download: Boolean, reporter: Consumer<String>?) {
        if (!state.compareAndSet(State.IDLE, State.CHECKING) && !state.compareAndSet(State.READY, State.CHECKING)
            && !state.compareAndSet(State.UP_TO_DATE, State.CHECKING) && !state.compareAndSet(State.ERROR, State.CHECKING)) {
            reporter?.accept("Update " + state.get().name.lowercase(java.util.Locale.getDefault()) + " already in progress.")
            return
        }
        try {
            val repo = plugin.getConfig().getString("auto-update.github-repo", "CrabCraftMC/CrabCraft")!!
            val token = plugin.getConfig().getString("auto-update.github-token", "")
            val includePre = plugin.getConfig().getBoolean("auto-update.include-prereleases", false)
            val current = plugin.getDescription().getVersion()
            val ua = "CrabUtilities/$current (+https://github.com/$repo)"
            val checker = UpdateChecker(repo, token, "CrabUtilities.jar", ua)
            val info = checker.fetchLatest()
            lastSeen = info
            lastCheck = Instant.now()
            val currentVer = SemVer.parse(current)
            if (currentVer == null) {
                state.set(State.UP_TO_DATE)
                plugin.getLogger().info("Auto-update: running a SNAPSHOT/non-semver build ($current); skipping update check against " + info.tag())
                reporter?.accept("Running a development build ($current); updates only applied to tagged builds.")
                return
            }
            val latestVersion = info.version()
            if (latestVersion == null) {
                state.set(State.ERROR)
                lastError = "Could not parse release tag: " + info.tag()
                reporter?.accept("Latest release tag '" + info.tag() + "' is not a semver.")
                return
            }
            if (info.prerelease() && !includePre) {
                state.set(State.UP_TO_DATE)
                reporter?.accept("Latest release " + info.tag() + " is a pre-release; skipping.")
                return
            }
            if (latestVersion.compareTo(currentVer) <= 0) {
                state.set(State.UP_TO_DATE)
                reporter?.accept("Already up to date ($current).")
                plugin.getLogger().info("Auto-update: already up to date ($current).")
                return
            }
            reporter?.accept("Update available: " + info.tag() + " (current: $current).")
            plugin.getLogger().info("Auto-update: " + info.tag() + " available (current: $current)")
            if (!download) { state.set(State.IDLE); return }
            state.set(State.DOWNLOADING)
            val dl = UpdateDownloader(token, ua)
            val pluginsDir = plugin.getDataFolder().getParentFile()
            val updateDir = pluginsDir.toPath().resolve("update")
            val targetName = plugin.getPluginJarFile().getName()
            val out = dl.download(info, updateDir, targetName)
            state.set(State.READY)
            val msg = "Staged " + info.tag() + " at $out; restart the server to apply."
            plugin.getLogger().info("Auto-update: $msg")
            reporter?.accept(msg)
            if (plugin.getConfig().getBoolean("auto-update.notify-ops", true)) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    for (p in Bukkit.getOnlinePlayers()) if (p.isOp) {
                        p.sendMessage(CrabMessages.success("Update ").append(CrabMessages.highlight(info.tag()))
                            .append(CrabMessages.success(" staged. Restart the server to apply.")))
                    }
                })
            }
        } catch (e: UpdateExceptions.RateLimitedException) {
            state.set(State.ERROR)
            lastError = e.message
            plugin.getLogger().warning("Auto-update: " + e.message)
            reporter?.accept(e.message!!)
        } catch (e: UpdateExceptions.NoReleaseException) {
            state.set(State.UP_TO_DATE)
            lastError = null
            plugin.getLogger().info("Auto-update: no releases published yet for this repo.")
            reporter?.accept("No releases published yet.")
        } catch (e: Exception) {
            state.set(State.ERROR)
            lastError = e.javaClass.simpleName + ": " + e.message
            plugin.getLogger().warning("Auto-update failed: " + lastError)
            reporter?.accept("Update failed: " + lastError)
        }
    }
}
