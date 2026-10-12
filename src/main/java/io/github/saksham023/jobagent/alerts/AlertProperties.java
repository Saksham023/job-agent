package io.github.saksham023.jobagent.alerts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * jobagent.alerts.*: job alerts sent to Telegram after every crawl round.
 *
 * @param enabled           false = no matching, no sending, no bot (new jobs are still queued)
 * @param matchBatch        queued jobs one matcher run takes
 * @param maxJobsPerMessage jobs listed in one Telegram message; the rest are counted as "and N more"
 * @param maxAttempts       failed sends before a match is given up (status FAILED)
 * @param sendingTimeout    a match left in SENDING longer than this (a crash mid-send) is retried
 * @param maxJobAge         only jobs POSTED within this time are sent, by the posting time the website shows: an alert is
 *                          about new jobs, so an old posting (found late, or queued again) never reaches anyone
 * @param gapFillWait       how long a new job may wait for Claude's gap fill before it is matched anyway, so the alert
 *                          uses Claude's family and years like the website does
 * @param siteUrl           the web app's address, linked in every message ("manage your alert")
 * @param telegram          the bot
 */
@ConfigurationProperties("jobagent.alerts")
public record AlertProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50") int matchBatch,
        @DefaultValue("20") int maxJobsPerMessage,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT5M") Duration sendingTimeout,
        @DefaultValue("P2D") Duration maxJobAge,
        @DefaultValue("PT30M") Duration gapFillWait,
        @DefaultValue("") String siteUrl,
        @DefaultValue Telegram telegram) {

    /**
     * @param botToken     from @BotFather (env JOBAGENT_ALERTS_TELEGRAM_BOT_TOKEN, never in git); blank = Telegram off
     * @param botUsername  the bot's username without @, for the connect link t.me/{username}?start={token}
     * @param apiUrl       Telegram's Bot API
     * @param linkTokenTtl how long a "connect Telegram" link works
     * @param mode         how the bot receives messages: POLLING (asks Telegram every few seconds; local testing, nothing
     *                     public needed) or WEBHOOK (Telegram calls {siteUrl}api/v1/telegram/webhook; production)
     * @param webhookSecret the secret Telegram sends back in X-Telegram-Bot-Api-Secret-Token on every webhook call (env
     *                     JOBAGENT_ALERTS_TELEGRAM_WEBHOOK_SECRET, never in git; 1-256 of A-Z a-z 0-9 _ -)
     */
    public record Telegram(
            @DefaultValue("") String botToken,
            @DefaultValue("") String botUsername,
            @DefaultValue("https://api.telegram.org") String apiUrl,
            @DefaultValue("PT10M") Duration linkTokenTtl,
            @DefaultValue("POLLING") Mode mode,
            @DefaultValue("") String webhookSecret) {

        public enum Mode { POLLING, WEBHOOK }

        /** The bot's public id: the number before ':' in the token (0 when there is none). */
        public long botId() {
            int colon = botToken == null ? -1 : botToken.indexOf(':');
            try {
                return colon > 0 ? Long.parseLong(botToken.substring(0, colon)) : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public boolean configured() {
            return botToken != null && !botToken.isBlank() && botUsername != null && !botUsername.isBlank();
        }
    }

    /** Telegram can be used: alerts on and a bot configured. */
    public boolean telegramReady() {
        return enabled && telegram.configured();
    }
}
