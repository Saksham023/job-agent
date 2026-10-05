package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.llm.ClaudeCliChatModel;
import io.github.saksham023.jobagent.requirements.JobClassifier.Specialization;
import io.github.saksham023.jobagent.requirements.RequirementsRepository.JobText;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Category;
import io.github.saksham023.jobagent.requirements.SkillExtractor.SkillName;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Skills;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Asks a model (Claude Opus today) for the facts the rules could not extract from one job, in ONE call, and keeps
 * only the answers that pass every check:
 * - experience: 0 to 30 years, min &lt;= max, the evidence is copied from the posting and states the minimum;
 * - family: one of our JobFamily values (the JSON schema enforces it), not UNCLASSIFIED;
 * - languages: names from our skill dictionary (category LANGUAGE) that the posting really mentions.
 * Only the gap fields are checked and used; the rest of the answer is kept for later comparisons, never applied.
 */
@Component
public class GapFiller {

    /** The same cut as get_job and the judge. */
    static final int MAX_DESCRIPTION_CHARS = 12_000;
    static final int MAX_YEARS = 30;
    static final int MAX_LANGUAGES = 3;

    /** Fields the model may leave null; the generated schema would otherwise demand a value for each. */
    private static final List<String> NULLABLE = List.of("minYears", "maxYears", "yearsEvidence", "specialization",
            "languagesEvidence");

    private static final Map<Integer, String> NUMBER_WORDS = Map.ofEntries(Map.entry(1, "one"), Map.entry(2, "two"),
            Map.entry(3, "three"), Map.entry(4, "four"), Map.entry(5, "five"), Map.entry(6, "six"),
            Map.entry(7, "seven"), Map.entry(8, "eight"), Map.entry(9, "nine"), Map.entry(10, "ten"),
            Map.entry(11, "eleven"), Map.entry(12, "twelve"), Map.entry(15, "fifteen"));

    /** What the rules left empty for a job: experience NONE / LOW, family UNCLASSIFIED, no main language. */
    public record Gaps(boolean years, boolean family, boolean languages) {

        public boolean any() {
            return years || family || languages;
        }
    }

    /** The checked values; a null (or empty list) field is not filled. Stored as job_gap_fills.accepted. */
    public record Fill(Integer minYears, Integer maxYears, String yearsEvidence, JobFamily family,
                       Specialization specialization, String familyReason, List<String> languages) {

        boolean hasYears() {
            return minYears != null;
        }

        boolean hasFamily() {
            return family != null;
        }

        boolean hasLanguages() {
            return languages != null && !languages.isEmpty();
        }
    }

    /** @param rejected one line per gap answer that failed a check, e.g. "years: evidence not in the posting" */
    public record Result(GapAnswer answer, Fill accepted, List<String> rejected, double costUsd) {
    }

    private final ChatModel chatModel;
    private final SkillExtractor skillExtractor;
    private final BeanOutputConverter<GapAnswer> converter = new BeanOutputConverter<>(GapAnswer.class);
    private final String schema;

    public GapFiller(ChatModel chatModel, SkillExtractor skillExtractor, JsonMapper jsonMapper) {
        this.chatModel = chatModel;
        this.skillExtractor = skillExtractor;
        this.schema = nullable(converter.getJsonSchema(), jsonMapper);
    }

    /** @param model the model name the ChatModel understands ("opus") */
    public Result fill(GapFillPrompt prompt, JobText job, Gaps gaps, String model) {
        Prompt request = new Prompt(
                List.of(new SystemMessage(prompt.instructions()), new UserMessage(userMessage(job))),
                StructuredOutputChatOptions.builder().model(model).outputSchema(schema).build());
        ChatResponse response = chatModel.call(request);
        String text = response.getResult().getOutput().getText();
        GapAnswer answer = converter.convert(text);
        if (answer == null) {
            throw new IllegalStateException("The model gave no answer: " + text);
        }
        Double cost = response.getMetadata().get(ClaudeCliChatModel.COST_USD);
        return check(answer, job, gaps, cost == null ? 0 : cost);
    }

    String schema() {
        return schema;
    }

    // ---------------------------------------------------------------- checks

    Result check(GapAnswer answer, JobText job, Gaps gaps, double costUsd) {
        String posting = normalize(job.title() + " " + nullToEmpty(job.description()));
        List<String> rejected = new ArrayList<>();

        Integer minYears = null, maxYears = null;
        String yearsEvidence = null;
        if (gaps.years() && answer.yearsStated() && answer.minYears() != null) {
            String problem = yearsProblem(answer, posting);
            if (problem == null) {
                minYears = answer.minYears();
                maxYears = answer.maxYears();
                yearsEvidence = answer.yearsEvidence().strip();
            } else {
                rejected.add("years: " + problem);
            }
        }

        JobFamily family = null;
        Specialization specialization = null;
        String familyReason = null;
        if (gaps.family() && answer.family() != null && answer.family() != JobFamily.UNCLASSIFIED) {
            family = answer.family();
            specialization = answer.specialization();
            familyReason = answer.familyReason();
        }

        List<String> languages = List.of();
        if (gaps.languages() && answer.mainLanguages() != null && !answer.mainLanguages().isEmpty()) {
            languages = languages(answer.mainLanguages(), job, rejected);
        }

        Fill fill = new Fill(minYears, maxYears, yearsEvidence, family, specialization, familyReason, languages);
        return new Result(answer, fill, List.copyOf(rejected), costUsd);
    }

    /** Null when the years answer is usable, else why not. */
    private static String yearsProblem(GapAnswer answer, String posting) {
        int min = answer.minYears();
        Integer max = answer.maxYears();
        if (min < 0 || min > MAX_YEARS || (max != null && (max < min || max > MAX_YEARS))) {
            return "out of range (" + min + "-" + max + ")";
        }
        String evidence = normalize(nullToEmpty(answer.yearsEvidence()));
        if (evidence.isEmpty()) {
            return "no evidence";
        }
        if (!posting.contains(evidence)) {
            return "evidence not in the posting: " + answer.yearsEvidence();
        }
        if (!statesNumber(evidence, min)) {
            return "evidence does not state " + min + ": " + answer.yearsEvidence();
        }
        return null;
    }

    private static boolean statesNumber(String normalizedEvidence, int number) {
        String word = NUMBER_WORDS.get(number);
        return Pattern.compile("(?<![\\p{N}])" + number + "(?![\\p{N}])").matcher(normalizedEvidence).find()
                || (word != null && Pattern.compile("\\b" + word + "\\b").matcher(normalizedEvidence).find());
    }

    /** Canonical language names that the posting mentions; the others are reported as rejected. */
    private List<String> languages(List<String> answered, JobText job, List<String> rejected) {
        Skills mentioned = skillExtractor.extract(job.title(), job.description(), job.companyName());
        String text = job.title() + " " + nullToEmpty(job.description());
        Set<String> accepted = new LinkedHashSet<>();
        for (String name : answered) {
            Optional<SkillName> skill = skillExtractor.canonical(name);
            if (skill.isEmpty() || skill.get().category() != Category.LANGUAGE) {
                rejected.add("languages: '" + name + "' is not a language we know");
            } else if (!mentioned.required().contains(skill.get().skill())
                    && !mentioned.preferred().contains(skill.get().skill())
                    && !writtenAsWord(skill.get().skill(), text)) {
                rejected.add("languages: " + skill.get().skill() + " is not in the posting");
            } else if (accepted.size() < MAX_LANGUAGES) {
                accepted.add(skill.get().skill());
            }
        }
        return List.copyOf(accepted);
    }

    /** "Go" and "C" only as exactly that word (case-sensitive), longer names in any case. */
    private static boolean writtenAsWord(String name, String text) {
        int flags = name.length() <= 2 ? 0 : Pattern.CASE_INSENSITIVE;
        return Pattern.compile("(?<![\\w+#])" + Pattern.quote(name) + "(?![\\w+#])", flags).matcher(text).find();
    }

    /** Lower case, every run of punctuation and spaces as one space: copied text survives bullets and line breaks. */
    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+#]+", " ").strip();
    }

    // ---------------------------------------------------------------- prompt pieces

    static String userMessage(JobText job) {
        String description = job.description() == null ? "(no description)" : job.description();
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS) + "\n[posting cut here]";
        }
        return """
                Company: %s
                Title: %s
                Department: %s

                Posting:
                %s
                """.formatted(job.companyName(), job.title(),
                job.department() == null || job.department().isBlank() ? "(not given)" : job.department(), description);
    }

    /** The generated schema with null allowed for the NULLABLE fields (an enum gets null as an allowed value). */
    static String nullable(String schema, JsonMapper jsonMapper) {
        ObjectNode root = (ObjectNode) jsonMapper.readTree(schema);
        ObjectNode properties = (ObjectNode) root.get("properties");
        for (String field : NULLABLE) {
            ObjectNode property = (ObjectNode) properties.get(field);
            String type = property.get("type").asString();
            property.putArray("type").add(type).add("null");
            if (property.get("enum") instanceof ArrayNode values) {
                values.addNull();
            }
        }
        return jsonMapper.writeValueAsString(root);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
