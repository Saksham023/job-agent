package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.alerts.TelegramClient.TelegramException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The signed-in user's job alert.
 *
 * GET    /api/v1/me/alerts            the alert (or suggestions from the profile when none is saved) + Telegram status
 * PUT    /api/v1/me/alerts            save it {enabled, families, minYears, maxYears, includeUnstated, skills, cities, companies}
 * POST   /api/v1/me/alerts/telegram   {url}: the one-time link that connects this account to the bot
 * DELETE /api/v1/me/alerts/telegram   disconnect Telegram
 * POST   /api/v1/me/alerts/test       send a test message to the connected chat
 */
@RestController
@RequestMapping("/api/v1/me/alerts")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    public AlertService.View get(@AuthenticationPrincipal Jwt jwt) {
        return alerts.view(userId(jwt));
    }

    @PutMapping
    public AlertService.View save(@AuthenticationPrincipal Jwt jwt, @RequestBody AlertPrefs prefs) {
        return alerts.save(userId(jwt), prefs);
    }

    @PostMapping("/telegram")
    public Map<String, String> connect(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("url", alerts.connectUrl(userId(jwt)));
    }

    @DeleteMapping("/telegram")
    public AlertService.View disconnect(@AuthenticationPrincipal Jwt jwt) {
        alerts.disconnect(userId(jwt));
        return alerts.view(userId(jwt));
    }

    @PostMapping("/test")
    public ResponseEntity<Void> test(@AuthenticationPrincipal Jwt jwt) {
        alerts.test(userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TelegramException.class)
    public ResponseEntity<Map<String, String>> telegramFailed(TelegramException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Telegram did not accept the message: " + e.getMessage()));
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
