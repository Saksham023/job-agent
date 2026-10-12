package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.alerts.AlertMatchRepository.Claimed;
import io.github.saksham023.jobagent.alerts.AlertMatchRepository.MatchedJob;
import io.github.saksham023.jobagent.alerts.TelegramClient.TelegramException;
import io.github.saksham023.jobagent.matching.MatchingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The sending poller. Every few seconds: (a) claim one user's due matches (a short transaction, PENDING -> SENDING),
 * (b) outside any transaction, drop jobs closed meanwhile and send ONE Telegram message, (c) record the outcome. A crash
 * between (b) and (c) leaves the matches in SENDING; after sendingTimeout they are sent again (a rare duplicate is
 * accepted: better than a lost alert).
 */
@Component
public class AlertSender {

    private static final Logger log = LoggerFactory.getLogger(AlertSender.class);
    private static final int MAX_USERS_PER_POLL = 20;
    private static final Duration PAUSE_BETWEEN_MESSAGES = Duration.ofMillis(100);   // well under Telegram's 30/s

    private final AlertMatchRepository matches;
    private final AlertPrefsRepository prefs;
    private final TelegramLinkRepository links;
    private final TelegramClient telegram;
    private final AlertProperties properties;
    private final MatchingProperties matching;
    private final TransactionTemplate transaction;

    public AlertSender(AlertMatchRepository matches, AlertPrefsRepository prefs, TelegramLinkRepository links,
                       TelegramClient telegram, AlertProperties properties, MatchingProperties matching,
                       TransactionTemplate transaction) {
        this.matches = matches;
        this.prefs = prefs;
        this.links = links;
        this.telegram = telegram;
        this.properties = properties;
        this.matching = matching;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${jobagent.alerts.send-delay:PT5S}", initialDelayString = "PT25S")
    public void poll() {
        if (!properties.telegramReady()) {
            return;
        }
        try {
            int released = matches.releaseStuck(properties.sendingTimeout());
            if (released > 0) {
                log.warn("Job alerts: {} matches were stuck in SENDING; sending them again", released);
            }
            for (int i = 0; i < MAX_USERS_PER_POLL; i++) {
                Optional<Claimed> claimed = transaction.execute(status -> matches.claimNextUser());
                if (claimed == null || claimed.isEmpty()) {
                    return;
                }
                send(claimed.get());
                Thread.sleep(PAUSE_BETWEEN_MESSAGES);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Job alerts: sending failed, retrying on the next poll: {}", e.toString());
        }
    }

    void send(Claimed claimed) {
        long userId = claimed.userId();
        List<Long> jobIds = claimed.jobIds();
        if (!prefs.find(userId).map(AlertPrefs::enabled).orElse(false)) {    // switched off since the match
            matches.finish(userId, jobIds, "SKIPPED", "alert switched off");
            return;
        }
        Optional<TelegramLinkRepository.Link> link = links.forUser(userId).filter(TelegramLinkRepository.Link::active);
        if (link.isEmpty()) {                                     // paused (/stop) or disconnected since the match
            matches.finish(userId, jobIds, "SKIPPED", "telegram not connected or paused");
            return;
        }
        List<MatchedJob> jobs = matches.jobs(userId, jobIds, matching.country());
        List<Long> closed = jobs.stream().filter(j -> !j.open()).map(MatchedJob::jobId).toList();
        if (!closed.isEmpty()) {
            matches.finish(userId, closed, "SKIPPED", "job closed before sending");
        }
        List<MatchedJob> open = jobs.stream().filter(MatchedJob::open).toList();
        List<Long> openIds = open.stream().map(MatchedJob::jobId).toList();
        if (open.isEmpty()) {
            return;
        }
        try {
            telegram.sendMessage(link.get().chatId(), AlertMessage.build(open, properties.maxJobsPerMessage(), properties.siteUrl()));
            matches.finish(userId, openIds, "SENT", null);
            log.info("Job alerts: sent {} jobs to user {}", open.size(), userId);
        } catch (TelegramException e) {
            failed(userId, openIds, e);
        }
    }

    private void failed(long userId, List<Long> jobIds, TelegramException e) {
        if (e.status() == 403) {                                  // the user blocked the bot: stop until they reconnect
            links.setActive(userId, false);
            matches.finish(userId, jobIds, "SKIPPED", e.getMessage());
            log.info("Job alerts: user {} blocked the bot; their Telegram link is now inactive", userId);
            return;
        }
        boolean tooFast = e.status() == 429 && e.retryAfter() != null;     // Telegram asks us to wait: not a failed try
        Duration delay = tooFast ? e.retryAfter() : backoff(matches.attempts(userId, jobIds));
        matches.retryLater(userId, jobIds, delay, !tooFast, properties.maxAttempts(), e.getMessage());
        log.warn("Job alerts: sending to user {} failed ({}); next try in {}", userId, e.getMessage(), delay);
    }

    /** 1, 2, 4, 8... minutes after each failure, at most an hour. */
    static Duration backoff(int attemptsSoFar) {
        return Duration.ofMinutes(Math.min(60, 1L << Math.min(attemptsSoFar, 6)));
    }
}
