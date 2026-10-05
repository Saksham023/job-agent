package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.GapFiller.Fill;
import io.github.saksham023.jobagent.requirements.GapFiller.Gaps;
import io.github.saksham023.jobagent.requirements.GapFiller.Result;
import io.github.saksham023.jobagent.requirements.JobClassifier.Specialization;
import io.github.saksham023.jobagent.requirements.RequirementsRepository.JobText;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Category;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gap filler with a stand-in ChatModel: what it sends, which answers pass the checks, and the prompt file.
 */
class GapFillerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SkillExtractor SKILLS = new SkillExtractor();
    private static final Gaps ALL_GAPS = new Gaps(true, true, true);

    private static final JobText JOB = new JobText(7, "Software Engineer II", "R&D", null, """
            About the role
            You will build payment services.
            Requirements
            • 3 - 5 years of experience building backend systems
            • Strong programming skills in Java or Kotlin
            """, null, "Acme");

    private static GapFiller filler(String json, AtomicReference<Prompt> sent) {
        ChatModel model = prompt -> {
            sent.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        };
        return new GapFiller(model, SKILLS, JSON);
    }

    private static GapAnswer answer(Integer min, Integer max, String evidence, JobFamily family, List<String> languages) {
        return new GapAnswer(min != null, min, max, evidence, family, Specialization.BACKEND, "Builds services.",
                languages, "Java or Kotlin");
    }

    private static Result check(GapAnswer answer, Gaps gaps) {
        return new GapFiller(prompt -> null, SKILLS, JSON).check(answer, JOB, gaps, 0);
    }

    @Test
    void oneCallWithThePromptThePostingAndTheSchema() {
        AtomicReference<Prompt> sent = new AtomicReference<>();
        Result result = filler("""
                {"yearsStated":true,"minYears":3,"maxYears":5,"yearsEvidence":"3 - 5 years of experience",
                 "family":"SOFTWARE_ENGINEERING","specialization":null,"familyReason":"Backend services.",
                 "mainLanguages":["Java","Kotlin"],"languagesEvidence":"Java or Kotlin"}""", sent)
                .fill(new GapFillPrompt("v1", "Extract three facts."), JOB, ALL_GAPS, "opus");

        Fill fill = result.accepted();
        assertThat(fill.minYears()).isEqualTo(3);
        assertThat(fill.maxYears()).isEqualTo(5);
        assertThat(fill.family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(fill.specialization()).isNull();
        assertThat(fill.languages()).containsExactly("Java", "Kotlin");
        assertThat(result.rejected()).isEmpty();

        Prompt prompt = sent.get();
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("Extract three facts.");
        assertThat(prompt.getUserMessage().getText()).contains("Company: Acme", "Title: Software Engineer II",
                "Department: R&D", "3 - 5 years of experience");
        assertThat(prompt.getOptions().getModel()).isEqualTo("opus");
        assertThat(((StructuredOutputChatOptions) prompt.getOptions()).getOutputSchema()).contains("SALES_ENGINEERING");
    }

    @Test
    void optionalFieldsMayBeNullInTheSchema() {
        JsonNode properties = JSON.readTree(new GapFiller(p -> null, SKILLS, JSON).schema()).get("properties");
        assertThat(properties.get("minYears").get("type").toString()).contains("integer", "null");
        assertThat(properties.get("specialization").get("enum").toString()).contains("BACKEND", "null");
        assertThat(properties.get("family").get("type").asString()).isEqualTo("string");    // family is required
    }

    @Test
    void onlyTheGapFieldsAreUsed() {
        Fill fill = check(answer(3, 5, "3 - 5 years", JobFamily.SOFTWARE_ENGINEERING, List.of("Java")),
                new Gaps(true, false, false)).accepted();
        assertThat(fill.hasYears()).isTrue();
        assertThat(fill.hasFamily()).isFalse();
        assertThat(fill.hasLanguages()).isFalse();
    }

    @Test
    void yearsEvidenceMustBeCopiedFromThePosting() {
        Result invented = check(answer(4, null, "4+ years of Java", null, List.of()), ALL_GAPS);
        assertThat(invented.accepted().hasYears()).isFalse();
        assertThat(invented.rejected()).singleElement().asString().startsWith("years: evidence not in the posting");

        // the copy may differ in bullets, spaces and case
        assertThat(check(answer(3, 5, "3 - 5 YEARS of experience  building", null, List.of()), ALL_GAPS)
                .accepted().minYears()).isEqualTo(3);
    }

    @Test
    void yearsEvidenceMustStateTheMinimum() {
        Result result = check(answer(2, 5, "3 - 5 years", null, List.of()), ALL_GAPS);
        assertThat(result.accepted().hasYears()).isFalse();
        assertThat(result.rejected()).singleElement().asString().startsWith("years: evidence does not state 2");
    }

    @Test
    void yearsMustBeASaneRange() {
        assertThat(check(answer(5, 3, "3 - 5 years", null, List.of()), ALL_GAPS).rejected())
                .singleElement().asString().startsWith("years: out of range");
        assertThat(check(answer(45, null, "3 - 5 years", null, List.of()), ALL_GAPS).accepted().hasYears()).isFalse();
    }

    @Test
    void notStatedIsNoFillAndNoRejection() {
        Result result = check(new GapAnswer(false, null, null, null, JobFamily.UNCLASSIFIED, null, "Too short.",
                List.of(), null), ALL_GAPS);
        assertThat(result.accepted().hasYears()).isFalse();
        assertThat(result.accepted().hasFamily()).isFalse();       // UNCLASSIFIED is no answer
        assertThat(result.accepted().hasLanguages()).isFalse();
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    void languagesMustBeKnownAndInThePosting() {
        Result result = check(answer(null, null, null, null, List.of("java", "Python", "Bash")), ALL_GAPS);
        assertThat(result.accepted().languages()).containsExactly("Java");          // canonical name
        assertThat(result.rejected()).containsExactly(
                "languages: Python is not in the posting",
                "languages: 'Bash' is not a language we know");
    }

    @Test
    void normalizeKeepsLettersDigitsPlusAndHash() {
        assertThat(GapFiller.normalize("• 3–5 Years in C++/C#!")).isEqualTo("3 5 years in c++ c#");
    }

    // ---------------------------------------------------------------- the prompt file

    @Test
    void promptNamesEveryFamilySpecializationAndLanguage() {
        GapFillPrompt prompt = GapFillPrompt.load();
        assertThat(prompt.version()).isEqualTo("v2");
        assertThat(prompt.instructions()).doesNotContain("GapFillPromptTest");      // the header is not sent
        for (JobFamily family : JobFamily.values()) {
            assertThat(prompt.instructions()).contains("- " + family.name() + ":");
        }
        assertThat(prompt.instructions()).contains(Arrays.stream(Specialization.values()).map(Enum::name).toList());
        List<String> languages = SKILLS.canonicalNames(Category.LANGUAGE);
        assertThat(languages).contains("Java", "Go");
        assertThat(prompt.instructions()).contains(languages);
    }
}
