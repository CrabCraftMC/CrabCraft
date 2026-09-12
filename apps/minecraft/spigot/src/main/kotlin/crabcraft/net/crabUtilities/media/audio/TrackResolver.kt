package crabcraft.net.crabUtilities.media.audio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.media.MediaFeature
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Resolves a media URL to a direct stream URL and title via yt-dlp. Call off the main thread. */
class TrackResolver(
  private val binaries: BinaryProvisioner?,
  private val destinationPolicy: MediaDestinationPolicy?,
  private val policyProxy: MediaPolicyProxy?
) {
  private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()
  private val loggedFailures = ConcurrentHashMap<String, String>()

  /** durationSeconds is null when unknown, for example for live streams. */
  data class ResolvedTrack(val streamUrl: String, val title: String, val durationSeconds: Int?) {
    fun streamUrl() = streamUrl
    fun title() = title
    fun durationSeconds() = durationSeconds
  }
  private data class CacheKey(val url: String, val cookies: String)
  private data class CacheEntry(val track: ResolvedTrack, val expiresAtNanos: Long)
  private data class Attempt(val track: ResolvedTrack?, val retryable: Boolean)

  fun resolve(url: String): ResolvedTrack? = resolve(url, false)

  /** Resolves a source, optionally bypassing a cached CDN URL after a stream failure. */
  fun resolve(url: String, forceRefresh: Boolean): ResolvedTrack? {
    val cookies = effectiveCookies()
    val key = CacheKey(url, cookies)
    val now = System.nanoTime()
    if (forceRefresh) cache.remove(key)
    val cached = cache[key]
    if (cached != null) {
      if (cached.expiresAtNanos > now) {
        loggedFailures.remove(url)
        return cached.track
      }
      cache.remove(key, cached)
    }
    val resolved = resolveUncached(url, cookies)
    if (resolved != null) cache[key] = CacheEntry(resolved, now + CACHE_TTL_NANOS)
    return resolved
  }

  private fun resolveUncached(url: String, cookies: String): ResolvedTrack? {
    val ytDlp = binaries!!.getYtDlpPath() ?: return null
    try {
      destinationPolicy!!.approve(url)
    } catch (e: IOException) {
      if (shouldLogFailure(url, "destination policy: " + e.message)) {
        MediaFeature.warn("Rejected media source {}: {}", url, e.message)
      }
      return null
    }
    // Never reuse a cached SoundCloud client_id: a stale one fails every SoundCloud request.
    val cmd = command(ytDlp, cookies, policyProxy!!.url(), url)
    // The onefile build occasionally fails to extract its libraries on a cold run; retry it.
    for (attempt in 1..BinaryProvisioner.SELF_EXTRACT_ATTEMPTS) {
      val result = runOnce(cmd, url)
      if (result.track != null) {
        loggedFailures.remove(url)
        return result.track
      }
      if (!result.retryable) return null
      if (attempt == BinaryProvisioner.SELF_EXTRACT_ATTEMPTS) {
        MediaFeature.warn("yt-dlp self-extraction still failing after {} attempts for {}",
          BinaryProvisioner.SELF_EXTRACT_ATTEMPTS, url)
        return null
      }
      MediaFeature.warn("yt-dlp self-extraction failed for {} (attempt {}/{}); retrying",
        url, attempt, BinaryProvisioner.SELF_EXTRACT_ATTEMPTS)
      try {
        Thread.sleep(200L * attempt)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return null
      }
    }
    return null
  }

  private fun runOnce(cmd: List<String>, url: String): Attempt {
    try {
      val processBuilder = ProcessBuilder(cmd).redirectErrorStream(false)
      processBuilder.environment().keys.removeIf { key ->
        key.equals("http_proxy", true) || key.equals("https_proxy", true) ||
          key.equals("all_proxy", true) || key.equals("no_proxy", true)
      }
      val p = processBuilder.start()
      val done = p.waitFor(30, TimeUnit.SECONDS)
      if (!done) {
        p.destroyForcibly()
        if (shouldLogFailure(url, "timeout")) MediaFeature.warn("yt-dlp timed out resolving {}", url)
        return Attempt(null, false)
      }
      if (p.exitValue() != 0) {
        val err = String(p.errorStream.readAllBytes(), StandardCharsets.UTF_8).trim()
        if (BinaryProvisioner.isSelfExtractionError(err)) return Attempt(null, true)
        val summary = if (err.length > 200) err.substring(0, 200) else err
        if (shouldLogFailure(url, "exit " + p.exitValue() + ": " + summary)) {
          MediaFeature.warn("yt-dlp failed (exit {}) for {}: {}", p.exitValue(), url, summary)
        }
        return Attempt(null, false)
      }
      val output = p.inputStream.readNBytes(MAX_OUTPUT_BYTES + 1)
      if (output.size > MAX_OUTPUT_BYTES) {
        if (shouldLogFailure(url, "oversized output")) {
          MediaFeature.warn("yt-dlp returned oversized metadata for {}", url)
        }
        return Attempt(null, false)
      }
      val track = parseOutput(String(output, StandardCharsets.UTF_8))
      destinationPolicy!!.approve(track.streamUrl())
      return Attempt(track, false)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      return Attempt(null, false)
    } catch (e: Exception) {
      if (shouldLogFailure(url, "error: " + e.message)) {
        MediaFeature.warn("yt-dlp error for {}: {}", url, e.message)
      }
      return Attempt(null, false)
    }
  }

  fun shouldLogFailure(url: String, failure: String): Boolean = failure != loggedFailures.put(url, failure)

  private fun effectiveCookies(): String {
    val cookies = MediaFeature.get().getMediaConfig().getYtDlpCookies()
    if (cookies.isNullOrBlank()) return ""
    val cookieFile = File(cookies)
    return if (cookieFile.isFile) cookieFile.absolutePath else ""
  }

  companion object {
    private val CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10)
    private const val MAX_OUTPUT_BYTES = 64 * 1024

    @JvmStatic fun command(ytDlp: String, cookies: String, policyProxyUrl: String, url: String): List<String> {
      val command = arrayListOf(ytDlp, "--ignore-config", "-f", "bestaudio/best", "--no-playlist", "--no-warnings",
        "--no-cache-dir", "--socket-timeout", "15", "--proxy", policyProxyUrl)
      if (cookies.isNotBlank()) { command.add("--cookies"); command.add(cookies) }
      command.add("--print")
      command.add("%(.{title,url,duration})j")
      command.add("--")
      command.add(url)
      return command
    }

    @JvmStatic @Throws(IOException::class)
    fun parseOutput(output: String): ResolvedTrack {
      try {
        val parsed = JsonParser.parseString(output.trim())
        if (!parsed.isJsonObject) throw IOException("yt-dlp metadata is not a JSON object")
        val obj = parsed.asJsonObject
        val streamUrl = requiredString(obj, "url")
        val title = if (obj.has("title") && !obj.get("title").isJsonNull) obj.get("title").asString else "Unknown"
        var duration: Int? = null
        if (obj.has("duration") && !obj.get("duration").isJsonNull) {
          val raw = obj.get("duration").asDouble
          if (raw.isFinite() && raw >= 0 && raw <= Int.MAX_VALUE) duration = Math.floor(raw).toInt()
        }
        return ResolvedTrack(streamUrl, title, duration)
      } catch (e: IOException) {
        throw e
      } catch (e: RuntimeException) {
        throw IOException("yt-dlp returned invalid structured metadata", e)
      }
    }

    @Throws(IOException::class)
    private fun requiredString(obj: JsonObject, field: String): String {
      if (!obj.has(field) || obj.get(field).isJsonNull || !obj.get(field).isJsonPrimitive ||
        !obj.get(field).asJsonPrimitive.isString) {
        throw IOException("yt-dlp metadata is missing " + field)
      }
      val value = obj.get(field).asString
      if (value.isBlank()) throw IOException("yt-dlp metadata contains a blank " + field)
      return value
    }
  }
}
