package io.github.saksham023.jobagent.alerts;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only code that talks to Telegram's Bot API: sendMessage and getUpdates. The bot token is part of every URL
 * (https://api.telegram.org/bot{token}/...), so it is removed from every error message before anything is logged or
 * stored.
 */
@Component
public class TelegramClient {

    /** A message sent to the bot. */
    public record Update(long updateId, long chatId, String text) {
    }

    /**
     * A failed call. status 429 = too many messages (wait retryAfter), 403 = the user blocked the bot, 0 = no answer
     * (network); anything else is retried with backoff.
     */
    public static class TelegramException extends RuntimeException {

        private final int status;
        private final Duration retryAfter;

        public TelegramException(int status, Duration retryAfter, String message) {
            super(message);
            this.status = status;
            this.retryAfter = retryAfter;
        }

        public int status() {
            return status;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    private final AlertProperties properties;
    private final JsonMapper jsonMapper;
    private final RestClient http;

    public TelegramClient(AlertProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Sends an HTML message (links on, previews off). */
    public void sendMessage(long chatId, String html) {
        call("sendMessage", Map.of("chat_id", chatId, "text", html, "parse_mode", "HTML",
                "link_preview_options", Map.of("is_disabled", true)));
    }

    /** Messages sent to the bot from `offset` on (no waiting: returns at once). */
    public List<Update> updates(long offset) {
        JsonNode body = call("getUpdates", Map.of("offset", offset, "timeout", 0, "allowed_updates", List.of("message")));
        List<Update> updates = new ArrayList<>();
        for (JsonNode update : body.path("result")) {
            updates.add(update(update));
        }
        return updates;
    }

    /** One update as Telegram sends it (by getUpdates, or as the body of a webhook call). */
    public static Update update(JsonNode update) {
        JsonNode message = update.path("message");
        return new Update(update.path("update_id").asLong(), message.path("chat").path("id").asLong(),
                message.path("text").isString() ? message.path("text").asString() : "");
    }

    /**
     * Tells Telegram to POST every new message to `url`, with `secret` in the X-Telegram-Bot-Api-Secret-Token header.
     * Calling it again with the same values is harmless. While a webhook is set, getUpdates answers 409.
     */
    public void setWebhook(String url, String secret) {
        call("setWebhook", Map.of("url", url, "secret_token", secret, "allowed_updates", List.of("message")));
    }

    private JsonNode call(String method, Map<String, Object> body) {
        String token = properties.telegram().botToken();
        try {
            JsonNode answer = http.post()
                    .uri(URI.create(properties.telegram().apiUrl() + "/bot" + token + "/" + method))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (answer == null || !answer.path("ok").asBoolean(false)) {
                throw new TelegramException(0, null, "Telegram " + method + " answered without ok");
            }
            return answer;
        } catch (RestClientResponseException e) {
            JsonNode error = parse(e.getResponseBodyAsString());
            int retryAfter = error.path("parameters").path("retry_after").asInt(0);
            String description = error.path("description").isString() ? error.path("description").asString() : e.getStatusText();
            throw new TelegramException(e.getStatusCode().value(), retryAfter > 0 ? Duration.ofSeconds(retryAfter) : null,
                    hide("Telegram " + method + ": " + e.getStatusCode().value() + " " + description, token));
        } catch (RestClientException e) {
            throw new TelegramException(0, null, hide("Telegram " + method + ": " + e.getMessage(), token));
        }
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? jsonMapper.createObjectNode() : jsonMapper.readTree(body);
        } catch (RuntimeException e) {
            return jsonMapper.createObjectNode();
        }
    }

    /** Never let the bot token reach a log line or a database row. */
    static String hide(String message, String token) {
        return message == null || token == null || token.isBlank() ? message : message.replace(token, "***");
    }
}
