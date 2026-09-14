package crabcraft.net.crabUtilities.media.audio

import crabcraft.net.crabUtilities.media.MediaFeature
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Resolves binaries from configured paths, PATH, or auto-downloaded static builds, in that order. */
class BinaryProvisioner {
  private val ytDlpPath: String?
  private val ffmpegPath: String?
  private val ready: Boolean

  init {
    val binDir = File(MediaFeature.get().getDataFolder(), "bin")
    if (!binDir.exists() && !binDir.mkdirs()) MediaFeature.warn("Failed to create bin directory {}", binDir.absolutePath)
    ytDlpPath = resolveYtDlp(MediaFeature.get().getMediaConfig().getYtDlpPath(), binDir)
    ffmpegPath = resolveFfmpeg(MediaFeature.get().getMediaConfig().getFfmpegPath(), binDir)
    ready = ytDlpPath != null && ffmpegPath != null
    if (ready) {
      MediaFeature.info("Audio binaries ready (yt-dlp={}, ffmpeg={})", ytDlpPath, ffmpegPath)
    } else {
      MediaFeature.error("Crab Utilities media playback needs yt-dlp and ffmpeg but could not " +
        "find/provision them (yt-dlp={}, ffmpeg={}). Install them on PATH or set " +
        "media.providers.yt-dlp-path / media.providers.ffmpeg-path in modules/media.yml.", ytDlpPath, ffmpegPath)
    }
  }

  fun isReady(): Boolean = ready
  fun getYtDlpPath(): String? = ytDlpPath
  fun getFfmpegPath(): String? = ffmpegPath

  private fun resolveYtDlp(configPath: String?, binDir: File): String? {
    if (isExplicit(configPath)) return if (verify(configPath!!, "--version")) configPath else null
    if (verify("yt-dlp", "--version")) return "yt-dlp"
    if (isAuto(configPath)) {
      val out = File(binDir, "yt-dlp")
      val checksum = File(binDir, "yt-dlp.sha256")
      val storedSha256 = readStoredSha256(checksum.toPath())
      if (storedSha256 != null && hasSha256(out.toPath(), storedSha256) && verify(out.absolutePath, "--version")) {
        return out.absolutePath
      }
      if (out.isFile || checksum.isFile) {
        MediaFeature.warn("Removing unverified cached binary {}", out.absolutePath)
        try { Files.deleteIfExists(out.toPath()) } catch (_: Exception) {}
        try { Files.deleteIfExists(checksum.toPath()) } catch (_: Exception) {}
      }
      val download = latestYtDlpDownload()
      if (download != null && downloadTo(download.url, out, download.sha256) && out.setExecutable(true) &&
        verify(out.absolutePath, "--version") && storeSha256(checksum.toPath(), download.sha256)) return out.absolutePath
    }
    return null
  }

  private fun resolveFfmpeg(configPath: String?, binDir: File): String? {
    if (isExplicit(configPath)) return if (verify(configPath!!, "-version")) configPath else null
    if (verify("ffmpeg", "-version")) return "ffmpeg"
    if (isAuto(configPath)) {
      val out = File(binDir, "ffmpeg")
      val download = ffmpegDownload() ?: return null
      if (verifiedDownloadedBinary(out, download.sha256, "-version")) return out.absolutePath
      if (downloadTo(download.url, out, download.sha256) && out.setExecutable(true) && verify(out.absolutePath, "-version")) {
        return out.absolutePath
      }
    }
    return null
  }

  private fun verifiedDownloadedBinary(file: File, expectedSha256: String, versionFlag: String): Boolean {
    if (!file.isFile) return false
    if (hasSha256(file.toPath(), expectedSha256) && verify(file.absolutePath, versionFlag)) return true
    MediaFeature.warn("Removing unverified cached binary {}", file.absolutePath)
    try { Files.deleteIfExists(file.toPath()) } catch (_: Exception) {}
    return false
  }

  private data class Download(val url: String, val sha256: String)

  private fun latestYtDlpDownload(): Download? {
    val checksumsUrl = YT_DLP_LATEST_RELEASE + YT_DLP_CHECKSUMS
    try {
      val client = httpClient()
      val request = HttpRequest.newBuilder().uri(URI.create(checksumsUrl)).timeout(Duration.ofSeconds(30))
        .header("Accept", "application/octet-stream").header("User-Agent", "CrabUtilities").GET().build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
      if (response.statusCode() != 200) {
        response.body().close()
        MediaFeature.warn("Could not fetch latest yt-dlp checksums (HTTP {})", response.statusCode())
        return null
      }
      val checksums = response.body().use { input ->
        val bytes = input.readNBytes(MAX_CHECKSUM_BYTES + 1)
        if (bytes.size > MAX_CHECKSUM_BYTES) {
          MediaFeature.warn("Latest yt-dlp checksum file exceeds 64 KiB")
          return null
        }
        String(bytes, StandardCharsets.UTF_8)
      }
      val sha256 = publishedSha256(checksums, YT_DLP_ASSET)
      if (sha256 == null) {
        MediaFeature.warn("Latest yt-dlp release has no valid checksum for {}", YT_DLP_ASSET)
        return null
      }
      return Download(YT_DLP_LATEST_RELEASE + YT_DLP_ASSET, sha256)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return null
    } catch (e: Exception) {
      MediaFeature.warn("Could not resolve latest yt-dlp release: {}", e.message)
      return null
    }
  }

  private fun verify(path: String, versionFlag: String): Boolean {
    for (attempt in 1..SELF_EXTRACT_ATTEMPTS) {
      try {
        val p = ProcessBuilder(path, versionFlag).redirectErrorStream(true).start()
        val done = p.waitFor(10, TimeUnit.SECONDS)
        if (!done) { p.destroyForcibly(); return false }
        val output = String(p.inputStream.readAllBytes(), StandardCharsets.UTF_8)
        if (p.exitValue() == 0) return true
        // The onefile build sometimes fails to self-extract on a cold run; retry before giving up.
        if (!isSelfExtractionError(output) || attempt == SELF_EXTRACT_ATTEMPTS) return false
        Thread.sleep(200L * attempt)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      } catch (_: Exception) { return false }
    }
    return false
  }

  private fun downloadTo(url: String, out: File, expectedSha256: String): Boolean {
    MediaFeature.info("Downloading {} ...", url)
    val tmp = out.toPath().resolveSibling(out.name + ".tmp")
    try {
      val client = httpClient()
      val req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMinutes(5))
        .header("Accept", "application/octet-stream").header("User-Agent", "CrabUtilities").GET().build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
      if (resp.statusCode() != 200) {
        MediaFeature.warn("Download failed (HTTP {}): {}", resp.statusCode(), url)
        resp.body().close()
        return false
      }
      val contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1L)
      if (contentLength > MAX_BINARY_BYTES) {
        MediaFeature.warn("Downloaded binary is too large: {}", url)
        resp.body().close()
        return false
      }
      resp.body().use { input ->
        Files.newOutputStream(tmp).use { output ->
          val buffer = ByteArray(8192)
          var downloaded = 0L
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            downloaded += read
            if (downloaded > MAX_BINARY_BYTES) throw IOException("downloaded binary exceeds 256 MiB")
            output.write(buffer, 0, read)
          }
        }
      }
      if (!hasSha256(tmp, expectedSha256)) {
        MediaFeature.warn("Downloaded binary failed SHA-256 verification: {}", url)
        Files.deleteIfExists(tmp)
        return false
      }
      Files.move(tmp, out.toPath(), StandardCopyOption.REPLACE_EXISTING)
      return true
    } catch (e: Exception) {
      MediaFeature.warn("Download error for {}: {}", url, e.message)
      try { Files.deleteIfExists(tmp) } catch (_: Exception) {}
      return false
    }
  }

  companion object {
    private const val YT_DLP_LATEST_RELEASE = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"
    private const val YT_DLP_ASSET = "yt-dlp_linux"
    private const val YT_DLP_CHECKSUMS = "SHA2-256SUMS"
    private const val MAX_CHECKSUM_BYTES = 64 * 1024
    private const val MAX_BINARY_BYTES = 256L * 1024 * 1024
    private const val FFMPEG_URL_AMD64 = "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/ffmpeg-linux-x64"
    private const val FFMPEG_SHA256_AMD64 = "e7e7fb30477f717e6f55f9180a70386c62677ef8a4d4d1a5d948f4098aa3eb99"
    private const val FFMPEG_URL_ARM64 = "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/ffmpeg-linux-arm64"
    private const val FFMPEG_SHA256_ARM64 = "6bb182d0d75d23028db82e9e4f723ca69b853d055698486e6984ddb2c06fb8ce"
    const val SELF_EXTRACT_ATTEMPTS = 3

    private fun ffmpegDownload(): Download? {
      val os = System.getProperty("os.name", "").lowercase(Locale.getDefault())
      val arch = System.getProperty("os.arch", "").lowercase(Locale.getDefault())
      if (!os.contains("linux")) return null
      if (arch == "aarch64" || arch == "arm64") return Download(FFMPEG_URL_ARM64, FFMPEG_SHA256_ARM64)
      if (arch == "amd64" || arch == "x86_64") return Download(FFMPEG_URL_AMD64, FFMPEG_SHA256_AMD64)
      return null
    }

    @JvmStatic fun publishedSha256(checksums: String?, assetName: String?): String? {
      if (checksums == null || assetName == null) return null
      for (line in checksums.split(Regex("\\R"))) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val parts = trimmed.split(Regex("\\s+"), 2)
        if (parts.size != 2) continue
        val name = if (parts[1].startsWith("*")) parts[1].substring(1) else parts[1]
        if (name == assetName && parts[0].matches(Regex("(?i)^[0-9a-f]{64}$"))) {
          return parts[0].lowercase(Locale.getDefault())
        }
      }
      return null
    }

    private fun readStoredSha256(file: Path): String? {
      return try {
        val value = Files.readString(file, StandardCharsets.UTF_8).trim()
        if (value.matches(Regex("(?i)^[0-9a-f]{64}$"))) value.lowercase(Locale.getDefault()) else null
      } catch (_: IOException) { null }
    }

    private fun storeSha256(file: Path, sha256: String): Boolean {
      return try {
        Files.writeString(file, sha256 + System.lineSeparator(), StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        true
      } catch (e: IOException) {
        MediaFeature.warn("Could not store yt-dlp checksum: {}", e.message)
        false
      }
    }

    private fun isExplicit(p: String?): Boolean = !p.isNullOrBlank() && !p.equals("auto", true)
    // Blank means PATH only; only null or auto triggers auto-provisioning.
    private fun isAuto(p: String?): Boolean = p == null || p.equals("auto", true)

    @JvmStatic fun isSelfExtractionError(output: String?): Boolean = output != null &&
      (output.contains("Failed to extract") || output.contains("PYI-") || output.contains("_wrapper.abi3"))

    private fun httpClient(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NORMAL).build()

    @JvmStatic fun hasSha256(file: Path, expectedSha256: String): Boolean {
      return try {
        Files.newInputStream(file).use { input ->
          val digest = MessageDigest.getInstance("SHA-256")
          val buffer = ByteArray(8192)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
          }
          HexFormat.of().formatHex(digest.digest()).equals(expectedSha256, true)
        }
      } catch (_: IOException) { false } catch (_: NoSuchAlgorithmException) { false }
    }
  }
}
