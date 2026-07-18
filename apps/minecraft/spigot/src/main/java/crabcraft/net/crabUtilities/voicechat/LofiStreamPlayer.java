package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import net.crabcraft.customdiscs.CDVoiceAddon;
import net.crabcraft.customdiscs.audio.AudioEngine;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Continuously resolves and plays one live stream to local members of the lofi group. */
final class LofiStreamPlayer implements AutoCloseable {
    static final long RETRY_SECONDS = TimeUnit.MINUTES.toSeconds(5L);
    private static final UUID CHANNEL_ID = UUID.nameUUIDFromBytes(
            "crabcraft:svc:lofi:stream".getBytes(StandardCharsets.UTF_8));

    private final VoicechatServerApi api;
    private final UUID groupId;
    private final String sourceUrl;
    private final float musicVolume;
    private final Logger logger;
    private final Map<UUID, VoicechatConnection> targets = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CrabUtilities-Lofi");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;
    private volatile StaticAudioChannel channel;
    private volatile AudioEngine.OpenedStream stream;
    private volatile OpusEncoder encoder;
    private volatile AudioPlayer player;
    private boolean resolutionFailureLogged;

    LofiStreamPlayer(VoicechatServerApi api, UUID groupId, String sourceUrl,
                     float musicVolume, Logger logger) {
        this.api = api;
        this.groupId = groupId;
        this.sourceUrl = sourceUrl;
        this.musicVolume = Math.max(0F, Math.min(1F, musicVolume));
        this.logger = logger;
    }

    void start() {
        if (!openChannel()) {
            logger.warning("Could not create the 24/7 Lofi audio channel");
            return;
        }
        running = true;
        executor.execute(this::playLoop);
    }

    boolean openChannel() {
        StaticAudioChannel opened = api.createStaticAudioChannel(CHANNEL_ID);
        if (opened == null) return false;
        opened.setBypassGroupIsolation(true);
        opened.setCategory(CDVoiceAddon.LOFI_CATEGORY);
        opened.setFilter(serverPlayer -> {
            VoicechatConnection connection = api.getConnectionOf(serverPlayer.getUuid());
            return connection != null && connection.getGroup() != null
                    && groupId.equals(connection.getGroup().getId());
        });
        channel = opened;
        return true;
    }

    void reconcileTarget(VoicechatConnection connection) {
        if (connection == null) return;
        UUID currentGroupId = connection.getGroup() == null ? null : connection.getGroup().getId();
        updateTarget(connection, currentGroupId);
    }

    void updateTarget(VoicechatConnection connection, UUID currentGroupId) {
        if (connection == null) return;
        UUID playerId = connection.getPlayer().getUuid();
        StaticAudioChannel currentChannel = channel;
        if (currentChannel == null) return;

        if (!groupId.equals(currentGroupId)) {
            removeTarget(playerId);
            return;
        }

        VoicechatConnection previous = targets.put(playerId, connection);
        if (previous != null && previous != connection) {
            currentChannel.removeTarget(previous);
        }
        currentChannel.addTarget(connection);
    }

    void removeTarget(UUID playerId) {
        VoicechatConnection connection = targets.remove(playerId);
        StaticAudioChannel currentChannel = channel;
        if (connection != null && currentChannel != null) {
            currentChannel.removeTarget(connection);
        }
    }

    private void playLoop() {
        boolean forceRefresh = false;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (!AudioEngine.getInstance().isReady()) {
                    waitBeforeRetry();
                    continue;
                }

                AudioEngine.OpenedStream opened = AudioEngine.getInstance()
                        .openStream(sourceUrl, musicVolume, forceRefresh);
                forceRefresh = true;
                if (opened == null) {
                    if (!resolutionFailureLogged) {
                        logger.warning("Could not resolve the 24/7 Lofi stream; retrying every "
                                + TimeUnit.SECONDS.toMinutes(RETRY_SECONDS) + " minutes");
                        resolutionFailureLogged = true;
                    }
                    waitBeforeRetry();
                    continue;
                }
                resolutionFailureLogged = false;

                CountDownLatch stopped = new CountDownLatch(1);
                OpusEncoder openedEncoder = api.createEncoder(OpusEncoderMode.AUDIO);
                AudioPlayer openedPlayer = api.createAudioPlayer(channel, openedEncoder, opened.pcm().frames());
                this.stream = opened;
                this.encoder = openedEncoder;
                this.player = openedPlayer;
                openedPlayer.setOnStopped(stopped::countDown);
                if (!running) break;
                logger.info("Playing 24/7 Lofi: " + opened.track().title());
                openedPlayer.startPlaying();
                while (running && !stopped.await(1L, TimeUnit.SECONDS)) {
                    // Wake periodically so shutdown never depends on a callback.
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (running) logger.warning("24/7 Lofi playback failed: " + e.getMessage());
            } finally {
                closeCurrentPlayback();
            }
            if (running) waitBeforeRetry();
        }
    }

    private void waitBeforeRetry() {
        try {
            TimeUnit.SECONDS.sleep(RETRY_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeCurrentPlayback() {
        AudioPlayer currentPlayer = player;
        AudioEngine.OpenedStream currentStream = stream;
        OpusEncoder currentEncoder = encoder;
        player = null;
        stream = null;
        encoder = null;
        if (currentPlayer != null) try { currentPlayer.stopPlaying(); } catch (Exception ignored) {}
        if (currentStream != null) try { currentStream.close(); } catch (Exception ignored) {}
        if (currentEncoder != null) try { currentEncoder.close(); } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        running = false;
        closeCurrentPlayback();
        executor.shutdownNow();
        StaticAudioChannel currentChannel = channel;
        channel = null;
        targets.clear();
        if (currentChannel != null) {
            try { currentChannel.flush(); } catch (Exception ignored) {}
            try { currentChannel.clearTargets(); } catch (Exception ignored) {}
        }
    }
}
