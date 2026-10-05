package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.matching.SkillImplications.Implied;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real classify/skill-implications.csv: loading it also checks every name against skills.csv.
 */
class SkillImplicationsTest {

    private final SkillImplications implications = new SkillImplications(new SkillExtractor());

    @Test
    void cloudServicesImplyTheCloudWithFullCredit() {
        Map<String, Implied> implied = implications.expand(Set.of("DynamoDB", "AWS SQS"));

        assertThat(implied.get("AWS")).isEqualTo(new Implied("AWS", 1.0, "DynamoDB"));
        assertThat(implied.get("Message Queues").credit()).isEqualTo(1.0);          // via AWS SQS
    }

    @Test
    void relatedExperienceGetsHalfCredit() {
        Map<String, Implied> implied = implications.expand(Set.of("Distributed Systems"));

        assertThat(implied.get("Microservices")).isEqualTo(new Implied("Microservices", 0.5, "Distributed Systems"));
    }

    @Test
    void theHigherCreditWinsWhenTwoSkillsImplyTheSameOne() {
        Map<String, Implied> implied = implications.expand(Set.of("Distributed Systems", "Spring Boot"));

        assertThat(implied.get("Microservices").credit()).isEqualTo(0.5);           // both are 0.5
        assertThat(implications.expand(Set.of("Kinesis", "Kafka")).get("Message Queues").credit()).isEqualTo(1.0);
    }

    @Test
    void listedSkillsAreNotImpliedAgainAndThereIsOnlyOneHop() {
        Map<String, Implied> implied = implications.expand(Set.of("Spring AI", "Java"));

        assertThat(implied).doesNotContainKey("Java");                               // listed directly
        assertThat(implied).containsKey("Spring Boot");                              // Spring AI -> Spring Boot
        assertThat(implied).doesNotContainKey("REST APIs");                          // would need Spring Boot -> REST APIs
    }
}