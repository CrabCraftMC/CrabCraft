package crabcraft.net.crabUtilities.update

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.json.JSONObject

open class UpdateChecker(
    private val repo: String,
    private val token: String?,
    private val jarAssetName: String,
    private val userAgent: String,
) {
    private val http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    @Throws(IOException::class, InterruptedException::class)
    open fun fetchLatest(): ReleaseInfo {
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/$repo/releases/latest"))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .GET()
        if (!token.isNullOrEmpty()) request.header("Authorization", "Bearer $token")
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        val code = response.statusCode()
        if (code == 404) throw UpdateExceptions.NoReleaseException("No releases found for $repo")
        if (code == 403) {
            val remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("?")
            if (remaining == "0") {
                throw UpdateExceptions.RateLimitedException(
                    "GitHub rate limit reached; set auto-update.github-token to raise it"
                )
            }
            throw IOException("403 from GitHub: ${truncate(response.body())}")
        }
        if (code / 100 != 2) throw IOException("HTTP $code from GitHub: ${truncate(response.body())}")

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
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            // The API URL accepts authorisation for private repositories; its redirect
            // supplies a signed download URL to UpdateDownloader.
            val url = asset.optString("url", "")
            if (name == jarAssetName) {
                jarUrl = url
                size = asset.optLong("size", 0L)
            } else if (name == "SHA256SUMS.txt") {
                checksumUrl = url
            }
        }
        if (jarUrl == null) {
            throw UpdateExceptions.AssetNotFoundException("Release $tag has no asset named $jarAssetName")
        }
        return ReleaseInfo(tag, version, jarAssetName, jarUrl, checksumUrl, size, prerelease)
    }

    private fun truncate(value: String?): String =
        when {
            value == null -> ""
            value.length > 200 -> value.substring(0, 200) + "..."
            else -> value
        }
}
