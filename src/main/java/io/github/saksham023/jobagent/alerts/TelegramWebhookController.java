package io.github.saksham023.jobagent.alerts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * POST /api/v1/telegram/webhook: Telegram delivers each message sent to the bot here (webhook mode only). The path is
 * open without a login (Telegram cannot sign in), so every call must carry the secret we gave Telegram in setWebhook, in
 * the X-Telegram-Bot-Api-Secret-Token header; anything else is refused. The answer is always quick: Telegram retries a
 * call that fails or times out, and a retried message is recognised by its update_id and skipped.
 */
@RestController
public class TelegramWebhookController {

    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramBot bot;
    private final AlertProperties properties;

    public TelegramWebhookController(TelegramBot bot, AlertProperties properties) {
        this.bot = bot;
        this.properties = properties;
    }

    @PostMapping("/api/v1/telegram/webhook")
    public ResponseEntity<Void> receive(@RequestHeader(value = SECRET_HEADER, required = false) String secret,
                                        @RequestBody(required = false) JsonNode update) {
        if (!webhookOn()) {
            return ResponseEntity.notFound().build();
        }
        if (!secretMatches(properties.telegram().webhookSecret(), secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (update != null && update.has("update_id")) {
            bot.handleWebhook(TelegramClient.update(update));
        }
        return ResponseEntity.ok().build();
    }

    private boolean webhookOn() {
        return properties.telegramReady() && properties.telegram().mode() == AlertProperties.Telegram.Mode.WEBHOOK;
    }

    /** Constant-time comparison (no timing hints about the secret); an empty configured secret never matches. */
    static boolean secretMatches(String expected, String given) {
        if (expected == null || expected.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
