package io.github.saksham023.jobagent.crawl;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * jobagent.crawl.schedule.*: the automatic crawl of every enabled company.
 *
 * @param enabled             false: no automatic crawls (manual POST /admin/crawl still works)
 * @param cron                when to crawl (Spring cron: second minute hour day month weekday); every 6 hours
 * @param gapFill             after a scheduled run, let the model fill the new jobs' gaps
 * @param gapFillModel        the model for that
 * @param gapFillParallelism  model calls at the same time
 * @param closeAfterMisses    a job is closed when this many successful crawls of its company in a row did not see it
 */
@ConfigurationProperties("jobagent.crawl.schedule")
public record CrawlScheduleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 0 */6 * * *") String cron,
        @DefaultValue("true") boolean gapFill,
        @DefaultValue("opus") String gapFillModel,
        @DefaultValue("10") int gapFillParallelism,
        @DefaultValue("2") int closeAfterMisses) {

    public CrawlScheduleProperties {
        if (closeAfterMisses < 1) {
            throw new IllegalArgumentException("jobagent.crawl.schedule.close-after-misses must be at least 1");
        }
    }
}