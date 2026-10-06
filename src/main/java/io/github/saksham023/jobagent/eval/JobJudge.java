package io.github.saksham023.jobagent.eval;

import io.github.saksham023.jobagent.job.JobQueryRepository.JobDetails;
import io.github.saksham023.jobagent.llm.ClaudeCliChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Asks a model whether a candidate should apply to a job. The rubric is the system message, the candidate and the
 * posting are the user message, and the Judgment record's JSON schema is the required answer format. Depends only
 * on the ChatModel interface: the same judge runs on Claude via the CLI today and on any other model later.
 */
@Component
public class JobJudge {

    /** The same cut as get_job: long postings are mostly boilerplate after this. */
    static final int MAX_DESCRIPTION_CHARS = 12_000;

    /**
     * A judgment plus what it cost: tokens as the model reports them (0 when a model reports none) and the
     * API-equivalent price when the model gives one (ClaudeCliChatModel does).
     */
    public record Result(Judgment judgment, int promptTokens, int completionTokens, double costUsd) {
    }

    private final ChatModel chatModel;
    private final BeanOutputConverter<Judgment> converter = new BeanOutputConverter<>(Judgment.class);

    public JobJudge(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** @param model the model name the ChatModel understands ("opus", "haiku", an Ollama model...) */
    public Result judge(Rubric rubric, JudgeProfile profile, JobDetails job, String model) {
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(rubric.instructions()), new UserMessage(userMessage(profile, job))),
                StructuredOutputChatOptions.builder().model(model).outputSchema(converter.getJsonSchema()).build());
        ChatResponse response = chatModel.call(prompt);
        String answer = response.getResult().getOutput().getText();
        Judgment judgment = converter.convert(answer);
        if (judgment == null || judgment.verdict() == null) {
            throw new IllegalStateException("The model answered without a verdict: " + answer);
        }
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        Double cost = metadata.get(ClaudeCliChatModel.COST_USD);
        return new Result(judgment, orZero(usage.getPromptTokens()), orZero(usage.getCompletionTokens()),
                cost == null ? 0 : cost);
    }

    private static int orZero(Integer tokens) {
        return tokens == null ? 0 : tokens;
    }

    /** The candidate and the job, as plain labeled text. Our own classification is left out on purpose. */
    static String userMessage(JudgeProfile profile, JobDetails job) {
        String description = job.description() == null ? "(no description)" : job.description();
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS) + "\n[posting cut here]";
        }
        String location = (job.cities().isEmpty() ? "India" : String.join(", ", job.cities()))
                + (job.remote() ? " (remote possible)" : "");
        String candidateYears = profile.years() == null ? "not stated" : String.valueOf(profile.years());
        String years = job.minYears() == null ? "not stated"
                : job.maxYears() == null ? job.minYears() + "+ years" : job.minYears() + "-" + job.maxYears() + " years";
        return """
                CANDIDATE
                Years of professional experience: %s
                Main programming languages: %s
                Other skills: %s
                Wants: %s

                JOB
                Company: %s
                Title: %s
                Location: %s
                Experience asked (extracted automatically, may be missing or wrong): %s

                Posting:
                %s
                """.formatted(candidateYears, String.join(", ", profile.mainLanguages()),
                String.join(", ", profile.otherSkills()), profile.wants(), job.company(), job.title(), location,
                years, description);
    }
}