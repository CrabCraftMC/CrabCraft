package crabcraft.net.crabUtilities.media.audio

import crabcraft.net.crabUtilities.media.MediaFeature
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Disk-backed cache of the first few seconds of decoded horn audio (48 kHz mono s16le).
 * Files are named by a hash of source URL and volume. Least-recently-played entries are evicted first.
 */
class HornAudioCache(private val dir: File) {
  @Volatile private var maxFiles = 50

  init {
    if (!dir.exists() && !dir.mkdirs()) {
      MediaFeature.warn("Could not create horn cache directory {}", dir.absolutePath)
    }
  }

  fun setMaxFiles(maxFiles: Int) { this.maxFiles = Math.max(0, maxFiles) }
  private fun fileFor(key: String) = File(dir, hash(key) + EXT)

  fun has(key: String): Boolean {
    val f = fileFor(key)
    return f.isFile && f.length() > 0
  }

  /** Reads a cached entry into 960-sample frames, touching it for LRU, or null on miss/error. */
  fun read(key: String): List<ShortArray>? {
    val f = fileFor(key)
    if (!f.isFile || f.length() == 0L) return null
    try {
      val bytes = Files.readAllBytes(f.toPath())
      val frameCount = bytes.size / FRAME_BYTES
      if (frameCount == 0) return null
      val frames = ArrayList<ShortArray>(frameCount)
      for (i in 0 until frameCount) {
        val frame = ShortArray(FRAME_SAMPLES)
        val off = i * FRAME_BYTES
        for (s in 0 until FRAME_SAMPLES) {
          frame[s] = ((bytes[off + s * 2].toInt() and 0xFF) or
            (bytes[off + s * 2 + 1].toInt() shl 8)).toShort()
        }
        frames.add(frame)
      }
      if (!f.setLastModified(System.currentTimeMillis())) {
        MediaFeature.debug("Could not touch horn cache {} for LRU ordering", f.name)
      }
      return frames
    } catch (e: IOException) {
      MediaFeature.warn("Failed reading horn cache {}: {}", f.name, e.message)
      return null
    }
  }

  /** Writes frames as raw s16le to disk atomically, then evicts entries past the cap. */
  fun write(key: String, frames: List<ShortArray>?) {
    if (maxFiles <= 0 || frames.isNullOrEmpty()) return
    val f = fileFor(key)
    val tmp = File(dir, f.name + ".tmp")
    try {
      BufferedOutputStream(FileOutputStream(tmp)).use { out ->
        val buf = ByteArray(FRAME_BYTES)
        for (frame in frames) {
          for (s in 0 until FRAME_SAMPLES) {
            buf[s * 2] = (frame[s].toInt() and 0xFF).toByte()
            buf[s * 2 + 1] = ((frame[s].toInt() shr 8) and 0xFF).toByte()
          }
          out.write(buf)
        }
        out.flush()
        try {
          Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
          Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        evict()
      }
    } catch (e: IOException) {
      MediaFeature.warn("Failed writing horn cache {}: {}", f.name, e.message)
      tmp.delete()
    }
  }

  private fun evict() {
    val files = dir.listFiles { _, n -> n.endsWith(EXT) } ?: return
    if (files.size <= maxFiles) return
    files.sortBy { it.lastModified() }
    for (i in 0 until files.size - maxFiles) {
      if (!files[i].delete()) MediaFeature.debug("Could not evict horn cache {}", files[i].name)
    }
  }

  fun clear() {
    dir.listFiles { _, n -> n.endsWith(EXT) || n.endsWith(".tmp") }?.forEach { it.delete() }
  }

  companion object {
    private const val FRAME_SAMPLES = 960
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private const val EXT = ".pcm"

    private fun hash(key: String): String {
      try {
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(h.size * 2)
        for (b in h) sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
          .append(Character.forDigit(b.toInt() and 0xF, 16))
        return sb.toString()
      } catch (e: Exception) { throw RuntimeException(e) }
    }
  }
}
