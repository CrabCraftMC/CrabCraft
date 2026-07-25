package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stateful Opus decode/scale/re-encode pipeline, isolated per speaker. */
final class OpusVolumeScaler implements AutoCloseable {
    private final VoicechatServerApi api;
    private final double gain;
    private final Map<UUID, SpeakerState> speakers = new ConcurrentHashMap<>();

    OpusVolumeScaler(VoicechatServerApi api, double gain) {
        this.api = api;
        this.gain = clampGain(gain);
    }

    byte[] scale(UUID speakerId, long sequence, byte[] opus) {
        return scale(speakerId, sequence, true, opus);
    }

    byte[] scaleNext(UUID speakerId, byte[] opus) {
        return scale(speakerId, 0L, false, opus);
    }

    private byte[] scale(UUID speakerId, long sequence, boolean cacheBySequence, byte[] opus) {
        if (opus == null || gain >= 1D) return opus;
        SpeakerState state = speakers.computeIfAbsent(speakerId,
                ignored -> new SpeakerState(api.createDecoder(), api.createEncoder(OpusEncoderMode.VOIP)));
        synchronized (state) {
            if (cacheBySequence && state.cached != null && state.sequence == sequence) return state.cached;
            if (opus.length == 0) {
                state.decoder.resetState();
                state.encoder.resetState();
                state.sequence = sequence;
                state.cached = opus;
                return opus;
            }
            short[] pcm = state.decoder.decode(opus);
            applyGain(pcm, gain);
            byte[] scaled = state.encoder.encode(pcm);
            state.sequence = sequence;
            state.cached = scaled;
            return scaled;
        }
    }

    static void applyGain(short[] samples, double gain) {
        double bounded = clampGain(gain);
        for (int i = 0; i < samples.length; i++) {
            long scaled = Math.round(samples[i] * bounded);
            samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
    }

    private static double clampGain(double gain) {
        return Math.max(0D, Math.min(1D, gain));
    }

    void remove(UUID speakerId) {
        SpeakerState state = speakers.remove(speakerId);
        if (state != null) state.close();
    }

    @Override
    public void close() {
        speakers.values().forEach(SpeakerState::close);
        speakers.clear();
    }

    private static final class SpeakerState {
        final OpusDecoder decoder;
        final OpusEncoder encoder;
        long sequence = Long.MIN_VALUE;
        byte[] cached;

        SpeakerState(OpusDecoder decoder, OpusEncoder encoder) {
            this.decoder = decoder;
            this.encoder = encoder;
        }

        void close() {
            try { decoder.close(); } catch (Exception ignored) {}
            try { encoder.close(); } catch (Exception ignored) {}
        }
    }
}
