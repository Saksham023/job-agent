package io.github.saksham023.jobagent.search;

import io.github.saksham023.jobagent.eval.JudgeProfile;
import io.github.saksham023.jobagent.matching.ExperienceWindow;
import io.github.saksham023.jobagent.matching.MatchScorer.Match;
import io.github.saksham023.jobagent.matching.MatchScorer.ResolvedProfile;
import io.github.saksham023.jobagent.matching.MatchService.MatchResponse;
import io.github.saksham023.jobagent.matching.Profile;
import io.github.saksham023.jobagent.search.SearchRepository.JobTraits;
import io.github.saksham023.jobagent.search.SearchRepository.Status;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parts of the search that need no database or model: the candidate the judge reads, the profile hash that
 * lets later searches reuse verdicts, the answer's note, and the settings.
 */
class SearchServiceTest {

    private static MatchResponse understood(Set<String> skills, Set<String> languages) {
        return new MatchResponse(new ResolvedProfile(2, skills, languages, Set.of(), true), List.of(), List.of(),
                null, null, 0, List.of());
    }

    private static final Profile PROFILE = new Profile(1.6, List.of("java", "springboot", "kafka"), List.of(),
            List.of(), true, List.of());

    @Test
    void theJudgeReadsCanonicalSkillsDecimalYearsAndWants() {
        JudgeProfile candidate = SearchService.judgeProfile(PROFILE,
                understood(Set.of("Java", "Spring Boot", "Kafka"), Set.of("Java")), "  backend roles ");

        assertThat(candidate.years()).isEqualTo(1.6);
        assertThat(candidate.mainLanguages()).containsExactly("Java");
        assertThat(candidate.otherSkills()).containsExactly("Kafka", "Spring Boot");       // sorted, language removed
        assertThat(candidate.wants()).isEqualTo("backend roles");
    }

    @Test
    void withoutWantsTheJudgeDecidesFromTheSkills() {
        JudgeProfile candidate = SearchService.judgeProfile(PROFILE, understood(Set.of("Java"), Set.of("Java")), null);

        assertThat(candidate.wants()).startsWith("Not stated");
    }

    @Test
    void theSameCandidateAlwaysGetsTheSameHash() {
        JudgeProfile a = new JudgeProfile("search", null, 1.6, List.of("Java"), List.of("Kafka", "Spring Boot"), "Backend");
        JudgeProfile b = new JudgeProfile("search", null, 1.6, List.of("Java"), List.of("Spring Boot", "Kafka"), " backend ");

        assertThat(SearchService.profileHash(a)).isEqualTo(SearchService.profileHash(b)).hasSize(64);
    }

    @Test
    void anythingTheJudgeReadsChangesTheHash() {
        JudgeProfile base = new JudgeProfile("search", null, 1.6, List.of("Java"), List.of("Kafka"), "backend");
        String hash = SearchService.profileHash(base);

        assertThat(SearchService.profileHash(new JudgeProfile("search", null, 2.6, List.of("Java"), List.of("Kafka"), "backend")))
                .isNotEqualTo(hash);
        assertThat(SearchService.profileHash(new JudgeProfile("search", null, 1.6, List.of("Java"), List.of("Redis"), "backend")))
                .isNotEqualTo(hash);
        assertThat(SearchService.profileHash(new JudgeProfile("search", null, null, List.of("Java"), List.of("Kafka"), "backend")))
                .isNotEqualTo(hash);
    }

    @Test
    void theNoteSaysWhatHappensNext() {
        assertThat(SearchService.note(10, 10, new Status(12, 0, 40, 30, 0), true, false))
                .isEqualTo("12 more APPLY jobs are ready.");
        assertThat(SearchService.note(3, 10, new Status(0, 0, 40, 30, 0), true, false))
                .startsWith("40 more candidates are being judged");
        assertThat(SearchService.note(3, 10, new Status(0, 2, 0, 70, 20), false, true))
                .isEqualTo("That is everything that fits for this search.");
    }

    @Test
    void pageSizeDefaultsAndLimits() {
        SearchProperties settings = SearchProperties.defaults();

        assertThat(settings.pageSize(null)).isEqualTo(10);
        assertThat(settings.pageSize(20)).isEqualTo(20);
        assertThat(settings.pageSize(500)).isEqualTo(25);
        assertThat(settings.pageSize(0)).isEqualTo(1);
        assertThatThrownBy(() -> new SearchProperties(10, 0, 20, 4, 10, 25, "opus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- tiers

    private static final ExperienceWindow WINDOW = new ExperienceWindow(0, 3);
    private static final Set<String> SKILLS = Set.of("Java", "Python", "C#", "Spring Boot", "Kafka");

    private static Match job(String title, Integer minYears) {
        return new Match(1, 70, "Acme", title, "https://example.com", List.of(), false, null, minYears, null,
                "SOFTWARE_ENGINEERING", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static JobTraits traits(String specialization, String... languages) {
        return new JobTraits(specialization, List.of(languages));
    }

    @Test
    void aSeniorTitleWithoutYearsGoesToTheLowPriorityTier() {
        assertThat(SearchService.lowPriorityReason(job("Lead Software Engineer", null), traits(null), WINDOW, SKILLS))
                .isEqualTo("senior title, no years stated");
        assertThat(SearchService.lowPriorityReason(job("Principal Engineer", null), traits(null), WINDOW, SKILLS)).isNotNull();
    }

    @Test
    void statedYearsOrAWindowThatReachesTheTitleKeepTheJobInTheMainTier() {
        assertThat(SearchService.lowPriorityReason(job("Lead Software Engineer", 3), traits(null), WINDOW, SKILLS)).isNull();
        assertThat(SearchService.lowPriorityReason(job("Lead Software Engineer", null), traits(null),
                new ExperienceWindow(4, 7), SKILLS)).isNull();                                        // 6-year candidate
        assertThat(SearchService.lowPriorityReason(job("Senior Software Engineer", null), traits(null), WINDOW, SKILLS))
                .isNull();                                                                            // "Senior" alone is not used
    }

    @Test
    void embeddedWorkWithoutCOrCppGoesToTheLowPriorityTier() {
        assertThat(SearchService.lowPriorityReason(job("Engineer - Camera Kernel", 2), traits("EMBEDDED"), WINDOW, SKILLS))
                .isEqualTo("embedded work, no C or C++");
        Set<String> withCpp = Set.of("Java", "C++");
        assertThat(SearchService.lowPriorityReason(job("Engineer - Camera Kernel", 2), traits("EMBEDDED"), WINDOW, withCpp))
                .isNull();
    }

    @Test
    void mainLanguagesTheCandidateLacksGoToTheLowPriorityTier() {
        assertThat(SearchService.lowPriorityReason(job("Software Engineer", 2), traits("BACKEND", "Go", "Rust"), WINDOW, SKILLS))
                .isEqualTo("main languages not the candidate's");
        assertThat(SearchService.lowPriorityReason(job("Software Engineer", 2), traits("BACKEND", "Go", "Java"), WINDOW, SKILLS))
                .isNull();                                                                            // one shared language is enough
        assertThat(SearchService.lowPriorityReason(job("Software Engineer", 2), traits("BACKEND"), WINDOW, SKILLS)).isNull();
    }

    @Test
    void seniorityWordsAndTheirYears() {
        assertThat(SearchService.seniorityEstimate("Director of Engineering")).isEqualTo(12);
        assertThat(SearchService.seniorityEstimate("Staff Engineer")).isEqualTo(8);
        assertThat(SearchService.seniorityEstimate("Team Lead - Backend")).isEqualTo(5);
        assertThat(SearchService.seniorityEstimate("Software Engineer II")).isNull();
        assertThat(SearchService.seniorityEstimate("Leadership Program Analyst")).isNull();
    }
}
