package crabcraft.net.crabUtilities.update

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.HexFormat
import java.util.regex.Pattern

open class UpdateDownloader(private val token: String?, private val userAgent: String) {
    private val http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    @Throws(IOException::class, InterruptedException::class)
    open fun download(info: ReleaseInfo, targetDir: Path, targetFilename: String): Path {
        Files.createDirectories(targetDir)
        val target = targetDir.resolve(targetFilename)
        val part = targetDir.resolve(".$targetFilename.part")
        val checksumUrl = info.checksumUrl()
        if (checksumUrl == null || checksumUrl.all { Character.isWhitespace(it) }) {
            throw IOException("Release is missing SHA256SUMS.txt")
        }
        val expected = fetchChecksum(checksumUrl, info.jarAssetName())
        if (expected == null || !expected.matches(Regex("(?i)^[0-9a-f]{64}$"))) {
            throw IOException("Release checksum is missing or invalid for ${info.jarAssetName()}")
        }
        if (info.size() <= 0 || info.size() > MAX_UPDATE_BYTES) {
            throw IOException("Release asset has an invalid size: ${info.size()}")
        }
        val sha =
            try {
                MessageDigest.getInstance("SHA-256")
            } catch (e: NoSuchAlgorithmException) {
                throw IOException("SHA-256 not available", e)
            }

        try {
            val response =
                http.send(
                    request(info.jarUrl(), Duration.ofMinutes(5)),
                    HttpResponse.BodyHandlers.ofInputStream(),
                )
            if (response.statusCode() / 100 != 2) {
                response.body().close()
                throw IOException("HTTP ${response.statusCode()} downloading ${info.jarUrl()}")
            }
            val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            if (contentLength > info.size() || contentLength > MAX_UPDATE_BYTES) {
                response.body().close()
                throw IOException("Release asset exceeds its declared size")
            }
            response.body().use { input ->
                Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use {
                    output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        downloaded += count
                        if (downloaded > info.size() || downloaded > MAX_UPDATE_BYTES) {
                            throw IOException("Release asset exceeds its declared size")
                        }
                        sha.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    if (downloaded != info.size()) {
                        throw IOException("Release asset size mismatch: expected ${info.size()}, got $downloaded")
                    }
                }
            }
            val actual = HexFormat.of().formatHex(sha.digest())
            if (!expected.equals(actual, ignoreCase = true)) {
                throw UpdateExceptions.ChecksumMismatchException(
                    "SHA-256 mismatch for ${info.jarAssetName()}: expected $expected, got $actual"
                )
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } finally {
            try {
                Files.deleteIfExists(part)
            } catch (_: IOException) {}
        }
    }

    private fun request(url: String, timeout: Duration): HttpRequest {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", userAgent)
                .GET()
        if (!token.isNullOrEmpty()) request.header("Authorization", "Bearer $token")
        return request.build()
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun fetchChecksum(url: String, assetName: String): String? {
        val response = http.send(request(url, Duration.ofSeconds(30)), HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() / 100 != 2) {
            response.body().close()
            return null
        }
        val body =
            response.body().use { input ->
                val bytes = input.readNBytes(MAX_CHECKSUM_BYTES + 1)
                if (bytes.size > MAX_CHECKSUM_BYTES) throw IOException("Checksum file exceeds 64 KiB")
                String(bytes, Charsets.UTF_8)
            }
        for (line in Pattern.compile("\\R").split(body)) {
            val trimmed = line.trim { it <= ' ' }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val parts = Pattern.compile("\\s+").split(trimmed, 2)
            if (parts.size != 2) continue
            val name = parts[1].removePrefix("*")
            if (name.equals(assetName, ignoreCase = true)) return parts[0]
        }
        return null
    }

    companion object {
        private const val MAX_UPDATE_BYTES = 128L * 1024 * 1024
        private const val MAX_CHECKSUM_BYTES = 64 * 1024
    }
}
