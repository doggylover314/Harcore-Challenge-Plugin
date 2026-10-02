package io.github.doggylover314.hardcorechallenge.ui;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Optional JSON POST on run start and end. The request is sent asynchronously and never blocks a tick.
 */
public final class Webhook {
    private final Logger logger;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Webhook(Logger logger) {
        this.logger = logger;
    }

    /**
     * @param url     target URL; blank disables the webhook
     * @param content human-readable summary (sent as {@code content}, which Discord displays)
     * @param payload structured fields, sent alongside {@code content}
     */
    public void post(String url, String content, JsonObject payload) {
        if (url == null || url.isBlank()) {
            return;
        }
        JsonObject body = payload.deepCopy();
        body.addProperty("content", content);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "HardcoreChallenge")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
        } catch (IllegalArgumentException e) {
            logger.warning("webhook-url is not a valid URL: " + url);
            return;
        }
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null) {
                logger.warning("Webhook POST failed: " + error.getMessage());
            } else if (response.statusCode() >= 300) {
                logger.warning("Webhook POST returned HTTP " + response.statusCode());
            }
        });
    }
}
