package crabcraft.net.crabUtilities.velocity;

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
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String json = "{\"content\":" + escapeJson(message) + ",\"allowed_mentions\":{\"parse\":[]}}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> {
                    logger.warn("Failed to send Discord webhook message", e);
                    return null;
                });
    }

    private static String escapeJson(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
