package io.github.saksham023.jobagent.crawl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * jobagent.crawl.schedule.*: the automatic crawl of every enabled company.
 *
 * @param enabled             false: no automatic crawls (manual POST /admin/crawl still works)
 * @param delay               FIXED DELAY of the regular loop: the next run starts this long after the previous one
 *                            FINISHED (ISO-8601, e.g. PT30M), so runs never overlap or pile up; the regular loop crawls
 *                            every company without its own timing (config.minCrawlHours)
 * @param initialDelay        wait after the app starts before the first run of each loop
 * @param slowCheckDelay      how often the loop of the slow companies (those with config.minCrawlHours: Microsoft,
 *                            Qualcomm) looks which of them is due; each due company is crawled on its own thread, so a
 *                            slow or throttled one never holds up the regular loop
 * @param retryAfter          a slow company whose crawl ended PARTIAL or FAILED (throttled) is tried again this long
 *                            after it ended, instead of waiting for its minCrawlHours
 * @param maxRetries          at most this many such retries in a row; then it waits for its minCrawlHours again
 * @param gapFill             after a scheduled run, let the model fill the new jobs' gaps
 * @param gapFillModel        the model for that
 * @param gapFillParallelism  model calls at the same time
 * @param closeAfterMisses    a job is closed when this many successful crawls of its company in a row did not see it
 */
@ConfigurationProperties("jobagent.crawl.schedule")
public record CrawlScheduleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT30M") Duration delay,
        @DefaultValue("PT1M") Duration initialDelay,
        @DefaultValue("PT5M") Duration slowCheckDelay,
        @DefaultValue("PT30M") Duration retryAfter,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("true") boolean gapFill,
        @DefaultValue("opus") String gapFillModel,
        @DefaultValue("10") int gapFillParallelism,
        @DefaultValue("2") int closeAfterMisses) {

    public CrawlScheduleProperties {
        if (closeAfterMisses < 1) {
            throw new IllegalArgumentException("jobagent.crawl.schedule.close-after-misses must be at least 1");
        }
        if (delay.isNegative() || delay.isZero() || slowCheckDelay.isNegative() || slowCheckDelay.isZero()) {
            throw new IllegalArgumentException("jobagent.crawl.schedule.delay and slow-check-delay must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("jobagent.crawl.schedule.max-retries must not be negative");
        }
    }
}
