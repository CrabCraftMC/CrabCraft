package crabcraft.net.crabUtilities.velocity.update;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class UpdateChecker {

    private static final String LATEST_URL = "https://api.github.com/repos/%s/releases/latest";
    private static final String CHECKSUMS_ASSET = "SHA256SUMS.txt";

    private final HttpClient http;
    private final String repo;
    private final String token;
    private final String jarAssetName;
    private final String userAgent;

    public UpdateChecker(String repo, String token, String jarAssetName, String userAgent) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.repo = repo;
        this.token = token;
        this.jarAssetName = jarAssetName;
        this.userAgent = userAgent;
    }

    public ReleaseInfo fetchLatest() throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(String.format(LATEST_URL, repo)))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
                .GET();
        if (token != null && !token.isEmpty()) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        int code = res.statusCode();
        if (code == 404) {
            throw new UpdateExceptions.NoReleaseException("No releases found for " + repo);
        }
        if (code == 403) {
            String remaining = res.headers().firstValue("X-RateLimit-Remaining").orElse("?");
            if ("0".equals(remaining)) {
                throw new UpdateExceptions.RateLimitedException("GitHub rate limit reached; set auto-update.github-token to raise it");
            }
            throw new IOException("403 from GitHub: " + truncate(res.body()));
        }
        if (code / 100 != 2) {
            throw new IOException("HTTP " + code + " from GitHub: " + truncate(res.body()));
        }

        JSONObject json = new JSONObject(res.body());
        String tag = json.optString("tag_name", "");
        boolean prerelease = json.optBoolean("prerelease", false);
        SemVer ver = SemVer.parse(tag);

        JSONArray assets = json.optJSONArray("assets");
        if (assets == null || assets.length() == 0) {
            throw new UpdateExceptions.AssetNotFoundException("Release " + tag + " has no assets");
        }

        String jarUrl = null;
        String checksumUrl = null;
        long size = 0L;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name", "");
            String url = a.optString("browser_download_url", "");
            if (name.equals(jarAssetName)) {
                jarUrl = url;
                size = a.optLong("size", 0L);
            } else if (name.equals(CHECKSUMS_ASSET)) {
                checksumUrl = url;
            }
        }
        if (jarUrl == null) {
            throw new UpdateExceptions.AssetNotFoundException(
                    "Release " + tag + " has no asset named " + jarAssetName);
        }

        return new ReleaseInfo(tag, ver, jarAssetName, jarUrl, checksumUrl, size, prerelease);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
