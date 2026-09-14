package crabcraft.net.crabUtilities.velocity

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

open class DiscordWebhook(private val webhookUrl: String?, private val logger: Logger) {
    private val httpClient = HttpClient.newHttpClient()
    open fun send(message: String) { send(message, null, null) }
    open fun send(message: String, username: String?, avatarUrl: String?) {
        if (webhookUrl.isNullOrEmpty()) return
        val payload = JsonObject()
        payload.addProperty("content", message)
        if (!username.isNullOrEmpty()) payload.addProperty("username", username)
        if (!avatarUrl.isNullOrEmpty()) payload.addProperty("avatar_url", avatarUrl)
        val allowedMentions = JsonObject()
        allowedMentions.add("parse", JsonArray())
        payload.add("allowed_mentions", allowedMentions)
        val request = HttpRequest.newBuilder().uri(URI.create(webhookUrl)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build()
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally { e ->
            logger.warn("Failed to send Discord webhook message", e)
            null
        }
    }
}
