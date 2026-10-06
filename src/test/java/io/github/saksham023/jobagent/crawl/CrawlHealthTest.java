package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.crawl.CrawlHealth.Status;
import io.github.saksham023.jobagent.crawl.CrawlHealth.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** When a crawl looks broken (nothing is closed then) and when it only deserves a warning. */
class CrawlHealthTest {

    @Test
    void aNormalCrawlIsOk() {
        Verdict verdict = CrawlHealth.judge(310, 0, 2, List.of(317, 320, 305));
        assertThat(verdict.status()).isEqualTo(Status.OK);
        assertThat(verdict.alerts()).isEmpty();
    }

    @Test
    void theFirstCrawlHasNothingToCompareWith() {
        assertThat(CrawlHealth.judge(0, 0, 0, List.of()).status()).isEqualTo(Status.OK);
    }

    @Test
    void nothingFoundAfterAGoodCrawlIsSuspect() {
        Verdict verdict = CrawlHealth.judge(0, 0, 0, List.of(7, 7));
        assertThat(verdict.status()).isEqualTo(Status.SUSPECT);
        assertThat(verdict.alerts()).singleElement().asString().startsWith("no jobs found (last good crawl: 7)");
    }

    @Test
    void aCollapseAgainstTheUsualCountIsSuspect() {
        Verdict verdict = CrawlHealth.judge(120, 0, 0, List.of(300, 310, 320, 290, 305));
        assertThat(verdict.status()).isEqualTo(Status.SUSPECT);
        assertThat(verdict.alerts()).singleElement().asString().startsWith("only 120 jobs, usually about 305");
        // small companies swing a lot: 3 instead of 8 is not judged
        assertThat(CrawlHealth.judge(3, 0, 0, List.of(8, 8, 9)).status()).isEqualTo(Status.OK);
    }

    @Test
    void manyUnresolvedLocationsOrMissingDescriptionsAreWarnings() {
        Verdict verdict = CrawlHealth.judge(80, 20, 30, List.of(100));
        assertThat(verdict.status()).isEqualTo(Status.OK);
        assertThat(verdict.alerts()).containsExactly(
                "20 of 100 jobs have no resolved location (add aliases?)",
                "30 of 80 jobs have no description (detail requests failing?)");
    }

    @Test
    void medianOfOddAndEvenCounts() {
        assertThat(CrawlHealth.median(List.of(5, 1, 3))).isEqualTo(3);
        assertThat(CrawlHealth.median(List.of(4, 1, 3, 2))).isEqualTo(2);
    }
}