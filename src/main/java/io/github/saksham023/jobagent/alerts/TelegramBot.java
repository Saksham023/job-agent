package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.alerts.TelegramClient.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Handles the messages people send to the bot. They arrive in one of two ways (jobagent.alerts.telegram.mode):
 * POLLING  this class asks Telegram every few seconds (getUpdates): for local testing, nothing has to be public;
 * WEBHOOK  Telegram POSTs each message to TelegramWebhookController, which calls handleWebhook: for the server.
 * Either way the same three commands:
 * /start TOKEN  links the chat to the account that created the one-time token on the website
 * /start        resumes alerts for an already linked chat
 * /stop         pauses alerts (pending matches are skipped, not saved up)
 * The position read up to (the next update_id) is stored, so a message is handled once, even when Telegram retries a
 * webhook call or the app restarts. In polling mode the bot NEVER removes a webhook: if the bot has one (the server's
 * bot used locally by mistake), polling just stops with a warning instead of breaking the server.
 */
@Component
public class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    static final String HELP = "Hi! I send new jobs that match your JobRadar alert.\n"
            + "To connect: open JobRadar → your menu → Job alerts → Connect Telegram.";

    private final TelegramClient telegram;
    private final TelegramLinkRepository links;
    private final AlertService alerts;
    private final AlertProperties properties;
    private final ReentrantLock handling = new ReentrantLock();      // one message at a time, in order
    private volatile boolean webhookWarned;

    public TelegramBot(TelegramClient telegram, TelegramLinkRepository links, AlertService alerts, AlertProperties properties) {
        this.telegram = telegram;
        this.links = links;
        this.alerts = alerts;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${jobagent.alerts.telegram.poll-delay:PT3S}", initialDelayString = "PT15S")
    public void poll() {
        if (!properties.telegramReady() || properties.telegram().mode() != AlertProperties.Telegram.Mode.POLLING || webhookWarned) {
            return;
        }
        try {
            for (Update update : telegram.updates(links.nextUpdateId(properties.telegram().botId()))) {
                process(update);
            }
        } catch (TelegramClient.TelegramException e) {
            if (e.status() == 409) {                                 // this bot has a webhook: it belongs to a server
                webhookWarned = true;
                log.warn("Telegram bot: this bot delivers to a webhook, so polling is off. Use a separate test bot locally "
                        + "(the webhook is left untouched).");
            } else {
                log.debug("Telegram bot: reading messages failed: {}", e.getMessage());
            }
        } catch (RuntimeException e) {
            log.debug("Telegram bot: reading messages failed: {}", e.toString());
        }
    }

    /** A message delivered by Telegram's webhook call. */
    public void handleWebhook(Update update) {
        process(update);
    }

    /** Handles a message once: an update_id below the stored position was already handled (a retry or a repeat). */
    private void process(Update update) {
        handling.lock();
        try {
            long botId = properties.telegram().botId();
            if (update.updateId() < links.nextUpdateId(botId)) {
                log.debug("Telegram bot: update {} already handled", update.updateId());
                return;
            }
            try {
                handle(update);
            } catch (RuntimeException e) {
                log.warn("Telegram bot: could not handle a message: {}", e.toString());
            }
            links.saveNextUpdateId(botId, update.updateId() + 1);    // handled (or given up): never again
        } finally {
            handling.unlock();
        }
    }

    void handle(Update update) {
        String text = update.text() == null ? "" : update.text().strip();
        long chatId = update.chatId();
        if (text.startsWith("/start ")) {
            String token = text.substring("/start ".length()).strip();
            Optional<Long> userId = alerts.useLinkToken(token, chatId);
            reply(chatId, userId.isPresent()
                    ? "Connected ✓ You'll get new jobs matching your JobRadar alert here, right after each crawl.\n"
                    + "Send /stop to pause, /start to resume."
                    : "This connect link has expired or was already used. Open JobRadar → Job alerts → Connect Telegram again.");
        } else if (text.equals("/start")) {
            Optional<TelegramLinkRepository.Link> link = links.forChat(chatId);
            if (link.isPresent()) {
                links.setActive(link.get().userId(), true);
                reply(chatId, "Alerts resumed ✓");
            } else {
                reply(chatId, HELP);
            }
        } else if (text.equals("/stop")) {
            Optional<TelegramLinkRepository.Link> link = links.forChat(chatId);
            if (link.isPresent()) {
                links.setActive(link.get().userId(), false);
                reply(chatId, "Alerts paused. Send /start to resume.");
            } else {
                reply(chatId, HELP);
            }
        } else {
            reply(chatId, HELP);
        }
    }

    private void reply(long chatId, String text) {
        telegram.sendMessage(chatId, AlertMessage.escape(text));
    }
}
