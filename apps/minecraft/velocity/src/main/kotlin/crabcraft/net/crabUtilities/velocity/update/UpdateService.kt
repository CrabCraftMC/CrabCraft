package crabcraft.net.crabUtilities.velocity.update

import com.velocitypowered.api.scheduler.ScheduledTask
import crabcraft.net.crabUtilities.update.ReleaseInfo
import crabcraft.net.crabUtilities.update.SemVer
import crabcraft.net.crabUtilities.update.UpdateChecker
import crabcraft.net.crabUtilities.update.UpdateDownloader
import crabcraft.net.crabUtilities.update.UpdateExceptions
import crabcraft.net.crabUtilities.velocity.BuildInfo
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

open class UpdateService(private val plugin: CrabUtilitiesVelocity) {
    enum class State {
        IDLE,
        CHECKING,
        DOWNLOADING,
        READY,
        UP_TO_DATE,
        ERROR,
    }

    private val server = plugin.getServer()
    private val logger = plugin.getLogger()
    private val state = AtomicReference(State.IDLE)
    @Volatile private var lastCheck: Instant? = null
    @Volatile private var lastSeen: ReleaseInfo? = null
    @Volatile private var lastError: String? = null
    private var periodic: ScheduledTask? = null

    open fun start() {
        val hours = maxOf(1L, plugin.getConfig().getUpdateCheckIntervalHours())
        periodic =
            server.scheduler
                .buildTask(plugin, Runnable { runCheck(true, null) })
                .delay(Duration.ofMinutes(2))
                .repeat(Duration.ofHours(hours))
                .schedule()
        logger.info("Auto-update enabled; checking every {}h", hours)
    }

    open fun shutdown() {
        periodic?.cancel()
        periodic = null
    }

    open fun getState(): State = state.get()

    open fun getLastCheck(): Instant? = lastCheck

    open fun getLastSeen(): ReleaseInfo? = lastSeen

    open fun getLastError(): String? = lastError

    /** Must be called off-main. */
    open fun runCheck(download: Boolean, reporter: Consumer<Component>?) {
        if (
            !state.compareAndSet(State.IDLE, State.CHECKING) &&
                !state.compareAndSet(State.READY, State.CHECKING) &&
                !state.compareAndSet(State.UP_TO_DATE, State.CHECKING) &&
                !state.compareAndSet(State.ERROR, State.CHECKING)
        ) {
            reporter?.accept(
                Component.text(
                    "Update ${state.get().name.lowercase(java.util.Locale.getDefault())} already in progress.",
                    NamedTextColor.YELLOW,
                )
            )
            return
        }
        try {
            val repo = plugin.getConfig().getUpdateGithubRepo()
            val token = plugin.getConfig().getUpdateGithubToken()
            val includePre = plugin.getConfig().isUpdateIncludePrereleases()
            val current = BuildInfo.VERSION
            val userAgent = "CrabUtilities-Velocity/$current (+https://github.com/$repo)"
            val checker = UpdateChecker(repo, token, "CrabUtilities-Velocity.jar", userAgent)
            val info = checker.fetchLatest()
            lastSeen = info
            lastCheck = Instant.now()
            val currentVersion = SemVer.parse(current)
            if (currentVersion == null) {
                state.set(State.UP_TO_DATE)
                logger.info(
                    "Auto-update: running a SNAPSHOT/non-semver build ({}); skipping update check against {}",
                    current,
                    info.tag(),
                )
                reporter?.accept(
                    Component.text(
                        "Running a development build ($current); updates only applied to tagged builds.",
                        NamedTextColor.GRAY,
                    )
                )
                return
            }
            val version = info.version()
            if (version == null) {
                state.set(State.ERROR)
                lastError = "Could not parse release tag: ${info.tag()}"
                reporter?.accept(
                    Component.text("Latest release tag '${info.tag()}' is not a semver.", NamedTextColor.RED)
                )
                return
            }
            if (info.prerelease() && !includePre) {
                state.set(State.UP_TO_DATE)
                reporter?.accept(
                    Component.text("Latest release ${info.tag()} is a pre-release; skipping.", NamedTextColor.GRAY)
                )
                return
            }
            if (version <= currentVersion) {
                state.set(State.UP_TO_DATE)
                reporter?.accept(Component.text("Already up to date ($current).", NamedTextColor.GREEN))
                logger.info("Auto-update: already up to date ({})", current)
                return
            }
            reporter?.accept(
                Component.text("Update available: ${info.tag()} (current: $current).", NamedTextColor.YELLOW)
            )
            logger.info("Auto-update: {} available (current: {})", info.tag(), current)
            if (!download) {
                state.set(State.IDLE)
                return
            }
            state.set(State.DOWNLOADING)
            val downloader = UpdateDownloader(token, userAgent)
            val liveFilename = resolveLiveJarFilename()
            val output = downloader.download(info, resolvePluginsDir(), "$liveFilename.staged")
            state.set(State.READY)
            val message =
                "Staged ${info.tag()} at $output; stop the proxy, replace $liveFilename with the .staged file, then start."
            logger.info("Auto-update: {}", message)
            reporter?.accept(Component.text(message, NamedTextColor.GREEN))
        } catch (e: UpdateExceptions.RateLimitedException) {
            state.set(State.ERROR)
            lastError = e.message
            logger.warn("Auto-update: {}", e.message)
            reporter?.accept(Component.text(e.message!!, NamedTextColor.RED))
        } catch (_: UpdateExceptions.NoReleaseException) {
            state.set(State.UP_TO_DATE)
            lastError = null
            logger.info("Auto-update: no releases published yet for this repo.")
            reporter?.accept(Component.text("No releases published yet.", NamedTextColor.GRAY))
        } catch (e: Exception) {
            state.set(State.ERROR)
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            logger.warn("Auto-update failed: {}", lastError)
            reporter?.accept(Component.text("Update failed: $lastError", NamedTextColor.RED))
        }
    }

    private fun resolvePluginsDir(): Path {
        val directory = plugin.getDataDirectory()
        return directory.parent ?: directory
    }

    private fun resolveLiveJarFilename(): String {
        try {
            val source = plugin.javaClass.protectionDomain.codeSource
            if (source?.location != null) {
                val path = Paths.get(source.location.toURI()).fileName.toString()
                if (path.endsWith(".jar")) return path
            }
        } catch (_: Exception) {}
        return "CrabUtilities-Velocity.jar"
    }
}
