package crabcraft.net.crabUtilities.velocity

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.slf4j.Logger

open class DiscordWebhook(private val webhookUrl: String?, private val logger: Logger) {
    private val httpClient = HttpClient.newHttpClient()

    open fun send(message: String) = send(message, null, null)

    open fun send(message: String, username: String?, avatarUrl: String?) {
        if (webhookUrl.isNullOrEmpty()) return
        val payload =
            JsonObject().apply {
                addProperty("content", message)
                if (!username.isNullOrEmpty()) addProperty("username", username)
                if (!avatarUrl.isNullOrEmpty()) addProperty("avatar_url", avatarUrl)
                add("allowed_mentions", JsonObject().apply { add("parse", JsonArray()) })
            }
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally { error ->
            logger.warn("Failed to send Discord webhook message", error)
            null
        }
    }
}
