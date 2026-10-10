package io.github.saksham023.jobagent.account;

import io.github.saksham023.jobagent.requirements.JobFamily;

import java.time.Instant;
import java.util.List;

/**
 * A signed-in user's profile: the facts read from their resume, as they may have edited them.
 *
 * @param headline the person's broad role ("backend engineer"), used in the referral message; null = unknown
 * @param build    what the person builds, a phrase starting with a verb ("building ..."); null = unknown
 * @param jobYearsFrom the experience range "Match my resume" filters on (from the years, the same rule as the AI search)
 */
public record UserProfile(String headline, String build, Double years, List<String> mainLanguages, List<String> skills, String rolesWanted,
                          List<JobFamily> families, String driveLink, String source, Instant readAt, Instant editedAt,
                          Integer jobYearsFrom, Integer jobYearsTo) {

    /** The facts a resume read gives, or a user saves. */
    public record Facts(String headline, String build, Double years, List<String> mainLanguages, List<String> skills, String rolesWanted,
                        List<JobFamily> families) {
    }
}
