package crabcraft.net.crabUtilities.media.audio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import crabcraft.net.crabUtilities.media.MediaFeature;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Resolves any media URL to a direct stream URL + title via yt-dlp. Blocking; call off the main thread. */
public final class TrackResolver {
  private static final long CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);
  private static final int MAX_OUTPUT_BYTES = 64 * 1024;

  private final BinaryProvisioner binaries;
  private final MediaDestinationPolicy destinationPolicy;
  private final MediaPolicyProxy policyProxy;
  private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
  private final Map<String, String> loggedFailures = new ConcurrentHashMap<>();

  public TrackResolver(BinaryProvisioner binaries, MediaDestinationPolicy destinationPolicy,
                       MediaPolicyProxy policyProxy) {
    this.binaries = binaries;
    this.destinationPolicy = destinationPolicy;
    this.policyProxy = policyProxy;
  }

  /** A resolved track. {@code durationSeconds} is {@code null} when unknown (e.g. live streams). */
  public record ResolvedTrack(String streamUrl, String title, Integer durationSeconds) {}
  private record CacheKey(String url, String cookies) {}
  private record CacheEntry(ResolvedTrack track, long expiresAtNanos) {}

  /**
   * Runs yt-dlp with one compact JSON output object whose fields retain their provenance.
   * @return the resolved track, or {@code null} if resolution failed / produced no URL.
   */
  public ResolvedTrack resolve(String url) {
    return resolve(url, false);
  }

  /** Resolves a source, optionally bypassing a cached CDN URL after a stream failure. */
  public ResolvedTrack resolve(String url, boolean forceRefresh) {
    String cookies = effectiveCookies();
    CacheKey key = new CacheKey(url, cookies);
    long now = System.nanoTime();
    if (forceRefresh) cache.remove(key);
    CacheEntry cached = cache.get(key);
    if (cached != null) {
      if (cached.expiresAtNanos() > now) {
        loggedFailures.remove(url);
        return cached.track();
      }
      cache.remove(key, cached);
    }

    ResolvedTrack resolved = resolveUncached(url, cookies);
    if (resolved != null) {
      cache.put(key, new CacheEntry(resolved, now + CACHE_TTL_NANOS));
    }
    return resolved;
  }

  private ResolvedTrack resolveUncached(String url, String cookies) {
    String ytDlp = binaries.getYtDlpPath();
    if (ytDlp == null) return null;
    try {
      destinationPolicy.approve(url);
    } catch (IOException e) {
      if (shouldLogFailure(url, "destination policy: " + e.getMessage())) {
        MediaFeature.warn("Rejected media source {}: {}", url, e.getMessage());
      }
      return null;
    }
    // --no-cache-dir: never reuse a cached SoundCloud client_id (a stale one 404s ALL
    // SoundCloud until manually cleared); fetch a fresh one each resolve.
    List<String> cmd = command(ytDlp, cookies, policyProxy.url(), url);

    // yt-dlp's onefile build occasionally fails to self-extract its bundled libs on a cold run;
    // that failure is transient, so retry the same command a few times before giving up.
    for (int attempt = 1; attempt <= BinaryProvisioner.SELF_EXTRACT_ATTEMPTS; attempt++) {
      Attempt result = runOnce(cmd, url);
      if (result.track() != null) {
        loggedFailures.remove(url);
        return result.track();
      }
      if (!result.retryable()) return null; // hard failure, already logged
      if (attempt == BinaryProvisioner.SELF_EXTRACT_ATTEMPTS) {
        MediaFeature.warn("yt-dlp self-extraction still failing after {} attempts for {}",
          BinaryProvisioner.SELF_EXTRACT_ATTEMPTS, url);
        return null;
      }
      MediaFeature.warn("yt-dlp self-extraction failed for {} (attempt {}/{}); retrying",
        url, attempt, BinaryProvisioner.SELF_EXTRACT_ATTEMPTS);
      try {
        Thread.sleep(200L * attempt);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  static List<String> command(String ytDlp, String cookies, String policyProxyUrl, String url) {
    List<String> command = new ArrayList<>(List.of(
      ytDlp, "--ignore-config", "-f", "bestaudio/best", "--no-playlist", "--no-warnings",
      "--no-cache-dir", "--socket-timeout", "15", "--proxy", policyProxyUrl));
    if (!cookies.isBlank()) {
      command.add("--cookies");
      command.add(cookies);
    }
    command.add("--print");
    command.add("%(.{title,url,duration})j");
    command.add("--");
    command.add(url);
    return command;
  }

  /** Outcome of a single yt-dlp run: {@code track} on success, else {@code retryable} for a transient failure. */
  private record Attempt(ResolvedTrack track, boolean retryable) {}

  private Attempt runOnce(List<String> cmd, String url) {
    try {
      ProcessBuilder processBuilder = new ProcessBuilder(cmd).redirectErrorStream(false);
      processBuilder.environment().keySet().removeIf(key ->
        key.equalsIgnoreCase("http_proxy")
          || key.equalsIgnoreCase("https_proxy")
          || key.equalsIgnoreCase("all_proxy")
          || key.equalsIgnoreCase("no_proxy"));
      Process p = processBuilder.start();
      boolean done = p.waitFor(30, TimeUnit.SECONDS);
      if (!done) {
        p.destroyForcibly();
        if (shouldLogFailure(url, "timeout")) {
          MediaFeature.warn("yt-dlp timed out resolving {}", url);
        }
        return new Attempt(null, false);
      }
      if (p.exitValue() != 0) {
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (BinaryProvisioner.isSelfExtractionError(err)) return new Attempt(null, true);
        String summary = err.length() > 200 ? err.substring(0, 200) : err;
        if (shouldLogFailure(url, "exit " + p.exitValue() + ": " + summary)) {
          MediaFeature.warn("yt-dlp failed (exit {}) for {}: {}", p.exitValue(), url, summary);
        }
        return new Attempt(null, false);
      }
      byte[] output = p.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
      if (output.length > MAX_OUTPUT_BYTES) {
        if (shouldLogFailure(url, "oversized output")) {
          MediaFeature.warn("yt-dlp returned oversized metadata for {}", url);
        }
        return new Attempt(null, false);
      }
      ResolvedTrack track = parseOutput(new String(output, StandardCharsets.UTF_8));
      destinationPolicy.approve(track.streamUrl());
      return new Attempt(track, false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Attempt(null, false);
    } catch (Exception e) {
      if (shouldLogFailure(url, "error: " + e.getMessage())) {
        MediaFeature.warn("yt-dlp error for {}: {}", url, e.getMessage());
      }
      return new Attempt(null, false);
    }
  }

  boolean shouldLogFailure(String url, String failure) {
    return !failure.equals(loggedFailures.put(url, failure));
  }

  static ResolvedTrack parseOutput(String output) throws IOException {
    try {
      JsonElement parsed = JsonParser.parseString(output.trim());
      if (!parsed.isJsonObject()) throw new IOException("yt-dlp metadata is not a JSON object");
      JsonObject object = parsed.getAsJsonObject();
      String streamUrl = requiredString(object, "url");
      String title = object.has("title") && !object.get("title").isJsonNull()
        ? object.get("title").getAsString() : "Unknown";
      Integer duration = null;
      if (object.has("duration") && !object.get("duration").isJsonNull()) {
        double raw = object.get("duration").getAsDouble();
        if (Double.isFinite(raw) && raw >= 0 && raw <= Integer.MAX_VALUE) {
          duration = (int) Math.floor(raw);
        }
      }
      return new ResolvedTrack(streamUrl, title, duration);
    } catch (IOException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IOException("yt-dlp returned invalid structured metadata", e);
    }
  }

  private static String requiredString(JsonObject object, String field) throws IOException {
    if (!object.has(field) || object.get(field).isJsonNull()
        || !object.get(field).isJsonPrimitive()
        || !object.get(field).getAsJsonPrimitive().isString()) {
      throw new IOException("yt-dlp metadata is missing " + field);
    }
    String value = object.get(field).getAsString();
    if (value.isBlank()) throw new IOException("yt-dlp metadata contains a blank " + field);
    return value;
  }

  private String effectiveCookies() {
    String cookies = MediaFeature.get().getMediaConfig().getYtDlpCookies();
    if (cookies == null || cookies.isBlank()) return "";
    File cookieFile = new File(cookies);
    return cookieFile.isFile() ? cookieFile.getAbsolutePath() : "";
  }

}
