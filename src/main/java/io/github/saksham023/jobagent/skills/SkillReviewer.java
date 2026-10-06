package io.github.saksham023.jobagent.skills;

import io.github.saksham023.jobagent.llm.ClaudeCliChatModel;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Category;
import io.github.saksham023.jobagent.requirements.SkillExtractor.SkillName;
import io.github.saksham023.jobagent.skills.SkillMiner.Candidate;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Asks a model (Opus) what a batch of candidate terms are, then CHECKS every answer before it may change the
 * dictionary: an alias must point to a skill we have; a new skill needs one of our categories and a sane name; every
 * spelling must appear in the sentences the model saw and must not already belong to another skill. Answers that fail
 * a check are rejected with the reason, so nothing unverified reaches the dictionary.
 */
@Component
public class SkillReviewer {

    public enum Verdict { NOT_A_SKILL, ALIAS, NEW_SKILL }

    /** The model's answer for one term. All fields are always present (empty when they do not apply). */
    public record TermDecision(String term, Verdict verdict, String canonical, String category, List<String> spellings,
                               boolean ambiguous, String reason) {
    }

    /** The model's answer for one batch. */
    public record ReviewAnswer(List<TermDecision> decisions) {
    }

    /** What is stored for a term after the checks. status: NEW_SKILL, ALIAS or REJECTED. */
    public record Outcome(String status, String skill, Category category, List<String> spellings, boolean ambiguous,
                          String reason) {

        static Outcome rejected(String reason) {
            return new Outcome("REJECTED", null, null, List.of(), false, reason);
        }
    }

    /** One batch: an outcome per candidate key the model answered, and the cost. */
    public record BatchResult(Map<String, Outcome> outcomes, double costUsd) {
    }

    static final int MAX_NAME_LENGTH = 40;

    static final String INSTRUCTIONS = """
            You maintain the skill dictionary of a search engine for tech jobs in India. The engine matches the skills
            a job asks for against the skills on a candidate's resume, so the dictionary must hold every technical
            skill employers ask for, and nothing else.

            You get terms found in job postings (unknown items in lists that also name skills we know), each with real
            sentences it appeared in, and the list of skills we already know. Decide for EACH term:
            - NOT_A_SKILL: not something a job can require as a technical skill: generic words ("security",
              "reporting"), fragments of other names, soft skills, business domains ("payments"), job titles, degrees,
              teams, or a product only the hiring company itself sells.
            - ALIAS: another name, abbreviation or spelling of a skill we ALREADY know. canonical = that skill's exact
              name from our list.
            - NEW_SKILL: a real technology, tool, language, framework, platform, cloud service, protocol, data product
              or technical method that we do not have. canonical = its usual official name ("AWS Lambda", "New
              Relic", "Apache Airflow"). Business software a job explicitly requires (SAP, SharePoint, Alteryx) counts,
              category TOOL.

            Categories (use exactly one for NEW_SKILL): LANGUAGE (programming languages only), FRAMEWORK, FRONTEND,
            MOBILE, DATASTORE (databases, caches, search engines), MESSAGING, CLOUD (cloud platforms and services),
            DEVOPS (CI/CD, containers, infrastructure, observability), DATA (data engineering, BI, warehouses), ML_AI,
            CONCEPT (technical methods: protocols, architectures, practices), TOOL (other software).

            spellings: how postings write the skill, as plain text (include the term itself when it names the skill).
            ambiguous: true when a spelling is also an ordinary English word or too generic alone ("Glue", "Lambda",
            "Athena"), so it should only count next to other skills.
            reason: one short sentence. Decide from the sentences, not from the word alone. Answer for every term.
            For NOT_A_SKILL use an empty canonical, an empty category and no spellings.
            """;

    private final ChatModel chatModel;
    private final SkillExtractor dictionary;
    private final BeanOutputConverter<ReviewAnswer> converter = new BeanOutputConverter<>(ReviewAnswer.class);

    public SkillReviewer(ChatModel chatModel, SkillExtractor dictionary) {
        this.chatModel = chatModel;
        this.dictionary = dictionary;
    }

    /** One model call for a batch of candidates; returns the checked outcome per candidate key it answered. */
    public BatchResult review(List<Candidate> batch, String model) {
        Prompt request = new Prompt(List.of(new SystemMessage(INSTRUCTIONS), new UserMessage(userMessage(batch))),
                StructuredOutputChatOptions.builder().model(model).outputSchema(converter.getJsonSchema()).build());
        ChatResponse response = chatModel.call(request);
        ReviewAnswer answer = converter.convert(response.getResult().getOutput().getText());
        if (answer == null || answer.decisions() == null) {
            throw new IllegalStateException("The model gave no decisions");
        }
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (Candidate candidate : batch) {
            answer.decisions().stream()
                    .filter(d -> d.term() != null && SkillMiner.key(d.term()).equals(candidate.key()))
                    .findFirst()
                    .ifPresent(d -> outcomes.put(candidate.key(), check(candidate, d)));
        }
        Double cost = response.getMetadata().get(ClaudeCliChatModel.COST_USD);
        return new BatchResult(outcomes, cost == null ? 0 : cost);
    }

    String userMessage(List<Candidate> batch) {
        StringBuilder text = new StringBuilder("Skills we already know: ")
                .append(String.join(", ", dictionary.allCanonicalNames())).append("\n\nTerms:\n");
        int n = 1;
        for (Candidate c : batch) {
            text.append(n++).append(". \"").append(c.term()).append("\" (in skill lists at ").append(c.companies())
                    .append(" companies)\n");
            for (String sentence : c.contexts()) {
                text.append("   - ").append(sentence).append('\n');
            }
        }
        return text.toString();
    }

    /** The checks; anything that fails is rejected with the reason. */
    Outcome check(Candidate candidate, TermDecision decision) {
        if (decision.verdict() == null || decision.verdict() == Verdict.NOT_A_SKILL) {
            return Outcome.rejected(blankTo(decision.reason(), "not a skill"));
        }
        String canonical = decision.canonical() == null ? "" : decision.canonical().strip();
        Optional<SkillName> existing = canonical.isEmpty() ? Optional.empty() : dictionary.canonical(canonical);
        if (decision.verdict() == Verdict.ALIAS && existing.isEmpty()) {
            return Outcome.rejected("alias of a skill we do not have: '" + canonical + "'");
        }
        String skill;
        Category category;
        String status;
        if (existing.isPresent()) {                                 // an alias, or a "new" skill we already have
            skill = existing.get().skill();
            category = existing.get().category();
            status = "ALIAS";
        } else {
            if (canonical.isEmpty() || canonical.length() > MAX_NAME_LENGTH) {
                return Outcome.rejected("no usable skill name: '" + canonical + "'");
            }
            Optional<Category> parsed = category(decision.category());
            if (parsed.isEmpty()) {
                return Outcome.rejected("unknown category: '" + decision.category() + "'");
            }
            skill = canonical;
            category = parsed.get();
            status = "NEW_SKILL";
        }
        Set<String> wanted = new LinkedHashSet<>();
        wanted.add(candidate.term());
        if (decision.spellings() != null) {
            decision.spellings().stream().filter(s -> s != null && !s.isBlank()).map(String::strip).forEach(wanted::add);
        }
        List<String> spellings = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (String spelling : wanted) {
            Optional<SkillName> owner = dictionary.canonical(spelling);
            if (owner.isPresent() && !owner.get().skill().equals(skill)) {
                dropped.add(spelling + " (belongs to " + owner.get().skill() + ")");
            } else if (owner.isPresent()) {
                // already a spelling of this skill: nothing to add
            } else if (!appears(spelling, candidate.contexts()) || spelling.length() > MAX_NAME_LENGTH) {
                dropped.add(spelling + " (not in the sentences)");
            } else if (spellings.stream().noneMatch(s -> s.equalsIgnoreCase(spelling))) {
                spellings.add(spelling);
            }
        }
        if (spellings.isEmpty()) {
            return Outcome.rejected("no new spelling passed the checks" + (dropped.isEmpty() ? "" : ": " + dropped));
        }
        String reason = blankTo(decision.reason(), "") + (dropped.isEmpty() ? "" : " [dropped: " + String.join(", ", dropped) + "]");
        return new Outcome(status, skill, category, List.copyOf(spellings), decision.ambiguous(), reason.strip());
    }

    static boolean appears(String spelling, List<String> contexts) {
        String needle = spelling.toLowerCase(Locale.ROOT);
        return contexts.stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).contains(needle));
    }

    static Optional<Category> category(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String upper = name.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(Category.values()).filter(c -> c.name().equals(upper)).findFirst();
    }

    private static String blankTo(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text.strip();
    }
}
