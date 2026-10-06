package io.github.saksham023.jobagent.eval;

import io.github.saksham023.jobagent.eval.Judgment.Verdict;
import io.github.saksham023.jobagent.job.JobQueryRepository.JobDetails;
import org.junit.jupiter.api.Test;
import io.github.saksham023.jobagent.llm.ClaudeCliChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The judge with a stand-in ChatModel (a lambda): what it sends and how it reads the answer.
 */
class JobJudgeTest {

    private static final Rubric RUBRIC = new Rubric("v2", "You are screening job postings.");
    private static final JudgeProfile PROFILE = new JudgeProfile("dev", "a test profile", 1.6, List.of("Java"),
            List.of("Spring Boot", "Kafka"), "Backend roles.");

    private static JobDetails job(String description) {
        return new JobDetails(7, "Acme", "Backend Engineer", "https://example.com/7", "Engineering",
                List.of("Bengaluru"), List.of("Bengaluru"), true, null, null, null, true, 3, 5, "3-5 years",
                "SOFTWARE_ENGINEERING", List.of(), null, List.of(), List.of(), List.of(), List.of(), description);
    }

    private static ChatModel answering(String json, AtomicReference<Prompt> sent) {
        return prompt -> {
            sent.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        };
    }

    @Test
    void sendsRubricCandidateJobAndSchemaAndReadsTheVerdict() {
        AtomicReference<Prompt> sent = new AtomicReference<>();
        JobJudge judge = new JobJudge(answering("""
                {"verdict":"APPLY","roleFit":"FIT","experienceFit":"FIT","stackFit":"GOOD","reason":"Java backend, 3-5 years."}""", sent));

        Judgment judgment = judge.judge(RUBRIC, PROFILE, job("Build Java services with Kafka."), "haiku").judgment();

        assertThat(judgment.verdict()).isEqualTo(Verdict.APPLY);
        assertThat(judgment.reason()).isEqualTo("Java backend, 3-5 years.");
        Prompt prompt = sent.get();
        assertThat(prompt.getSystemMessage().getText()).isEqualTo("You are screening job postings.");
        assertThat(prompt.getUserMessage().getText()).contains("Years of professional experience: 1.6",
                "Main programming languages: Java", "Title: Backend Engineer", "Location: Bengaluru (remote possible)",
                "3-5 years", "Build Java services with Kafka.");
        assertThat(prompt.getUserMessage().getText()).doesNotContain("SOFTWARE_ENGINEERING");   // no hint from our classifier
        assertThat(prompt.getOptions().getModel()).isEqualTo("haiku");
        assertThat(((StructuredOutputChatOptions) prompt.getOptions()).getOutputSchema()).contains("verdict", "APPLY", "MAYBE");
    }

    @Test
    void longPostingsAreCut() {
        String message = JobJudge.userMessage(PROFILE, job("x".repeat(20_000)));

        assertThat(message).contains("[posting cut here]");
        assertThat(message.length()).isLessThan(JobJudge.MAX_DESCRIPTION_CHARS + 1_000);
    }

    @Test
    void anAnswerWithoutAVerdictIsAnError() {
        JobJudge judge = new JobJudge(answering("{\"reason\":\"not sure\"}", new AtomicReference<>()));

        assertThatThrownBy(() -> judge.judge(RUBRIC, PROFILE, job("text"), "haiku"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportsTokensAndCostFromTheResponseMetadata() {
        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"verdict":"NO","roleFit":"MISMATCH","experienceFit":"MISMATCH","stackFit":"STRETCH","reason":"x"}"""))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(7000, 150)).keyValue(ClaudeCliChatModel.COST_USD, 0.05).build());

        JobJudge.Result result = new JobJudge(model).judge(RUBRIC, PROFILE, job("text"), "opus");

        assertThat(result.promptTokens()).isEqualTo(7000);
        assertThat(result.completionTokens()).isEqualTo(150);
        assertThat(result.costUsd()).isEqualTo(0.05);
    }

    @Test
    void aModelThatReportsNoUsageCountsAsZero() {
        JobJudge.Result result = new JobJudge(answering("""
                {"verdict":"NO","roleFit":"MISMATCH","experienceFit":"MISMATCH","stackFit":"STRETCH","reason":"x"}""",
                new AtomicReference<>())).judge(RUBRIC, PROFILE, job("text"), "local");

        assertThat(result.promptTokens()).isZero();
        assertThat(result.costUsd()).isZero();
    }

    @Test
    void unknownYearsAreSaidPlainly() {
        JudgeProfile unknown = new JudgeProfile("dev", null, null, List.of("Java"), List.of(), "Backend roles.");

        assertThat(JobJudge.userMessage(unknown, job("Build things."))).contains("Years of professional experience: not stated");
    }
}
