package io.github.saksham023.jobagent.matching;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Matching settings from application.yaml (jobagent.matching.*), so they can be tuned without a code change.
 *
 * @param country      jobs must be in this country (ISO code)
 * @param yearsBelow   default experience window: a job is shown if its range reaches down to the candidate's
 *                     years minus this...
 * @param yearsAbove   ...and up to the candidate's years plus this (2 years with 2 below and 1 above = 0 to 3)
 * @param roundUpFrom  fractional years at or above this round up: with 0.5, 1.5 -> 2 and 1.4 -> 1
 */
@ConfigurationProperties("jobagent.matching")
public record MatchingProperties(
        @DefaultValue("IN") String country,
        @DefaultValue("2") int yearsBelow,
        @DefaultValue("1") int yearsAbove,
        @DefaultValue("0.5") double roundUpFrom) {

    public MatchingProperties {
        if (yearsBelow < 0 || yearsAbove < 0) {
            throw new IllegalArgumentException("jobagent.matching.years-below/years-above must not be negative");
        }
        if (roundUpFrom <= 0 || roundUpFrom > 1) {
            throw new IllegalArgumentException("jobagent.matching.round-up-from must be in (0, 1]");
        }
    }

    /** The defaults, for tests and tools that build the matching classes by hand. */
    public static MatchingProperties defaults() {
        return new MatchingProperties("IN", 2, 1, 0.5);
    }
}
