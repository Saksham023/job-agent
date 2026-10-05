package io.github.saksham023.jobagent.mcp;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The postedSince argument of match_jobs: a date the user says ("since 1 October") means that day in India.
 */
class JobToolsTest {

    @Test
    void aDateMeansMidnightInIndia() {
        assertThat(JobTools.startOfDayInIndia("2026-10-01")).isEqualTo(Instant.parse("2026-09-30T18:30:00Z"));
        assertThat(JobTools.startOfDayInIndia(" 2026-10-01 ")).isEqualTo(Instant.parse("2026-09-30T18:30:00Z"));
    }

    @Test
    void noDateMeansNoFilter() {
        assertThat(JobTools.startOfDayInIndia(null)).isNull();
        assertThat(JobTools.startOfDayInIndia("  ")).isNull();
    }

    @Test
    void anythingElseIsRejectedWithAClearMessage() {
        assertThatThrownBy(() -> JobTools.startOfDayInIndia("last Monday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("postedSince must be a date like 2026-10-01, got: last Monday");
        assertThatThrownBy(() -> JobTools.startOfDayInIndia("01/10/2026"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}