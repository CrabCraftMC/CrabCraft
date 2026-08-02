package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.mp3.Mp3Decoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import crabcraft.net.crabUtilities.media.VoiceMediaRegistry;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Plays the two bundled call tones only to their intended local voice-chat player. */
final class CallRingtonePlayer implements AutoCloseable {
    static final long MAX_RING_DURATION_MILLIS = 30_000L;
    static final int FRAME_SAMPLES = 960;
    private static final int SAMPLES_PER_MILLISECOND = 48;
    private static final String INCOMING_RESOURCE = "crabcraft/call/incoming_ringtone.mp3";
    private static final String OUTGOING_RESOURCE = "crabcraft/call/outgoing_ringtone.mp3";

    private final VoicechatServerApi api;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<VoiceMessages.RingDirection, short[]> clips;
    private final Map<SessionKey, Endpoint> endpoints = new ConcurrentHashMap<>();
    private volatile boolean closed;

    CallRingtonePlayer(VoicechatServerApi api, Logger logger) throws IOException {
        this(api, decodeClip(api, INCOMING_RESOURCE), decodeClip(api, OUTGOING_RESOURCE),
                System::currentTimeMillis, logger);
    }

    CallRingtonePlayer(VoicechatServerApi api, short[] incoming, short[] outgoing,
                       LongSupplier clock, Logger logger) {
        if (incoming.length == 0 || outgoing.length == 0) {
            throw new IllegalArgumentException("Call ringtone clips must not be empty");
        }
        this.api = api;
        this.logger = logger;
        this.clock = clock;
        this.clips = new EnumMap<>(VoiceMessages.RingDirection.class);
        clips.put(VoiceMessages.RingDirection.INCOMING, incoming);
        clips.put(VoiceMessages.RingDirection.OUTGOING, outgoing);
    }

    void start(VoiceMessages.CallRingStart start) {
        if (closed) return;
        long now = clock.getAsLong();
        if (start.expiresAtMillis() <= now) return;

        VoicechatConnection connection = api.getConnectionOf(start.playerId());
        if (connection == null || !connection.isInstalled() || !connection.isConnected()) return;

        // A forged or badly skewed absolute deadline can never make a backend
        // ring for more than the invitation's intended 30-second lifetime.
        long maximumDeadline = now > Long.MAX_VALUE - MAX_RING_DURATION_MILLIS
                ? Long.MAX_VALUE : now + MAX_RING_DURATION_MILLIS;
        long safeDeadline = Math.min(start.expiresAtMillis(), maximumDeadline);
        SessionKey key = new SessionKey(start.playerId(), start.direction());
        Endpoint endpoint = endpoints.computeIfAbsent(key, this::createEndpoint);
        if (endpoint != null) endpoint.start(start.token(), safeDeadline, connection);
    }

    void stop(VoiceMessages.CallRingStop stop) {
        Endpoint endpoint = endpoints.get(new SessionKey(stop.playerId(), stop.direction()));
        if (endpoint != null) endpoint.stopToken(stop.token());
    }

    void removePlayer(UUID playerId) {
        for (VoiceMessages.RingDirection direction : VoiceMessages.RingDirection.values()) {
            Endpoint endpoint = endpoints.remove(new SessionKey(playerId, direction));
            if (endpoint != null) endpoint.close();
        }
    }

    private Endpoint createEndpoint(SessionKey key) {
        if (closed) return null;
        try {
            StaticAudioChannel channel = api.createStaticAudioChannel(channelId(key));
            if (channel == null) {
                logger.warning("Could not create the " + key.direction().name().toLowerCase()
                        + " call ringtone channel for " + key.playerId());
                return null;
            }
            channel.setBypassGroupIsolation(true);
            channel.setCategory(VoiceMediaRegistry.CALL_CATEGORY);
            channel.setFilter(player -> key.playerId().equals(player.getUuid()));
            return new Endpoint(channel, clips.get(key.direction()));
        } catch (Exception e) {
            logger.warning("Could not initialise a call ringtone channel for " + key.playerId());
            return null;
        }
    }

    private static UUID channelId(SessionKey key) {
        return UUID.nameUUIDFromBytes(("crabcraft:svc:call-ringtone:"
                + key.playerId() + ':' + key.direction().name())
                .getBytes(StandardCharsets.UTF_8));
    }

    private static short[] decodeClip(VoicechatServerApi api, String resource) throws IOException {
        try (InputStream input = CallRingtonePlayer.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing bundled call ringtone " + resource);
            Mp3Decoder decoder = api.createMp3Decoder(input);
            if (decoder == null) throw new IOException("Could not create MP3 decoder for " + resource);
            AudioFormat format = decoder.getAudioFormat();
            if (format.getChannels() != 1 || Math.round(format.getSampleRate()) != 48_000) {
                throw new IOException("Call ringtone must be 48 kHz mono: " + resource);
            }
            short[] decoded = decoder.decode();
            if (decoded == null || decoded.length == 0) {
                throw new IOException("Call ringtone decoded to no audio: " + resource);
            }
            return decoded;
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Endpoint endpoint : endpoints.values()) endpoint.close();
        endpoints.clear();
    }

    private record SessionKey(UUID playerId, VoiceMessages.RingDirection direction) {}

    private final class Endpoint implements AutoCloseable {
        private final StaticAudioChannel channel;
        private final short[] clip;
        private VoicechatConnection target;
        private Playback playback;
        private boolean endpointClosed;

        private Endpoint(StaticAudioChannel channel, short[] clip) {
            this.channel = channel;
            this.clip = clip;
        }

        synchronized void start(String token, long deadline, VoicechatConnection connection) {
            if (closed || endpointClosed) return;
            if (!setTarget(connection)) return;
            if (playback != null && playback.frames.addToken(token, deadline)) return;
            if (playback != null) {
                Playback stale = playback;
                playback = null;
                stale.stop();
            }

            LoopingFrames frames = new LoopingFrames(clip, clock);
            frames.addToken(token, deadline);
            OpusEncoder encoder = null;
            Playback opened = null;
            try {
                encoder = api.createEncoder(OpusEncoderMode.AUDIO);
                AudioPlayer player = api.createAudioPlayer(channel, encoder, frames);
                opened = new Playback(player, encoder, frames);
                Playback callbackPlayback = opened;
                player.setOnStopped(() -> onPlaybackStopped(callbackPlayback));
                playback = opened;
                player.startPlaying();
            } catch (Exception e) {
                if (playback == opened) playback = null;
                if (opened != null) opened.stop();
                else if (encoder != null) try { encoder.close(); } catch (Exception ignored) {}
                logger.warning("Could not start a call ringtone");
            }
        }

        void stopToken(String token) {
            Playback stopped = null;
            synchronized (this) {
                if (playback != null && playback.frames.removeToken(token)) {
                    stopped = playback;
                    playback = null;
                }
            }
            if (stopped != null) stopped.stop();
        }

        private void onPlaybackStopped(Playback stopped) {
            synchronized (this) {
                if (playback == stopped) playback = null;
            }
            stopped.release(false);
        }

        private boolean setTarget(VoicechatConnection connection) {
            if (target == connection) return true;
            if (target != null) {
                try { channel.removeTarget(target); } catch (Exception ignored) {}
            }
            try {
                channel.addTarget(connection);
                target = connection;
                return true;
            } catch (Exception e) {
                target = null;
                return false;
            }
        }

        @Override
        public void close() {
            Playback stopped;
            VoicechatConnection removedTarget;
            synchronized (this) {
                if (endpointClosed) return;
                endpointClosed = true;
                stopped = playback;
                playback = null;
                removedTarget = target;
                target = null;
            }
            if (stopped != null) stopped.stop();
            if (removedTarget != null) {
                try { channel.removeTarget(removedTarget); } catch (Exception ignored) {}
            }
            try { channel.flush(); } catch (Exception ignored) {}
            try { channel.clearTargets(); } catch (Exception ignored) {}
        }
    }

    private static final class Playback {
        private final AudioPlayer player;
        private final OpusEncoder encoder;
        private final LoopingFrames frames;
        private final AtomicBoolean released = new AtomicBoolean();

        private Playback(AudioPlayer player, OpusEncoder encoder, LoopingFrames frames) {
            this.player = player;
            this.encoder = encoder;
            this.frames = frames;
        }

        private void stop() {
            frames.stop();
            release(true);
        }

        private void release(boolean stopPlayer) {
            if (!released.compareAndSet(false, true)) return;
            if (stopPlayer) try { player.stopPlaying(); } catch (Exception ignored) {}
            try { encoder.close(); } catch (Exception ignored) {}
        }
    }

    /** Token-aware, deadline-limited 20 ms frame source shared by one direction. */
    static final class LoopingFrames implements Supplier<short[]> {
        private final short[] clip;
        private final LongSupplier clock;
        private final Map<String, Long> deadlines = new HashMap<>();
        private int cursor;
        private boolean stopped;

        LoopingFrames(short[] clip, LongSupplier clock) {
            if (clip.length == 0) throw new IllegalArgumentException("Ringtone clip is empty");
            this.clip = clip;
            this.clock = clock;
        }

        synchronized boolean addToken(String token, long deadline) {
            if (stopped) return false;
            deadlines.merge(token, deadline, Math::max);
            return true;
        }

        /** Returns true only when removing this exact token ends the playback. */
        synchronized boolean removeToken(String token) {
            if (deadlines.remove(token) == null || !deadlines.isEmpty()) return false;
            stopped = true;
            return true;
        }

        synchronized void stop() {
            stopped = true;
            deadlines.clear();
        }

        @Override
        public synchronized short[] get() {
            if (stopped) return null;
            long now = clock.getAsLong();
            deadlines.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (deadlines.isEmpty()) {
                stopped = true;
                return null;
            }

            long finalDeadline = deadlines.values().stream()
                    .mapToLong(Long::longValue).max().orElse(now);
            long remainingMillis = finalDeadline - now;
            if (remainingMillis <= 0L) {
                stopped = true;
                return null;
            }
            int audibleSamples = (int) Math.min(FRAME_SAMPLES,
                    remainingMillis * SAMPLES_PER_MILLISECOND);
            short[] frame = new short[FRAME_SAMPLES];
            for (int sample = 0; sample < audibleSamples; sample++) {
                frame[sample] = clip[cursor++];
                if (cursor == clip.length) cursor = 0;
            }
            return frame;
        }
    }
}
