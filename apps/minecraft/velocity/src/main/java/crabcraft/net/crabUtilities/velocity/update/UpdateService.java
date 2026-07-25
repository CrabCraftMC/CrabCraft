package crabcraft.net.crabUtilities.velocity.update;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import crabcraft.net.crabUtilities.velocity.BuildInfo;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class UpdateService {

    public enum State {
        IDLE, CHECKING, DOWNLOADING, READY, UP_TO_DATE, ERROR
    }

    private final CrabUtilitiesVelocity plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    private volatile Instant lastCheck;
    private volatile ReleaseInfo lastSeen;
    private volatile String lastError;
    private ScheduledTask periodic;

    public UpdateService(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
    }

    public void start() {
        long hours = Math.max(1L, plugin.getConfig().getUpdateCheckIntervalHours());
        periodic = server.getScheduler()
                .buildTask(plugin, () -> runCheck(true, null))
                .delay(Duration.ofMinutes(2))
                .repeat(Duration.ofHours(hours))
                .schedule();
        logger.info("Auto-update enabled; checking every {}h", hours);
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

    /** Must be called off-main. */
    public void runCheck(boolean download, Consumer<Component> reporter) {
        if (!state.compareAndSet(State.IDLE, State.CHECKING)
                && !state.compareAndSet(State.READY, State.CHECKING)
                && !state.compareAndSet(State.UP_TO_DATE, State.CHECKING)
                && !state.compareAndSet(State.ERROR, State.CHECKING)) {
            if (reporter != null) reporter.accept(Component.text(
                    "Update " + state.get().name().toLowerCase() + " already in progress.",
                    NamedTextColor.YELLOW));
            return;
        }

        try {
            String repo = plugin.getConfig().getUpdateGithubRepo();
            String token = plugin.getConfig().getUpdateGithubToken();
            boolean includePre = plugin.getConfig().isUpdateIncludePrereleases();
            String current = BuildInfo.VERSION;
            String ua = "CrabUtilities-Velocity/" + current + " (+https://github.com/" + repo + ")";

            UpdateChecker checker = new UpdateChecker(repo, token, "CrabUtilities-Velocity.jar", ua);
            ReleaseInfo info = checker.fetchLatest();
            lastSeen = info;
            lastCheck = Instant.now();

            SemVer currentVer = SemVer.parse(current);
            if (currentVer == null) {
                state.set(State.UP_TO_DATE);
                logger.info("Auto-update: running a SNAPSHOT/non-semver build ({}); skipping update check against {}",
                        current, info.tag());
                if (reporter != null) reporter.accept(Component.text(
                        "Running a development build (" + current + "); updates only applied to tagged builds.",
                        NamedTextColor.GRAY));
                return;
            }
            if (info.version() == null) {
                state.set(State.ERROR);
                lastError = "Could not parse release tag: " + info.tag();
                if (reporter != null) reporter.accept(Component.text(
                        "Latest release tag '" + info.tag() + "' is not a semver.",
                        NamedTextColor.RED));
                return;
            }
            if (info.prerelease() && !includePre) {
                state.set(State.UP_TO_DATE);
                if (reporter != null) reporter.accept(Component.text(
                        "Latest release " + info.tag() + " is a pre-release; skipping.",
                        NamedTextColor.GRAY));
                return;
            }
            if (info.version().compareTo(currentVer) <= 0) {
                state.set(State.UP_TO_DATE);
                if (reporter != null) reporter.accept(Component.text(
                        "Already up to date (" + current + ").", NamedTextColor.GREEN));
                logger.info("Auto-update: already up to date ({})", current);
                return;
            }

            if (reporter != null) reporter.accept(Component.text(
                    "Update available: " + info.tag() + " (current: " + current + ").",
                    NamedTextColor.YELLOW));
            logger.info("Auto-update: {} available (current: {})", info.tag(), current);

            if (!download) {
                state.set(State.IDLE);
                return;
            }

            state.set(State.DOWNLOADING);
            UpdateDownloader dl = new UpdateDownloader(token, ua);

            Path pluginsDir = resolvePluginsDir();
            String liveFilename = resolveLiveJarFilename();
            String stagedFilename = liveFilename + ".staged";
            Path out = dl.download(info, pluginsDir, stagedFilename);

            state.set(State.READY);
            String msg = "Staged " + info.tag() + " at " + out
                    + "; stop the proxy, replace " + liveFilename + " with the .staged file, then start.";
            logger.info("Auto-update: {}", msg);
            if (reporter != null) reporter.accept(Component.text(msg, NamedTextColor.GREEN));
        } catch (UpdateExceptions.RateLimitedException e) {
            state.set(State.ERROR);
            lastError = e.getMessage();
            logger.warn("Auto-update: {}", e.getMessage());
            if (reporter != null) reporter.accept(Component.text(e.getMessage(), NamedTextColor.RED));
        } catch (UpdateExceptions.NoReleaseException e) {
            state.set(State.UP_TO_DATE);
            lastError = null;
            logger.info("Auto-update: no releases published yet for this repo.");
            if (reporter != null) reporter.accept(Component.text(
                    "No releases published yet.", NamedTextColor.GRAY));
        } catch (Exception e) {
            state.set(State.ERROR);
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            logger.warn("Auto-update failed: {}", lastError);
            if (reporter != null) reporter.accept(Component.text(
                    "Update failed: " + lastError, NamedTextColor.RED));
        }
    }

    private Path resolvePluginsDir() {
        Path dataDir = plugin.getDataDirectory();
        Path parent = dataDir.getParent();
        return parent != null ? parent : dataDir;
    }

    private String resolveLiveJarFilename() {
        try {
            CodeSource cs = plugin.getClass().getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                String path = Paths.get(cs.getLocation().toURI()).getFileName().toString();
                if (path.endsWith(".jar")) return path;
            }
        } catch (Exception ignored) {
        }
        return "CrabUtilities-Velocity.jar";
    }
}
