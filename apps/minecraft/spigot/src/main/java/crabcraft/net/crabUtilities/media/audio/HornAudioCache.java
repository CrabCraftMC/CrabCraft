package crabcraft.net.crabUtilities.media.audio;

import crabcraft.net.crabUtilities.media.MediaFeature;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Disk-backed cache of the first few seconds of decoded horn audio. Each entry is a small raw PCM
 * file (48 kHz mono s16le, ~0.7 MB at 7s) named by a hash of source URL + volume, stored under
 * {@code <data folder>/horn-cache/}. Survives restarts and keeps the audio out of the heap; only the
 * clip currently being played is read into memory. Bounded by an LRU over file count
 * (least-recently-played evicted first).
 */
public final class HornAudioCache {
  private static final int FRAME_SAMPLES = 960;          // 20ms @ 48kHz, must match FfmpegPcmStream
  private static final int FRAME_BYTES = FRAME_SAMPLES * 2; // s16le mono
  private static final String EXT = ".pcm";

  private final File dir;
  private volatile int maxFiles = 50;

  public HornAudioCache(File dir) {
    this.dir = dir;
    if (!dir.exists() && !dir.mkdirs()) {
      MediaFeature.warn("Could not create horn cache directory {}", dir.getAbsolutePath());
    }
  }

  public void setMaxFiles(int maxFiles) {
    this.maxFiles = Math.max(0, maxFiles);
  }

  private File fileFor(String key) {
    return new File(dir, hash(key) + EXT);
  }

  public boolean has(String key) {
    File f = fileFor(key);
    return f.isFile() && f.length() > 0;
  }

  /** Reads a cached entry into 960-sample frames, touching it for LRU, or {@code null} on miss/error. */
  public List<short[]> read(String key) {
    File f = fileFor(key);
    if (!f.isFile() || f.length() == 0) return null;
    try {
      byte[] bytes = Files.readAllBytes(f.toPath());
      int frameCount = bytes.length / FRAME_BYTES;
      if (frameCount == 0) return null;
      List<short[]> frames = new ArrayList<>(frameCount);
      for (int i = 0; i < frameCount; i++) {
        short[] frame = new short[FRAME_SAMPLES];
        int off = i * FRAME_BYTES;
        for (int s = 0; s < FRAME_SAMPLES; s++) {
          frame[s] = (short) ((bytes[off + s * 2] & 0xFF) | (bytes[off + s * 2 + 1] << 8));
        }
        frames.add(frame);
      }
      if (!f.setLastModified(System.currentTimeMillis())) { // touch -> least-recently-played eviction
        MediaFeature.debug("Could not touch horn cache {} for LRU ordering", f.getName());
      }
      return frames;
    } catch (IOException e) {
      MediaFeature.warn("Failed reading horn cache {}: {}", f.getName(), e.getMessage());
      return null;
    }
  }

  /** Writes frames as raw s16le to disk (atomically), then evicts the oldest entries past the cap. */
  public void write(String key, List<short[]> frames) {
    if (maxFiles <= 0 || frames == null || frames.isEmpty()) return;
    File f = fileFor(key);
    File tmp = new File(dir, f.getName() + ".tmp");
    try (OutputStream out = new BufferedOutputStream(new java.io.FileOutputStream(tmp))) {
      byte[] buf = new byte[FRAME_BYTES];
      for (short[] frame : frames) {
        for (int s = 0; s < FRAME_SAMPLES; s++) {
          buf[s * 2] = (byte) (frame[s] & 0xFF);
          buf[s * 2 + 1] = (byte) ((frame[s] >> 8) & 0xFF);
        }
        out.write(buf);
      }
      out.flush();
      try {
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicFailed) {
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      evict();
    } catch (IOException e) {
      MediaFeature.warn("Failed writing horn cache {}: {}", f.getName(), e.getMessage());
      tmp.delete();
    }
  }

  private void evict() {
    File[] files = dir.listFiles((d, n) -> n.endsWith(EXT));
    if (files == null || files.length <= maxFiles) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    for (int i = 0; i < files.length - maxFiles; i++) {
      if (!files[i].delete()) MediaFeature.debug("Could not evict horn cache {}", files[i].getName());
    }
  }

  public void clear() {
    File[] files = dir.listFiles((d, n) -> n.endsWith(EXT) || n.endsWith(".tmp"));
    if (files != null) for (File f : files) f.delete();
  }

  private static String hash(String key) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(h.length * 2);
      for (byte b : h) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
