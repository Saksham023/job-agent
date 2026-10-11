package io.github.saksham023.jobagent.alerts;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramWebhookTest {

    @Test
    void onlyTheRightSecretIsAccepted() {
        assertThat(TelegramWebhookController.secretMatches("abc123", "abc123")).isTrue();
        assertThat(TelegramWebhookController.secretMatches("abc123", "abc124")).isFalse();
        assertThat(TelegramWebhookController.secretMatches("abc123", null)).isFalse();
        assertThat(TelegramWebhookController.secretMatches("", "")).isFalse();          // no secret configured: refuse all
    }

    @Test
    void webhookModeNeedsAPublicHttpsAddressAndAValidSecret() {
        assertThat(TelegramWebhookSetup.problem("https://jobserver.tail8cf172.ts.net/", "a".repeat(64))).isNull();
        assertThat(TelegramWebhookSetup.problem("http://localhost:5173/", "abc")).contains("https");
        assertThat(TelegramWebhookSetup.problem("https://x.example/", "")).contains("webhook-secret");
        assertThat(TelegramWebhookSetup.problem("https://x.example/", "has spaces!")).contains("webhook-secret");
        assertThat(TelegramWebhookSetup.webhookUrl("https://x.example")).isEqualTo("https://x.example/api/v1/telegram/webhook");
        assertThat(TelegramWebhookSetup.webhookUrl("https://x.example/")).isEqualTo("https://x.example/api/v1/telegram/webhook");
    }

    @Test
    void readsAWebhookBody() {
        var body = JsonMapper.builder().build().readTree("""
                {"update_id": 912, "message": {"chat": {"id": 4242}, "text": "/stop"}}""");
        assertThat(TelegramClient.update(body)).isEqualTo(new TelegramClient.Update(912, 4242, "/stop"));
    }

    @Test
    void theBotIdIsThePublicPartOfTheToken() {
        assertThat(telegram("7123456789:AAH3k-secret").botId()).isEqualTo(7123456789L);
        assertThat(telegram("").botId()).isZero();
        assertThat(telegram("garbage").botId()).isZero();
    }

    private static AlertProperties.Telegram telegram(String token) {
        return new AlertProperties.Telegram(token, "Bot", "https://api.telegram.org", Duration.ofMinutes(10),
                AlertProperties.Telegram.Mode.WEBHOOK, "s");
    }
}
