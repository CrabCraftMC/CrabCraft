package net.crabcraft.customdiscs.audio;

import net.crabcraft.customdiscs.CustomDiscs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Resolves any media URL to a direct stream URL + title via yt-dlp. Blocking; call off the main thread. */
public final class TrackResolver {
  private static final long CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);

  private final BinaryProvisioner binaries;
  private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
  private final Map<String, String> loggedFailures = new ConcurrentHashMap<>();

  public TrackResolver(BinaryProvisioner binaries) {
    this.binaries = binaries;
  }

  /** A resolved track. {@code durationSeconds} is {@code null} when unknown (e.g. live streams). */
  public record ResolvedTrack(String streamUrl, String title, Integer durationSeconds) {}
  private record CacheKey(String url, String cookies, String proxy) {}
  private record CacheEntry(ResolvedTrack track, long expiresAtNanos) {}

  /**
   * Runs {@code yt-dlp --print "%(title)s" --print urls -f bestaudio --no-playlist <url>}.
   * Output is the title on the first line then one-or-more URLs; we take the first http(s) line as the stream URL.
   * @return the resolved track, or {@code null} if resolution failed / produced no URL.
   */
  public ResolvedTrack resolve(String url) {
    return resolve(url, false);
  }

  /** Resolves a source, optionally bypassing a cached CDN URL after a stream failure. */
  public ResolvedTrack resolve(String url, boolean forceRefresh) {
    String cookies = effectiveCookies();
    String proxy = effectiveProxy();
    CacheKey key = new CacheKey(url, cookies, proxy);
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

    ResolvedTrack resolved = resolveUncached(url, cookies, proxy);
    if (resolved != null) {
      cache.put(key, new CacheEntry(resolved, now + CACHE_TTL_NANOS));
    }
    return resolved;
  }

  private ResolvedTrack resolveUncached(String url, String cookies, String proxy) {
    String ytDlp = binaries.getYtDlpPath();
    if (ytDlp == null) return null;
    // --no-cache-dir: never reuse a cached SoundCloud client_id (a stale one 404s ALL
    // SoundCloud until manually cleared); fetch a fresh one each resolve.
    List<String> cmd = new ArrayList<>(List.of(
      ytDlp, "-f", "bestaudio/best", "--no-playlist", "--no-warnings", "--no-cache-dir"));
    if (!cookies.isBlank()) {
      cmd.add("--cookies");
      cmd.add(cookies);
    }
    if (!proxy.isBlank()) {
      cmd.add("--proxy");
      cmd.add(proxy);
    }
    cmd.add("--print"); cmd.add("%(title)s");
    cmd.add("--print"); cmd.add("urls");
    cmd.add("--print"); cmd.add("%(duration)s");
    cmd.add(url);

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
        CustomDiscs.warn("yt-dlp self-extraction still failing after {} attempts for {}",
          BinaryProvisioner.SELF_EXTRACT_ATTEMPTS, url);
        return null;
      }
      CustomDiscs.warn("yt-dlp self-extraction failed for {} (attempt {}/{}); retrying",
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

  /** Outcome of a single yt-dlp run: {@code track} on success, else {@code retryable} for a transient failure. */
  private record Attempt(ResolvedTrack track, boolean retryable) {}

  private Attempt runOnce(List<String> cmd, String url) {
    try {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
      boolean done = p.waitFor(30, TimeUnit.SECONDS);
      if (!done) {
        p.destroyForcibly();
        if (shouldLogFailure(url, "timeout")) {
          CustomDiscs.warn("yt-dlp timed out resolving {}", url);
        }
        return new Attempt(null, false);
      }
      if (p.exitValue() != 0) {
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (BinaryProvisioner.isSelfExtractionError(err)) return new Attempt(null, true);
        String summary = err.length() > 200 ? err.substring(0, 200) : err;
        if (shouldLogFailure(url, "exit " + p.exitValue() + ": " + summary)) {
          CustomDiscs.warn("yt-dlp failed (exit {}) for {}: {}", p.exitValue(), url, summary);
        }
        return new Attempt(null, false);
      }
      List<String> lines = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().map(String::trim)
        .filter(s -> !s.isEmpty()).toList();
      String streamUrl = lines.stream().filter(s -> s.startsWith("http")).findFirst().orElse(null);
      if (streamUrl == null) {
        if (shouldLogFailure(url, "no stream URL")) {
          CustomDiscs.warn("yt-dlp returned no stream URL for {}", url);
        }
        return new Attempt(null, false);
      }
      // Non-URL lines are, in print order: title first, then duration (e.g. "212.0" or "NA").
      List<String> nonUrl = lines.stream().filter(s -> !s.startsWith("http")).toList();
      String title = nonUrl.isEmpty() ? "Unknown" : nonUrl.getFirst();
      Integer durationSeconds = nonUrl.size() < 2 ? null : parseDuration(nonUrl.getLast());
      return new Attempt(new ResolvedTrack(streamUrl, title, durationSeconds), false);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Attempt(null, false);
    } catch (Exception e) {
      if (shouldLogFailure(url, "error: " + e.getMessage())) {
        CustomDiscs.warn("yt-dlp error for {}: {}", url, e.getMessage());
      }
      return new Attempt(null, false);
    }
  }

  boolean shouldLogFailure(String url, String failure) {
    return !failure.equals(loggedFailures.put(url, failure));
  }

  /** Parses yt-dlp's {@code %(duration)s} (seconds, possibly a float or "NA"); null when unparseable. */
  private static Integer parseDuration(String value) {
    try {
      return (int) Math.floor(Double.parseDouble(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String effectiveCookies() {
    String cookies = CustomDiscs.getPlugin().getCDConfig().getYtDlpCookies();
    if (cookies == null || cookies.isBlank()) return "";
    File cookieFile = new File(cookies);
    return cookieFile.isFile() ? cookieFile.getAbsolutePath() : "";
  }

  private String effectiveProxy() {
    String proxy = CustomDiscs.getPlugin().getCDConfig().getYtDlpProxy();
    return proxy == null || proxy.isBlank() ? "" : proxy;
  }
}
