package io.github.saksham023.jobagent.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Search settings from application.yaml (jobagent.search.*).
 *
 * @param firstBatch   how many top candidates are judged before the first answer (in parallel)
 * @param readyTarget  the background judge keeps this many judged-APPLY jobs ready (not shown yet), then pauses;
 *                     every more_jobs call that takes some wakes it up to refill
 * @param stopAfterNos the background judge stops for good after this many NO verdicts in a row (further down the
 *                     rule ranking APPLY jobs get rare, so going on would mostly cost money)
 * @param parallelism  judge calls at the same time
 * @param pageSize     jobs per answer when the caller does not say how many
 * @param maxPageSize  the most jobs one answer may hold
 * @param model        the judge model ("opus")
 */
@ConfigurationProperties("jobagent.search")
public record SearchProperties(
        @DefaultValue("10") int firstBatch,
        @DefaultValue("15") int readyTarget,
        @DefaultValue("20") int stopAfterNos,
        @DefaultValue("4") int parallelism,
        @DefaultValue("10") int pageSize,
        @DefaultValue("25") int maxPageSize,
        @DefaultValue("opus") String model) {

    public SearchProperties {
        if (firstBatch < 1 || readyTarget < 1 || stopAfterNos < 1 || parallelism < 1 || pageSize < 1 || maxPageSize < pageSize) {
            throw new IllegalArgumentException("jobagent.search.* must be positive, and max-page-size >= page-size");
        }
    }

    /** The defaults, for tests. */
    public static SearchProperties defaults() {
        return new SearchProperties(10, 15, 20, 4, 10, 25, "opus");
    }

    /** The requested page size, or the default, kept within 1..maxPageSize. */
    public int pageSize(Integer requested) {
        return requested == null ? pageSize : Math.min(Math.max(requested, 1), maxPageSize);
    }
}