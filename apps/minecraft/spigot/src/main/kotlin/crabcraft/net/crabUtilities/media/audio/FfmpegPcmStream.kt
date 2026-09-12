package crabcraft.net.crabUtilities.media.audio

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** Decodes a stream URL to 48 kHz / mono / s16le PCM in 20 ms (960-sample) frames. */
class FfmpegPcmStream @JvmOverloads @Throws(IOException::class) constructor(
  ffmpegPath: String,
  streamUrl: String,
  volume: Float,
  destinationPolicy: MediaDestinationPolicy,
  policyProxy: MediaPolicyProxy,
  maxSeconds: Int = 0
) {
  private val process: Process
  private val queue = ArrayBlockingQueue<ShortArray>(50) // ~1s buffer
  @Volatile private var closed = false

  init {
    destinationPolicy.approve(streamUrl)
    if (!volume.isFinite()) throw IOException("audio volume must be finite")
    val command = command(ffmpegPath, streamUrl, volume, policyProxy.url(), maxSeconds)
    val pb = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
    pb.environment().keys.removeIf { key ->
      key.equals("http_proxy", true) || key.equals("https_proxy", true) ||
        key.equals("all_proxy", true) || key.equals("no_proxy", true)
    }
    pb.environment()["http_proxy"] = policyProxy.url()
    pb.environment()["https_proxy"] = policyProxy.url()
    process = pb.start()
    Thread(::readLoop, "CD-ffmpeg-reader").apply { isDaemon = true; start() }
  }

  /** Frame supplier for SVC: returns a 960-sample frame, or null when the stream ends. */
  fun frames(): Supplier<ShortArray?> = Supplier {
    if (closed) return@Supplier null
    try {
      val frame = queue.poll(2, TimeUnit.SECONDS)
      if (frame === END) return@Supplier null
      if (frame != null) return@Supplier frame
      // Underrun: end if ffmpeg has exited, else emit silence for a transient stall.
      if (process.isAlive) ShortArray(FRAME_SAMPLES) else null
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      null
    }
  }

  private fun readLoop() {
    val buf = ByteArray(FRAME_BYTES)
    try {
      process.inputStream.use { input ->
        while (!closed) {
          val read = readFully(input, buf)
          if (read <= 0) break
          val frame = ShortArray(FRAME_SAMPLES)
          for (i in 0 until read / 2) {
            frame[i] = ((buf[i * 2].toInt() and 0xFF) or (buf[i * 2 + 1].toInt() shl 8)).toShort()
          }
          // Remaining samples in a short final frame stay zero for silence padding.
          queue.put(frame)
        }
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (_: IOException) {
      // Process killed / pipe closed.
    } finally {
      // close() clears the queue and the consumer drains at EOF; never block here.
      queue.offer(END)
    }
  }

  fun close() {
    closed = true
    try { process.destroyForcibly() } catch (_: Exception) {}
    queue.clear()
    queue.offer(END)
  }

  companion object {
    private const val FRAME_SAMPLES = 960
    private const val FRAME_BYTES = FRAME_SAMPLES * 2
    private val END = ShortArray(0)

    @JvmStatic fun command(ffmpegPath: String, streamUrl: String, volume: Float,
                           policyProxyUrl: String, maxSeconds: Int): List<String> {
      val command = arrayListOf(
        ffmpegPath,
        "-protocol_whitelist", "http,https,tls,tcp,crypto,httpproxy,data",
        "-rw_timeout", "15000000", "-http_proxy", policyProxyUrl,
        "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
        "-i", streamUrl, "-vn", "-af", "volume=" + Math.max(0f, Math.min(2f, volume)),
        "-f", "s16le", "-ar", "48000", "-ac", "1"
      )
      if (maxSeconds > 0) { command.add("-t"); command.add(maxSeconds.toString()) }
      command.add("pipe:1")
      return command
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, buf: ByteArray): Int {
      var off = 0
      while (off < buf.size) {
        val n = input.read(buf, off, buf.size - off)
        if (n < 0) return if (off == 0) -1 else off
        off += n
      }
      return off
    }
  }
}
