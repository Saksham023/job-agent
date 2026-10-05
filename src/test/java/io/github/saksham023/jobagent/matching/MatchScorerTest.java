package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.matching.MatchCandidateRepository.Candidate;
import io.github.saksham023.jobagent.matching.MatchScorer.Match;
import io.github.saksham023.jobagent.matching.MatchScorer.ResolvedProfile;
import io.github.saksham023.jobagent.matching.SkillImplications.Implied;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoring rules, without a database: hand-made candidate jobs against a Java backend profile.
 */
class MatchScorerTest {

    private final MatchScorer scorer = new MatchScorer();

    private static final ResolvedProfile JAVA_BACKEND = new ResolvedProfile(3,
            Set.of("Java", "Spring Boot", "Kafka", "PostgreSQL", "Redis", "AWS"),
            Set.of("Java"), Set.of("Bengaluru"), true);

    private static Candidate job(List<String> required, List<String> preferred, List<String> languages,
                                 Integer minYears, Integer maxYears, List<String> cities, boolean remote) {
        return new Candidate(1, "Acme", "Backend Engineer", "https://example.com/1", cities, remote,
                minYears, maxYears, "SOFTWARE_ENGINEERING", List.of(), required, preferred, languages);
    }

    @Test
    void perfectFitScoresHundredAndExplainsIt() {
        Match m = scorer.score(job(List.of("Java", "Kafka", "PostgreSQL", "Redis"), List.of(), List.of("Java"),
                2, 5, List.of("Bengaluru"), false), JAVA_BACKEND);

        assertThat(m.score()).isEqualTo(100);
        assertThat(m.matchedRequired()).containsExactly("Java", "Kafka", "PostgreSQL", "Redis");
        assertThat(m.missingRequired()).isEmpty();
        assertThat(m.reasons()).contains("4/4 required skills", "main language Java matches", "asks 2-5 years, you have 3");
    }

    @Test
    void aPostingWithFewSkillsCannotGiveAPerfectSkillMatch() {
        Match m = scorer.score(job(List.of("Java", "Kafka"), List.of(), List.of("Java"),
                2, 5, List.of("Bengaluru"), false), JAVA_BACKEND);

        assertThat(m.reasons()).contains("2/2 required skills");
        assertThat(m.score()).isEqualTo(68);                      // 65*(2/4) + 20 + 15 = 67.5: counted as 2 of 4
    }

    @Test
    void missingSkillsAreListedAndLowerTheScore() {
        Match m = scorer.score(job(List.of("Java", "Go", "Kubernetes", "Terraform"), List.of(), List.of("Java"),
                2, 5, List.of("Bengaluru"), false), JAVA_BACKEND);

        assertThat(m.missingRequired()).containsExactly("Go", "Kubernetes", "Terraform");
        assertThat(m.score()).isEqualTo(51);                      // 65*(1/4) + 20 + 15 = 51.25
    }

    @Test
    void longSkillListsAreCappedSoCoreMatchesStillCount() {
        List<String> seventeen = List.of("Java", "Kafka", "PostgreSQL", "Redis", "AWS",
                "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A11", "A12");
        Match m = scorer.score(job(seventeen, List.of(), List.of(), null, null, List.of("Bengaluru"), false), JAVA_BACKEND);

        assertThat(m.score()).isEqualTo(58);                      // 65*(5/8) + 20*0.5 + 15*0.5 = 40.6+10+7.5
    }

    @Test
    void preferredSkillsCountHalf() {
        Match m = scorer.score(job(List.of("Java"), List.of("Kafka", "Rust"), List.of("Java"),
                null, null, List.of("Bengaluru"), false), JAVA_BACKEND);

        assertThat(m.matchedPreferred()).containsExactly("Kafka");
        assertThat(m.reasons()).contains("1/1 required skills, 1/2 preferred");
    }

    @Test
    void jobWithoutSkillsOrLanguageIsNeutralNotZero() {
        Match m = scorer.score(job(List.of(), List.of(), List.of(), null, null, List.of("Bengaluru"), false), JAVA_BACKEND);

        assertThat(m.score()).isEqualTo(50);                      // 65*0.5 + 20*0.5 + 15*0.5
        assertThat(m.reasons()).contains("job lists no recognizable skills (neutral)");
    }

    @Test
    void languageFullPartialOrNone() {
        assertThat(scorer.score(job(List.of("Java"), List.of(), List.of("Go", "Java"), null, null,
                List.of("Bengaluru"), false), JAVA_BACKEND).reasons())
                .contains("uses Java (main language is Go)");
        assertThat(scorer.score(job(List.of("Python"), List.of(), List.of("Python"), null, null,
                List.of("Bengaluru"), false), JAVA_BACKEND).reasons())
                .contains("main language Python is not one of yours");
    }

    @Test
    void experienceUnderOverAndUnknown() {
        assertThat(scorer.score(job(List.of("Java"), List.of(), List.of("Java"), 4, 6, List.of("Bengaluru"), false),
                JAVA_BACKEND).reasons()).contains("asks 4-6 years, you have 3 (slightly under)");
        assertThat(scorer.score(job(List.of("Java"), List.of(), List.of("Java"), 0, 1, List.of("Bengaluru"), false),
                JAVA_BACKEND).reasons()).contains("asks 0-1 years, you have 3 (above the range)");
        assertThat(scorer.score(job(List.of("Java"), List.of(), List.of("Java"), null, null, List.of("Bengaluru"), false),
                JAVA_BACKEND).reasons()).noneMatch(r -> r.startsWith("asks"));
    }

    @Test
    void locationNeverChangesTheScore() {
        Match inYourCity = scorer.score(job(List.of("Java"), List.of(), List.of("Java"), 2, 5, List.of("Bengaluru"), false), JAVA_BACKEND);
        Match elsewhere = scorer.score(job(List.of("Java"), List.of(), List.of("Java"), 2, 5, List.of("Pune"), false), JAVA_BACKEND);
        Match noCity = scorer.score(job(List.of("Java"), List.of(), List.of("Java"), 2, 5, List.of(), false), JAVA_BACKEND);

        assertThat(elsewhere.score()).isEqualTo(inYourCity.score());
        assertThat(noCity.score()).isEqualTo(inYourCity.score());
    }

    @Test
    void remoteIsShownButNotScored() {
        Match remote = scorer.score(job(List.of("Java"), List.of(), List.of("Java"), 2, 5, List.of("Pune"), true), JAVA_BACKEND);
        Match onSite = scorer.score(job(List.of("Java"), List.of(), List.of("Java"), 2, 5, List.of("Pune"), false), JAVA_BACKEND);

        assertThat(remote.reasons()).contains("remote");
        assertThat(remote.score()).isEqualTo(onSite.score());
    }

    @Test
    void impliedSkillsCountWithTheirCreditAndSayWhereTheyComeFrom() {
        ResolvedProfile profile = new ResolvedProfile(3, Set.of("Java", "DynamoDB", "Distributed Systems"),
                Set.of("Java"), Set.of(), true, Map.of(
                        "AWS", new Implied("AWS", 1.0, "DynamoDB"),
                        "Microservices", new Implied("Microservices", 0.5, "Distributed Systems")));

        Match m = scorer.score(job(List.of("Java", "AWS", "Microservices", "Go"), List.of(), List.of("Java"),
                2, 5, List.of("Bengaluru"), false), profile);

        assertThat(m.matchedRequired()).containsExactly("Java", "AWS (via DynamoDB)",
                "Microservices (half credit, via Distributed Systems)");
        assertThat(m.missingRequired()).containsExactly("Go");
        assertThat(m.reasons()).contains("2.5/4 required skills");
        assertThat(m.score()).isEqualTo(76);                      // 65*(2.5/4) + 20 + 15 = 75.6
    }
}
