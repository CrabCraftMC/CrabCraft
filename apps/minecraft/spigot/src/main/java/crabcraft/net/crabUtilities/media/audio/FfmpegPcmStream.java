package crabcraft.net.crabUtilities.media.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Decodes a stream URL to 48 kHz / mono / s16le PCM via ffmpeg and exposes 20 ms (960-sample)
 * frames as a {@link Supplier} for Simple Voice Chat's createAudioPlayer.
 * The supplier returns {@code null} at end-of-stream. {@link #close()} kills ffmpeg.
 */
public final class FfmpegPcmStream {
  private static final int FRAME_SAMPLES = 960;     // 20ms @ 48kHz
  private static final int FRAME_BYTES = FRAME_SAMPLES * 2; // s16le mono
  private static final short[] END = new short[0];  // queue sentinel

  private final Process process;
  private final BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(50); // ~1s buffer
  private volatile boolean closed = false;

  public FfmpegPcmStream(String ffmpegPath, String streamUrl, float volume,
                         MediaDestinationPolicy destinationPolicy, MediaPolicyProxy policyProxy)
      throws IOException {
    this(ffmpegPath, streamUrl, volume, destinationPolicy, policyProxy, 0);
  }

  /**
   * @param maxSeconds when {@code > 0}, caps the decoded audio to this many seconds (ffmpeg {@code -t}).
   *                   Used by goat horns to truncate playback at the cooldown length.
   */
  public FfmpegPcmStream(String ffmpegPath, String streamUrl, float volume,
                         MediaDestinationPolicy destinationPolicy, MediaPolicyProxy policyProxy,
                         int maxSeconds) throws IOException {
    destinationPolicy.approve(streamUrl);
    if (!Float.isFinite(volume)) throw new IOException("audio volume must be finite");
    List<String> command = command(ffmpegPath, streamUrl, volume, policyProxy.url(), maxSeconds);
    ProcessBuilder pb = new ProcessBuilder(command)
      .redirectError(ProcessBuilder.Redirect.DISCARD); // drain stderr to avoid pipe deadlock
    pb.environment().keySet().removeIf(key ->
      key.equalsIgnoreCase("http_proxy")
        || key.equalsIgnoreCase("https_proxy")
        || key.equalsIgnoreCase("all_proxy")
        || key.equalsIgnoreCase("no_proxy"));
    pb.environment().put("http_proxy", policyProxy.url());
    pb.environment().put("https_proxy", policyProxy.url());
    this.process = pb.start();

    Thread reader = new Thread(this::readLoop, "CD-ffmpeg-reader");
    reader.setDaemon(true);
    reader.start();
  }

  static List<String> command(String ffmpegPath, String streamUrl, float volume,
                              String policyProxyUrl, int maxSeconds) {
    List<String> command = new ArrayList<>(List.of(
      ffmpegPath,
      "-protocol_whitelist", "http,https,tls,tcp,crypto,httpproxy,data",
      "-rw_timeout", "15000000",
      "-http_proxy", policyProxyUrl,
      "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
      "-i", streamUrl,
      "-vn",
      "-af", "volume=" + Math.max(0f, Math.min(2f, volume)),
      "-f", "s16le", "-ar", "48000", "-ac", "1"));
    if (maxSeconds > 0) {
      command.add("-t");
      command.add(String.valueOf(maxSeconds));
    }
    command.add("pipe:1");
    return command;
  }

  /** Frame supplier for SVC: returns a 960-sample frame, or null when the stream ends. */
  public Supplier<short[]> frames() {
    return () -> {
      if (closed) return null;
      try {
        short[] frame = queue.poll(2, TimeUnit.SECONDS);
        if (frame == END) return null;
        if (frame != null) return frame;
        // underrun: end if ffmpeg has exited (true EOF), else emit silence (transient stall)
        return process.isAlive() ? new short[FRAME_SAMPLES] : null;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    };
  }

  private void readLoop() {
    byte[] buf = new byte[FRAME_BYTES];
    try (InputStream in = process.getInputStream()) {
      while (!closed) {
        int read = readFully(in, buf);
        if (read <= 0) break;               // EOF
        short[] frame = new short[FRAME_SAMPLES];
        int samples = read / 2;
        for (int i = 0; i < samples; i++) {
          frame[i] = (short) ((buf[i * 2] & 0xFF) | (buf[i * 2 + 1] << 8)); // little-endian
        }
        // remaining samples (short final frame) stay zero -> silence padding
        queue.put(frame);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException ignored) {
      // process killed / pipe closed
    } finally {
      // non-blocking: close() clears the queue (guaranteeing room) and the consumer drains
      // at EOF, so END is always enqueued; never block here.
      queue.offer(END);
    }
  }

  /** Reads up to buf.length bytes; returns bytes read (may be a short final frame), or -1 at immediate EOF. */
  private static int readFully(InputStream in, byte[] buf) throws IOException {
    int off = 0;
    while (off < buf.length) {
      int n = in.read(buf, off, buf.length - off);
      if (n < 0) return off == 0 ? -1 : off;
      off += n;
    }
    return off;
  }

  public void close() {
    closed = true;
    try { process.destroyForcibly(); } catch (Exception ignored) {}
    queue.clear();
    queue.offer(END);
  }
}
