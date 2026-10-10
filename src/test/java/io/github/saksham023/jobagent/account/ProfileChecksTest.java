package io.github.saksham023.jobagent.account;

import io.github.saksham023.jobagent.requirements.JobFamily;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileChecksTest {

    private static final SkillExtractor DICTIONARY = new SkillExtractor();

    @Test
    void knownNamesAreWrittenTheDictionaryWayAndOnlyLanguagesCountAsLanguages() {
        UserProfile.Facts facts = ProfileChecks.clean(new UserProfile.Facts(" A Backend  engineer. ", "Building APIs in Java.", 1.64, List.of("java", "Spring Boot", "python"),
                List.of("springboot", "Kafka", "kafka", "  Postgres ", "Some New Tool"), "  Backend   roles ",
                List.of(JobFamily.SOFTWARE_ENGINEERING, JobFamily.UNCLASSIFIED, JobFamily.DATA_ML)), DICTIONARY);

        assertThat(facts.headline()).isEqualTo("Backend engineer");            // the message adds its own "a" / "an"
        assertThat(facts.build()).isEqualTo("building APIs in Java");
        assertThat(facts.years()).isEqualTo(1.6);
        assertThat(facts.mainLanguages()).containsExactly("Java", "Python");                 // Spring Boot is not a language
        assertThat(facts.skills()).contains("Spring Boot", "Kafka", "Some New Tool").doesNotHaveDuplicates();
        assertThat(facts.skills().stream().filter(s -> s.equalsIgnoreCase("kafka"))).hasSize(1);
        assertThat(facts.rolesWanted()).isEqualTo("Backend roles");
        assertThat(facts.families()).containsExactly(JobFamily.SOFTWARE_ENGINEERING, JobFamily.DATA_ML);
    }

    @Test
    void outOfRangeOrEmptyValuesAreDropped() {
        UserProfile.Facts facts = ProfileChecks.clean(new UserProfile.Facts("  ", "  ", 80.0, null, List.of("x".repeat(60)), "  ", null), DICTIONARY);
        assertThat(facts.headline()).isNull();
        assertThat(facts.build()).isNull();
        assertThat(facts.years()).isNull();
        assertThat(facts.mainLanguages()).isEmpty();
        assertThat(facts.skills()).isEmpty();
        assertThat(facts.rolesWanted()).isNull();
        assertThat(facts.families()).isEmpty();
        assertThat(ProfileChecks.clean(new UserProfile.Facts(null, null, -1.0, null, null, null, null), DICTIONARY).years()).isNull();
    }

    @Test
    void headlineKeepsItsWordsButNotAnArticleOrEndPunctuation() {
        assertThat(ProfileChecks.headline("an ML engineer")).isEqualTo("ML engineer");
        assertThat(ProfileChecks.headline("Analytics engineer")).isEqualTo("Analytics engineer");
        assertThat(ProfileChecks.headline("x".repeat(61))).isNull();
    }

    @Test
    void buildMustStartWithAnIngVerb() {
        assertThat(ProfileChecks.build("building ML pipelines")).isEqualTo("building ML pipelines");
        assertThat(ProfileChecks.build("in Java and Spring Boot")).isNull();
        assertThat(ProfileChecks.build("APIs in Java")).isNull();
        assertThat(ProfileChecks.build("building " + "x".repeat(120))).isNull();
        assertThat(ProfileChecks.build("building a b c d e f g h i j k l m n o")).isNull();              // 16 words
    }
}
