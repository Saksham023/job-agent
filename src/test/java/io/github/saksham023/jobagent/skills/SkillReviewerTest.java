package io.github.saksham023.jobagent.skills;

import io.github.saksham023.jobagent.requirements.SkillExtractor;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Category;
import io.github.saksham023.jobagent.requirements.SkillExtractor.LearnedSkill;
import io.github.saksham023.jobagent.skills.SkillMiner.Candidate;
import io.github.saksham023.jobagent.skills.SkillReviewer.Outcome;
import io.github.saksham023.jobagent.skills.SkillReviewer.TermDecision;
import io.github.saksham023.jobagent.skills.SkillReviewer.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The checks every model answer must pass, and learned skills in the dictionary. */
class SkillReviewerTest {

    private final SkillExtractor dictionary = new SkillExtractor();
    private final SkillReviewer reviewer = new SkillReviewer(prompt -> null, dictionary);

    private static Candidate candidate(String term, String... sentences) {
        return new Candidate(SkillMiner.key(term), term, 5, 4, List.of(sentences));
    }

    private static TermDecision decision(Verdict verdict, String canonical, String category, List<String> spellings) {
        return new TermDecision("x", verdict, canonical, category, spellings, false, "because");
    }

    @Test
    void aNewSkillKeepsOnlySpellingsFoundInTheSentences() {
        Outcome outcome = reviewer.check(candidate("Dynatrace", "Tools such as Splunk, Kibana, Dynatrace"),
                decision(Verdict.NEW_SKILL, "Dynatrace", "devops", List.of("Dynatrace", "Dyna Trace")));
        assertThat(outcome.status()).isEqualTo("NEW_SKILL");
        assertThat(outcome.category()).isEqualTo(Category.DEVOPS);
        assertThat(outcome.spellings()).containsExactly("Dynatrace");
        assertThat(outcome.reason()).contains("Dyna Trace (not in the sentences)");
    }

    @Test
    void anAliasMustPointToAKnownSkill() {
        Outcome ok = reviewer.check(candidate("Postgres DB", "SQL, Redis, Postgres DB"),
                decision(Verdict.ALIAS, "PostgreSQL", "", List.of()));
        assertThat(ok.status()).isEqualTo("ALIAS");
        assertThat(ok.skill()).isEqualTo("PostgreSQL");
        assertThat(ok.category()).isEqualTo(Category.DATASTORE);

        Outcome unknown = reviewer.check(candidate("Foo", "Java, Python, Foo"), decision(Verdict.ALIAS, "FooBar", "", List.of()));
        assertThat(unknown.status()).isEqualTo("REJECTED");
        assertThat(unknown.reason()).startsWith("alias of a skill we do not have");
    }

    @Test
    void aNewSkillWeAlreadyHaveBecomesAnAlias() {
        Outcome outcome = reviewer.check(candidate("Postgres DB", "SQL, Redis, Postgres DB"),
                decision(Verdict.NEW_SKILL, "PostgreSQL", "DATASTORE", List.of("Postgres DB")));
        assertThat(outcome.status()).isEqualTo("ALIAS");
        assertThat(outcome.skill()).isEqualTo("PostgreSQL");
    }

    @Test
    void badAnswersAreRejectedWithTheReason() {
        assertThat(reviewer.check(candidate("security", "Java, Python, security"),
                new TermDecision("security", Verdict.NOT_A_SKILL, "", "", List.of(), false, "generic word")).reason())
                .isEqualTo("generic word");
        assertThat(reviewer.check(candidate("Zork", "Java, Python, Zork"), decision(Verdict.NEW_SKILL, "Zork", "GAMES", List.of())).reason())
                .startsWith("unknown category");
        // a spelling that already belongs to another skill is not taken over
        Outcome stolen = reviewer.check(candidate("Flink SQL", "Java, Kafka, Flink SQL"),
                decision(Verdict.NEW_SKILL, "Flink SQL", "DATA", List.of("SQL")));
        assertThat(stolen.spellings()).containsExactly("Flink SQL");
        assertThat(stolen.reason()).contains("SQL (belongs to SQL)");
    }

    @Test
    void learnedSpellingsWorkInTheDictionaryAndCanBeRemoved() {
        SkillExtractor fresh = new SkillExtractor();
        assertThat(fresh.canonical("dynatrace")).isEmpty();
        fresh.useLearned(List.of(new LearnedSkill("Dynatrace", Category.DEVOPS, "Dynatrace", false),
                new LearnedSkill("Java", Category.LANGUAGE, "Javva", false),           // a new spelling of a CSV skill
                new LearnedSkill("NotJava", Category.TOOL, "java", false)));             // may not take over a CSV spelling
        assertThat(fresh.canonical("DYNATRACE")).hasValueSatisfying(s -> assertThat(s.skill()).isEqualTo("Dynatrace"));
        assertThat(fresh.canonical("javva")).hasValueSatisfying(s -> assertThat(s.skill()).isEqualTo("Java"));
        assertThat(fresh.canonical("java")).hasValueSatisfying(s -> assertThat(s.skill()).isEqualTo("Java"));
        assertThat(fresh.extract("SRE", "Requirements\n- Experience with Splunk and Dynatrace.", null).required())
                .contains("Dynatrace");
        fresh.useLearned(List.of());
        assertThat(fresh.canonical("dynatrace")).isEmpty();
    }

    @Test
    void theUserMessageListsKnownSkillsAndEachTermWithItsSentences() {
        String message = reviewer.userMessage(List.of(candidate("Dynatrace", "Splunk, Kibana, Dynatrace")));
        assertThat(message).startsWith("Skills we already know: Java, Python")
                .contains("1. \"Dynatrace\" (in skill lists at 4 companies)", "   - Splunk, Kibana, Dynatrace");
    }
}
