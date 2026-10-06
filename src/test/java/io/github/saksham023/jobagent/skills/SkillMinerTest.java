package io.github.saksham023.jobagent.skills;

import io.github.saksham023.jobagent.skills.SkillMiner.Found;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding candidate skills by the company they keep. */
class SkillMinerTest {

    private static final Set<String> KNOWN = Set.of("jenkins", "gradle", "maven", "git", "sonarqube", "ci/cd", "aws",
            "docker", "kubernetes", "splunk", "kibana", "python", "java", "tcp/ip", "dns");
    private static final Predicate<String> KNOWN_TEST = s -> KNOWN.contains(s.toLowerCase());
    private static final Predicate<String> NOTHING = s -> false;

    private static List<String> terms(String text) {
        return SkillMiner.candidates(text, KNOWN_TEST, NOTHING).stream().map(Found::term).toList();
    }

    @Test
    void unknownItemsInAListOfKnownSkillsAreCandidates() {
        assertThat(terms("- Hands-on experience with CI/CD tools: Jenkins, Gradle, Maven, Git, SonarQube, Artifactory"))
                .containsExactly("Artifactory");
        assertThat(terms("Monitoring tools such as Splunk, Kibana, Dynatrace and New Relic."))
                .containsExactly("Dynatrace", "New Relic");
    }

    @Test
    void aListWithFewerThanTwoKnownSkillsIsIgnored() {
        assertThat(terms("We use Jenkins, Bamboo and TeamCity for builds")).isEmpty();
        assertThat(terms("Our offices are in Pune, Hyderabad and Bengaluru")).isEmpty();
    }

    @Test
    void knownSlashTermsStayWholeOthersAreSplit() {
        assertThat(SkillMiner.items("Jenkins, CI/CD, TCP/IP", KNOWN_TEST)).containsExactly("Jenkins", "CI/CD", "TCP/IP");
        assertThat(terms("Python/Java/Rust, Docker")).containsExactly("Rust");
    }

    @Test
    void genericWordsAndLongFragmentsAreNotCandidates() {
        assertThat(terms("Experience with AWS, Docker, Kubernetes, or similar tools, and strong communication skills overall"))
                .isEmpty();
        assertThat(SkillMiner.plausible("security")).isFalse();
        assertThat(SkillMiner.plausible("one")).isFalse();
        assertThat(SkillMiner.plausible("tool calling")).isTrue();
        assertThat(SkillMiner.plausible("API Gateway")).isTrue();
        assertThat(SkillMiner.plausible("AWS Lambda")).isTrue();
        assertThat(SkillMiner.plausible("CI/CD tools")).isFalse();
        assertThat(SkillMiner.plausible("a very long phrase that is clearly not a name")).isFalse();
    }

    @Test
    void excludedNamesAreSkipped() {
        assertThat(SkillMiner.candidates("Built with Java, Python and Paytm Wallet", KNOWN_TEST,
                s -> s.equalsIgnoreCase("Paytm Wallet"))).isEmpty();
    }

    @Test
    void theTallyCountsJobsAndCompaniesAndKeepsTheMostCommonSpelling() {
        SkillMiner.Tally tally = new SkillMiner.Tally();
        tally.add(1, 10, List.of(new Found("Dynatrace", "a")));
        tally.add(2, 10, List.of(new Found("Dynatrace", "b")));
        tally.add(3, 20, List.of(new Found("dynatrace", "c")));
        SkillMiner.Candidate c = tally.candidates(5).getFirst();
        assertThat(c.key()).isEqualTo("dynatrace");
        assertThat(c.term()).isEqualTo("Dynatrace");
        assertThat(c.jobs()).isEqualTo(3);
        assertThat(c.companies()).isEqualTo(2);
        assertThat(c.contexts()).containsExactly("a", "c");
    }

    @Test
    void theReextractionFilterMatchesWholeWordsOnly() {
        assertThat(SkillLearningRepository.wordPattern("AI")).isEqualTo("(^|[^[:alnum:]])AI([^[:alnum:]]|$)");
        assertThat(SkillLearningRepository.wordPattern(".NET")).isEqualTo("(^|[^[:alnum:]])\\.NET([^[:alnum:]]|$)");
        assertThat(SkillLearningRepository.wordPattern("C++")).isEqualTo("(^|[^[:alnum:]])C\\+\\+([^[:alnum:]]|$)");
    }
}
