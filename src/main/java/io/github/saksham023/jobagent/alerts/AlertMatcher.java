package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.matching.MatchingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

/**
 * The matching poller. Every few seconds it takes queued jobs (alert_job_queue, filled by the crawl together with each
 * new job), finds the users whose alert fits each job (one SQL statement on alert_prefs), writes the pairs to
 * alert_matches and removes the jobs from the queue: all in ONE transaction, so a crash leaves the queue untouched and
 * the next run simply does it again (already matched pairs are skipped).
 */
@Component
public class AlertMatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertMatcher.class);
    private static final Duration STALE_QUEUE_ROW = Duration.ofDays(1);

    private final AlertMatchRepository matches;
    private final AlertProperties properties;
    private final MatchingProperties matching;
    private final TransactionTemplate transaction;

    public AlertMatcher(AlertMatchRepository matches, AlertProperties properties, MatchingProperties matching,
                        TransactionTemplate transaction) {
        this.matches = matches;
        this.properties = properties;
        this.matching = matching;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${jobagent.alerts.match-delay:PT5S}", initialDelayString = "PT20S")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        try {
            while (matchBatch() == properties.matchBatch()) {
                // a full batch: there may be more waiting, take the next one right away
            }
        } catch (RuntimeException e) {
            log.warn("Job alerts: matching failed, retrying on the next poll: {}", e.toString());
        }
    }

    /** One batch of queued jobs; returns how many were taken. */
    int matchBatch() {
        Integer taken = transaction.execute(status -> {
            List<Long> jobIds = matches.claimQueuedJobs(properties.matchBatch());
            if (jobIds.isEmpty()) {
                return 0;
            }
            int added = matches.match(jobIds, matching.country(), properties.maxJobAge());
            matches.dequeue(jobIds);
            if (added > 0) {
                log.info("Job alerts: {} new jobs matched {} times", jobIds.size(), added);
            } else {
                log.debug("Job alerts: {} new jobs, no matching alert", jobIds.size());
            }
            return jobIds.size();
        });
        return taken == null ? 0 : taken;
    }

    @Scheduled(cron = "0 40 4 * * *")
    public void dropStaleQueueRows() {
        int dropped = matches.dropStaleQueueRows(STALE_QUEUE_ROW);
        if (dropped > 0) {
            log.warn("Job alerts: dropped {} queued jobs that never got their requirements", dropped);
        }
    }
}
