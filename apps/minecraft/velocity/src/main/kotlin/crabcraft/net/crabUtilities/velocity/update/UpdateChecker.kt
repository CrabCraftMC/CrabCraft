package crabcraft.net.crabUtilities.velocity.update

import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class UpdateChecker(
    private val repo: String,
    private val token: String?,
    private val jarAssetName: String,
    private val userAgent: String
) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Throws(IOException::class, InterruptedException::class)
    open fun fetchLatest(): ReleaseInfo {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(String.format(LATEST_URL, repo)))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
            .GET()
        if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer $token")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val code = response.statusCode()
        if (code == 404) throw UpdateExceptions.NoReleaseException("No releases found for $repo")
        if (code == 403) {
            val remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("?")
            if (remaining == "0") {
                throw UpdateExceptions.RateLimitedException("GitHub rate limit reached; set auto-update.github-token to raise it")
            }
            throw IOException("403 from GitHub: " + truncate(response.body()))
        }
        if (code / 100 != 2) throw IOException("HTTP $code from GitHub: " + truncate(response.body()))

        val json = JSONObject(response.body())
        val tag = json.optString("tag_name", "")
        val prerelease = json.optBoolean("prerelease", false)
        val version = SemVer.parse(tag)
        val assets = json.optJSONArray("assets")
        if (assets == null || assets.length() == 0) {
            throw UpdateExceptions.AssetNotFoundException("Release $tag has no assets")
        }

        var jarUrl: String? = null
        var checksumUrl: String? = null
        var size = 0L
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name", "")
            // Use the API url (not browser_download_url) so Authorization
            // gets accepted for private repos. Combined with the
            // Accept: application/octet-stream header in UpdateDownloader,
            // GitHub 302s to a signed URL which downloads without auth.
            val url = asset.optString("url", "")
            if (name == jarAssetName) {
                jarUrl = url
                size = asset.optLong("size", 0L)
            } else if (name == CHECKSUMS_ASSET) {
                checksumUrl = url
            }
        }
        if (jarUrl == null) throw UpdateExceptions.AssetNotFoundException("Release $tag has no asset named $jarAssetName")
        return ReleaseInfo(tag, version, jarAssetName, jarUrl, checksumUrl, size, prerelease)
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/%s/releases/latest"
        private const val CHECKSUMS_ASSET = "SHA256SUMS.txt"

        private fun truncate(value: String?): String =
            if (value == null) "" else if (value.length > 200) value.substring(0, 200) + "..." else value
    }
}
