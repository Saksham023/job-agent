package io.github.saksham023.jobagent.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Webhook mode: when the app has started, tells Telegram where to deliver the bot's messages
 * ({siteUrl}api/v1/telegram/webhook) and which secret to send along. Done on every start (harmless when nothing changed),
 * so a new site address or secret takes effect with a restart. Polling mode does nothing here.
 */
@Component
public class TelegramWebhookSetup {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookSetup.class);
    static final String PATH = "api/v1/telegram/webhook";
    private static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");      // what Telegram accepts

    private final TelegramClient telegram;
    private final AlertProperties properties;

    public TelegramWebhookSetup(TelegramClient telegram, AlertProperties properties) {
        this.telegram = telegram;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!properties.telegramReady()) {
            log.info("Telegram alerts: off (no bot token/username set)");
            return;
        }
        if (properties.telegram().mode() != AlertProperties.Telegram.Mode.WEBHOOK) {
            log.info("Telegram alerts: on, bot @{} reads its messages by polling", properties.telegram().botUsername());
            return;
        }
        String problem = problem(properties.siteUrl(), properties.telegram().webhookSecret());
        if (problem != null) {
            log.error("Telegram alerts: webhook NOT set: {}", problem);
            return;
        }
        String url = webhookUrl(properties.siteUrl());
        try {
            telegram.setWebhook(url, properties.telegram().webhookSecret());
            log.info("Telegram alerts: on, bot @{} delivers its messages to {}", properties.telegram().botUsername(), url);
        } catch (TelegramClient.TelegramException e) {
            log.error("Telegram alerts: setting the webhook failed: {}", e.getMessage());
        }
    }

    /** What is wrong with the settings for webhook mode, or null. Telegram only calls public https addresses. */
    static String problem(String siteUrl, String secret) {
        if (siteUrl == null || !siteUrl.startsWith("https://")) {
            return "jobagent.alerts.site-url must be the public https address (e.g. https://jobserver.tail8cf172.ts.net/)";
        }
        if (secret == null || !SECRET.matcher(secret).matches()) {
            return "jobagent.alerts.telegram.webhook-secret must be 1-256 characters of A-Z a-z 0-9 _ - (e.g. openssl rand -hex 32)";
        }
        return null;
    }

    static String webhookUrl(String siteUrl) {
        return (siteUrl.endsWith("/") ? siteUrl : siteUrl + "/") + PATH;
    }
}
