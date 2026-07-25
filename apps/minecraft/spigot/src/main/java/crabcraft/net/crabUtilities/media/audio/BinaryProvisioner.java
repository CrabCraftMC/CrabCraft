package crabcraft.net.crabUtilities.media.audio;

import crabcraft.net.crabUtilities.media.MediaFeature;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Resolves usable yt-dlp and ffmpeg binaries. Per binary, in order:
 * configured path -> binary on PATH -> auto-downloaded static build -> unavailable.
 */
public final class BinaryProvisioner {
  private static final String YT_DLP_URL =
    "https://github.com/yt-dlp/yt-dlp/releases/download/2026.07.04/yt-dlp_linux";
  private static final String YT_DLP_SHA256 =
    "6bbb3d314cde4febe36e5fa1d55462e29c974f63444e707871834f6d8cc210ae";
  private static final String FFMPEG_URL_AMD64 =
    "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/ffmpeg-linux-x64";
  private static final String FFMPEG_SHA256_AMD64 =
    "e7e7fb30477f717e6f55f9180a70386c62677ef8a4d4d1a5d948f4098aa3eb99";
  private static final String FFMPEG_URL_ARM64 =
    "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/ffmpeg-linux-arm64";
  private static final String FFMPEG_SHA256_ARM64 =
    "6bb182d0d75d23028db82e9e4f723ca69b853d055698486e6984ddb2c06fb8ce";

  private final String ytDlpPath;
  private final String ffmpegPath;
  private final boolean ready;

  public BinaryProvisioner() {
    File binDir = new File(MediaFeature.get().getDataFolder(), "bin");
    if (!binDir.exists() && !binDir.mkdirs()) {
      MediaFeature.warn("Failed to create bin directory {}", binDir.getAbsolutePath());
    }
    this.ytDlpPath = resolveYtDlp(MediaFeature.get().getMediaConfig().getYtDlpPath(), binDir);
    this.ffmpegPath = resolveFfmpeg(MediaFeature.get().getMediaConfig().getFfmpegPath(), binDir);
    this.ready = ytDlpPath != null && ffmpegPath != null;
    if (ready) {
      MediaFeature.info("Audio binaries ready (yt-dlp={}, ffmpeg={})", ytDlpPath, ffmpegPath);
    } else {
      MediaFeature.error("Crab Utilities media playback needs yt-dlp and ffmpeg but could not "
        + "find/provision them (yt-dlp={}, ffmpeg={}). Install them on PATH or set "
        + "media.providers.yt-dlp-path / media.providers.ffmpeg-path in modules/media.yml.",
        ytDlpPath, ffmpegPath);
    }
  }

  public boolean isReady() { return ready; }
  public String getYtDlpPath() { return ytDlpPath; }
  public String getFfmpegPath() { return ffmpegPath; }

  private String resolveYtDlp(String configPath, File binDir) {
    if (isExplicit(configPath)) return verify(configPath, "--version") ? configPath : null;
    if (verify("yt-dlp", "--version")) return "yt-dlp";
    if (isAuto(configPath)) {
      File out = new File(binDir, "yt-dlp");
      if (verifiedDownloadedBinary(out, YT_DLP_SHA256, "--version")) return out.getAbsolutePath();
      if (downloadTo(YT_DLP_URL, out, YT_DLP_SHA256)
        && out.setExecutable(true)
        && verify(out.getAbsolutePath(), "--version"))
        return out.getAbsolutePath();
    }
    return null;
  }

  private String resolveFfmpeg(String configPath, File binDir) {
    if (isExplicit(configPath)) return verify(configPath, "-version") ? configPath : null;
    if (verify("ffmpeg", "-version")) return "ffmpeg";
    if (isAuto(configPath)) {
      File out = new File(binDir, "ffmpeg");
      Download download = ffmpegDownload();
      if (download == null) return null;
      if (verifiedDownloadedBinary(out, download.sha256(), "-version")) return out.getAbsolutePath();
      if (downloadTo(download.url(), out, download.sha256())
        && out.setExecutable(true)
        && verify(out.getAbsolutePath(), "-version"))
        return out.getAbsolutePath();
    }
    return null;
  }

  private boolean verifiedDownloadedBinary(File file, String expectedSha256, String versionFlag) {
    if (!file.isFile()) return false;
    if (hasSha256(file.toPath(), expectedSha256)
      && verify(file.getAbsolutePath(), versionFlag)) return true;

    MediaFeature.warn("Removing unverified cached binary {}", file.getAbsolutePath());
    try { Files.deleteIfExists(file.toPath()); } catch (Exception ignored) {}
    return false;
  }

  private static Download ffmpegDownload() {
    String os = System.getProperty("os.name", "").toLowerCase();
    String arch = System.getProperty("os.arch", "").toLowerCase();
    if (!os.contains("linux")) return null;
    if (arch.equals("aarch64") || arch.equals("arm64")) {
      return new Download(FFMPEG_URL_ARM64, FFMPEG_SHA256_ARM64);
    }
    if (arch.equals("amd64") || arch.equals("x86_64")) {
      return new Download(FFMPEG_URL_AMD64, FFMPEG_SHA256_AMD64);
    }
    return null;
  }

  private record Download(String url, String sha256) {}

  private static boolean isExplicit(String p) {
    return p != null && !p.isBlank() && !p.equalsIgnoreCase("auto");
  }

  private static boolean isAuto(String p) {
    // blank means "PATH only, no auto-download"; only null or "auto" auto-provision.
    return p == null || p.equalsIgnoreCase("auto");
  }

  private boolean verify(String path, String versionFlag) {
    for (int attempt = 1; attempt <= SELF_EXTRACT_ATTEMPTS; attempt++) {
      try {
        Process p = new ProcessBuilder(path, versionFlag).redirectErrorStream(true).start();
        boolean done = p.waitFor(10, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); return false; }
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() == 0) return true;
        // yt-dlp's onefile build sometimes fails to self-extract on a cold run; retry before giving up.
        if (!isSelfExtractionError(output) || attempt == SELF_EXTRACT_ATTEMPTS) return false;
        Thread.sleep(200L * attempt);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception e) {
        return false;
      }
    }
    return false;
  }

  /**
   * The {@code yt-dlp_linux} PyInstaller onefile build unpacks its bundled native libraries into a
   * temp dir on every run and occasionally fails to do so on a cold invocation (exit 255,
   * {@code [PYI-...:ERROR] Failed to extract .../curl_cffi/_wrapper.abi3.so: decompression resulted
   * in return code -1}). A retry moments later succeeds, so callers re-run the command a few times.
   */
  static final int SELF_EXTRACT_ATTEMPTS = 3;

  /** True if process output is a yt-dlp/PyInstaller self-extraction failure (transient, retryable). */
  static boolean isSelfExtractionError(String output) {
    if (output == null) return false;
    return output.contains("Failed to extract")
      || output.contains("PYI-")
      || output.contains("_wrapper.abi3");
  }

  private boolean downloadTo(String url, File out, String expectedSha256) {
    MediaFeature.info("Downloading {} ...", url);
    Path tmp = out.toPath().resolveSibling(out.getName() + ".tmp");
    try {
      HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
      HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
        .timeout(Duration.ofMinutes(5)).GET().build();
      HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
      if (resp.statusCode() != 200) {
        MediaFeature.warn("Download failed (HTTP {}): {}", resp.statusCode(), url);
        resp.body().close();
        return false;
      }
      try (InputStream in = resp.body(); OutputStream o = Files.newOutputStream(tmp)) {
        in.transferTo(o);
      }
      if (!hasSha256(tmp, expectedSha256)) {
        MediaFeature.warn("Downloaded binary failed SHA-256 verification: {}", url);
        Files.deleteIfExists(tmp);
        return false;
      }
      Files.move(tmp, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return true;
    } catch (Exception e) {
      MediaFeature.warn("Download error for {}: {}", url, e.getMessage());
      try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
      return false;
    }
  }

  static boolean hasSha256(Path file, String expectedSha256) {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        if (read > 0) digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expectedSha256);
    } catch (IOException | NoSuchAlgorithmException e) {
      return false;
    }
  }
}
