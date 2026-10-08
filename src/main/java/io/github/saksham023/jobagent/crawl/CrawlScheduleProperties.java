package io.github.saksham023.jobagent.crawl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * jobagent.crawl.schedule.*: the automatic crawl of every enabled company.
 *
 * @param enabled             false: no automatic crawls (manual POST /admin/crawl still works)
 * @param delay               FIXED DELAY of every server group (ISO-8601, e.g. PT30M): a group starts its next round this
 *                            long after its previous round FINISHED, so rounds of one group never overlap or pile up
 * @param initialDelay        wait after the app starts before the first check
 * @param checkDelay          how often the tick looks for server groups that are due (cheap; PT1M)
 * @param gapFill             after a company's crawl, let the model fill the gaps of its jobs never asked about
 * @param gapFillModel        the model for that
 * @param gapFillParallelism  model calls at the same time
 * @param closeAfterMisses    a job is closed when this many successful crawls of its company in a row did not see it
 */
@ConfigurationProperties("jobagent.crawl.schedule")
public record CrawlScheduleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("PT30M") Duration delay,
        @DefaultValue("PT1M") Duration initialDelay,
        @DefaultValue("PT1M") Duration checkDelay,
        @DefaultValue("true") boolean gapFill,
        @DefaultValue("sonnet") String gapFillModel,
        @DefaultValue("10") int gapFillParallelism,
        @DefaultValue("2") int closeAfterMisses) {

    public CrawlScheduleProperties {
        if (closeAfterMisses < 1) {
            throw new IllegalArgumentException("jobagent.crawl.schedule.close-after-misses must be at least 1");
        }
        if (delay.isNegative() || delay.isZero() || checkDelay.isNegative() || checkDelay.isZero()) {
            throw new IllegalArgumentException("jobagent.crawl.schedule.delay and check-delay must be positive");
        }
    }
}
