package crabcraft.net.crabUtilities.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class DiscordWebhook {

    private final HttpClient httpClient;
    private final String webhookUrl;
    private final Logger logger;

    public DiscordWebhook(String webhookUrl, Logger logger) {
        this.webhookUrl = webhookUrl;
        this.logger = logger;
        this.httpClient = HttpClient.newHttpClient();
    }

    public void send(String message) {
        send(message, null, null);
    }

    public void send(String message, String username, String avatarUrl) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("content", message);
        if (username != null && !username.isEmpty()) {
            payload.addProperty("username", username);
        }
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            payload.addProperty("avatar_url", avatarUrl);
        }
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> {
                    logger.warn("Failed to send Discord webhook message", e);
                    return null;
                });
    }
}
