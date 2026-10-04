package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.common.CsvResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides a job's family (and, for tech families, its specialization) by evidence voting:
 * the title's role words (+3), the platform department or function (+2), and description keywords (+1).
 * Department and keyword evidence may be generic "TECH" (an "Engineering" department, Java/Kafka keywords):
 * it supports the title's family when that family is technical, and counts as SOFTWARE_ENGINEERING otherwise,
 * so an SRE in an "IT" department stays INFRA_DEVOPS instead of tying with software engineering.
 * The family with the most points wins only if it has at least 2 and leads the runner-up by at least 1;
 * otherwise the job is UNCLASSIFIED (reported, never guessed). Rules live in classify/*.csv.
 */
@Component
public class JobClassifier {

    private static final Logger log = LoggerFactory.getLogger(JobClassifier.class);

    static final int TITLE_WEIGHT = 3;
    static final int DEPARTMENT_WEIGHT = 2;
    static final int DESCRIPTION_WEIGHT = 1;
    static final int MIN_SCORE = 2;
    static final int MIN_LEAD = 1;
    static final int MIN_KEYWORD_HITS = 3;

    /**
     * @param reasons the votes, e.g. "title 'sde' -> SOFTWARE_ENGINEERING +3", for debugging and the user
     */
    public record Classification(JobFamily family, Specialization specialization, int score, List<String> reasons) {
    }

    public enum Specialization {
        FULL_STACK, FRONTEND, BACKEND, MOBILE, EMBEDDED, DATA_ENGINEERING, ML_AI, SRE, PLATFORM_INFRA, SECURITY
    }

    private record Rule<T>(T value, Pattern pattern) {}

    /** A family, or null for the generic TECH umbrella. */
    private record KeywordSet(JobFamily family, List<Pattern> keywords) {}

    private static final String TECH = "TECH";

    private final List<Rule<JobFamily>> titleRules;
    private final List<Rule<JobFamily>> departmentRules;
    private final List<KeywordSet> keywordSets;
    private final List<Rule<Specialization>> specializationRules;

    public JobClassifier() {
        this.titleRules = rules("classify/title-families.csv", JobFamily::valueOf);
        this.departmentRules = rules("classify/department-families.csv", JobClassifier::familyOrTech);
        this.specializationRules = rules("classify/specializations.csv", Specialization::valueOf);
        this.keywordSets = keywordSets("classify/description-keywords.csv");
        log.info("Job classifier loaded: {} title rules, {} department rules, {} keyword sets, {} specialization rules",
                titleRules.size(), departmentRules.size(), keywordSets.size(), specializationRules.size());
    }

    /**
     * @param department the platform's department, may be null
     * @param function   SmartRecruiters' function label, may be null; used when the department gives no vote
     */
    public Classification classify(String title, String department, String function, String description) {
        Map<JobFamily, Integer> votes = new EnumMap<>(JobFamily.class);
        List<String> reasons = new ArrayList<>();

        Optional<Hit<JobFamily>> titleHit = firstMatch(titleRules, title);
        titleHit.ifPresent(hit -> vote(votes, reasons, hit.value(), TITLE_WEIGHT, "title '" + hit.matched() + "'"));

        // generic TECH evidence supports a technical title family, otherwise it means software engineering
        JobFamily techTarget = titleHit.map(Hit::value)
                .filter(f -> f.group() != JobFamily.Group.BUSINESS)
                .orElse(JobFamily.SOFTWARE_ENGINEERING);

        firstMatch(departmentRules, department)
                .or(() -> firstMatch(departmentRules, function))
                .ifPresent(hit -> vote(votes, reasons, resolve(hit.value(), techTarget), DEPARTMENT_WEIGHT,
                        "department '" + hit.matched() + "'"));

        descriptionVote(description).ifPresent(hit -> vote(votes, reasons, resolve(hit.value(), techTarget),
                DESCRIPTION_WEIGHT, "description keywords " + hit.matched()));

        JobFamily family = decide(votes);
        int score = votes.getOrDefault(family, 0);
        Specialization specialization = family.isTech()
                ? firstMatch(specializationRules, title).map(Hit::value).orElse(null)
                : null;
        return new Classification(family, specialization, score, List.copyOf(reasons));
    }

    // ---------------------------------------------------------------- voting

    private record Hit<T>(T value, String matched) {}

    /** null stands for the TECH umbrella. */
    private static JobFamily resolve(JobFamily family, JobFamily techTarget) {
        return family == null ? techTarget : family;
    }

    private static JobFamily familyOrTech(String value) {
        return TECH.equals(value) ? null : JobFamily.valueOf(value);
    }

    private static void vote(Map<JobFamily, Integer> votes, List<String> reasons, JobFamily family, int weight, String why) {
        votes.merge(family, weight, Integer::sum);
        reasons.add(why + " -> " + family + " +" + weight);
    }

    /** Highest score wins if it reaches MIN_SCORE and leads the runner-up by MIN_LEAD. */
    private static JobFamily decide(Map<JobFamily, Integer> votes) {
        List<Map.Entry<JobFamily, Integer>> ranked = votes.entrySet().stream()
                .sorted(Map.Entry.<JobFamily, Integer>comparingByValue().reversed())
                .toList();
        if (ranked.isEmpty() || ranked.getFirst().getValue() < MIN_SCORE) {
            return JobFamily.UNCLASSIFIED;
        }
        int lead = ranked.size() == 1 ? ranked.getFirst().getValue()
                : ranked.getFirst().getValue() - ranked.get(1).getValue();
        return lead >= MIN_LEAD ? ranked.getFirst().getKey() : JobFamily.UNCLASSIFIED;
    }

    private static <T> Optional<Hit<T>> firstMatch(List<Rule<T>> rules, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Rule<T> rule : rules) {
            Matcher m = rule.pattern().matcher(text);
            if (m.find()) {
                return Optional.of(new Hit<>(rule.value(), m.group()));
            }
        }
        return Optional.empty();
    }

    /** One weak vote for the family whose keywords appear most (at least MIN_KEYWORD_HITS, no tie). */
    private Optional<Hit<JobFamily>> descriptionVote(String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        record Count(JobFamily family, List<String> hits) {}
        List<Count> counts = keywordSets.stream()
                .map(set -> new Count(set.family(), set.keywords().stream()
                        .map(k -> k.matcher(description))
                        .filter(Matcher::find)
                        .map(m -> m.group().toLowerCase())
                        .distinct()
                        .toList()))
                .sorted(Comparator.comparingInt((Count c) -> c.hits().size()).reversed())
                .toList();
        Count best = counts.getFirst();
        boolean tie = counts.size() > 1 && counts.get(1).hits().size() == best.hits().size();
        return best.hits().size() >= MIN_KEYWORD_HITS && !tie
                ? Optional.of(new Hit<>(best.family(), best.hits().toString()))
                : Optional.empty();
    }

    // ---------------------------------------------------------------- loading

    private static <T> List<Rule<T>> rules(String path, Function<String, T> parse) {
        return CsvResource.read(path, 2).stream()
                .map(row -> new Rule<>(parse.apply(row[0]), Pattern.compile(row[1], Pattern.CASE_INSENSITIVE)))
                .toList();
    }

    private static List<KeywordSet> keywordSets(String path) {
        return CsvResource.read(path, 2).stream()
                .map(row -> new KeywordSet(familyOrTech(row[0]), Arrays.stream(row[1].split("\\|"))
                        .map(String::strip)
                        .filter(k -> !k.isEmpty())
                        .map(k -> Pattern.compile("(?<![\\w])" + Pattern.quote(k) + "(?![\\w])", Pattern.CASE_INSENSITIVE))
                        .toList()))
                .toList();
    }
}