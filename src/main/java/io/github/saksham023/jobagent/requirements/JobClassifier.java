package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.common.CsvResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * Engineering-like jobs also get SECONDARY families from the evidence that did not win (recall first: a
 * search for any of a job's families finds it), e.g. "Staff SDE - AI Engineer" is DATA_ML, also
 * SOFTWARE_ENGINEERING.
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
    static final int MIN_SECONDARY_KEYWORD_HITS = 4;

    /**
     * @param secondaryFamilies other technical families the job also belongs to (never contains family)
     * @param reasons           the votes, e.g. "title 'sde' -> SOFTWARE_ENGINEERING +3", for debugging and the user
     * @param familyGuessed     the family is SOFTWARE_ENGINEERING only because the title says "engineer" / "architect"
     *                          (the catch-all rule) and no specific evidence: a model may check it (GapFiller)
     */
    public record Classification(JobFamily family, List<JobFamily> secondaryFamilies, Specialization specialization,
                                 int score, List<String> reasons, boolean familyGuessed) {
    }

    /** AI_ENGINEERING builds products on top of models (LLM apps, agents, RAG); ML_AI builds the models. */
    public enum Specialization {
        FULL_STACK, FRONTEND, BACKEND, MOBILE, EMBEDDED, DATA_ENGINEERING, AI_ENGINEERING, ML_AI, SRE, PLATFORM_INFRA,
        SECURITY
    }

    private record Rule<T>(T value, Pattern pattern) {}

    /** A family, or null for the generic TECH umbrella. */
    private record KeywordSet(JobFamily family, List<Pattern> keywords) {}

    private static final String TECH = "TECH";

    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final List<Rule<JobFamily>> titleRules;
    private final List<Rule<JobFamily>> fallbackTitleRules;
    private final List<Rule<JobFamily>> departmentRules;
    private final List<KeywordSet> keywordSets;
    private final List<KeywordSet> secondaryKeywordSets;
    private final List<Rule<Specialization>> specializationRules;

    public JobClassifier() {
        this.titleRules = rules("classify/title-families.csv", JobFamily::valueOf);
        this.fallbackTitleRules = rules("classify/title-fallback.csv", JobFamily::valueOf);
        this.departmentRules = rules("classify/department-families.csv", JobClassifier::familyOrTech);
        this.specializationRules = rules("classify/specializations.csv", Specialization::valueOf);
        this.keywordSets = keywordSets("classify/description-keywords.csv");
        this.secondaryKeywordSets = keywordSets("classify/description-secondary-keywords.csv");
        log.info("Job classifier loaded: {} title rules (+{} fallback), {} department rules, {} keyword sets "
                        + "(+{} secondary-only), {} specialization rules", titleRules.size(), fallbackTitleRules.size(),
                departmentRules.size(), keywordSets.size(), secondaryKeywordSets.size(), specializationRules.size());
    }

    /**
     * @param department the platform's department, may be null
     * @param function   SmartRecruiters' function label, may be null; used when the department gives no vote
     */
    public Classification classify(String rawTitle, String department, String function, String description) {
        String title = rawTitle == null ? null : SPACES.matcher(rawTitle.strip()).replaceAll(" ");  // "Software  Architect"
        Map<JobFamily, Integer> votes = new EnumMap<>(JobFamily.class);
        List<String> reasons = new ArrayList<>();

        List<Hit<JobFamily>> titleHits = titleHits(title);
        Optional<Hit<JobFamily>> departmentHit = firstMatch(departmentRules, department)
                .or(() -> firstMatch(departmentRules, function));

        // the generic "engineer" catch-all only says "some engineering job": when the department names a specific
        // technical family ("Synthesis Engineer" in "Hardware Engineering"), the department decides alone; against a
        // business department ("Technical Architect" in "Sales") the title keeps its full vote
        Optional<Hit<JobFamily>> titleHit = titleHits.stream().findFirst()
                .or(() -> firstMatch(fallbackTitleRules, title));
        boolean genericTitle = titleHits.isEmpty() && titleHit.isPresent();
        boolean departmentDecides = genericTitle
                && departmentHit.map(Hit::value).filter(f -> f.group() != JobFamily.Group.BUSINESS).isPresent();
        if (departmentDecides) {
            reasons.add("title '" + titleHit.get().matched() + "' is generic: department '"
                    + departmentHit.get().matched() + "' decides");
            titleHit = Optional.empty();
        }
        titleHit.ifPresent(hit -> vote(votes, reasons, hit.value(), TITLE_WEIGHT, "title '" + hit.matched() + "'"));

        // generic TECH evidence supports a technical title family, otherwise it means software engineering
        JobFamily techTarget = titleHit.map(Hit::value)
                .filter(f -> f.group() != JobFamily.Group.BUSINESS)
                .orElse(JobFamily.SOFTWARE_ENGINEERING);

        departmentHit.ifPresent(hit -> vote(votes, reasons, resolve(hit.value(), techTarget), DEPARTMENT_WEIGHT,
                "department '" + hit.matched() + "'"));

        List<KeywordCount> keywordCounts = keywordCounts(description, keywordSets);
        Optional<Hit<JobFamily>> descriptionHit = descriptionVote(keywordCounts);
        descriptionHit.ifPresent(hit -> vote(votes, reasons, resolve(hit.value(), techTarget),
                DESCRIPTION_WEIGHT, "description keywords " + hit.matched()));

        JobFamily family = decide(votes);
        int score = votes.getOrDefault(family, 0);
        List<KeywordCount> secondaryEvidence = new ArrayList<>(keywordCounts);
        secondaryEvidence.addAll(keywordCounts(description, secondaryKeywordSets));
        // the main family comes from the role part, but any rule matching the whole title may add a secondary family
        // ("AI Engineer, FDE" is also software engineering)
        List<JobFamily> secondary = secondaryFamilies(family, allMatches(titleRules, title), departmentHit, descriptionHit,
                secondaryEvidence, reasons);
        boolean engineeringLike = family.isTech() || family == JobFamily.SALES_ENGINEERING;
        if (departmentDecides && engineeringLike && family != JobFamily.SOFTWARE_ENGINEERING
                && !secondary.contains(JobFamily.SOFTWARE_ENGINEERING)) {
            secondary = new ArrayList<>(secondary);                 // recall first: an "... Engineer" stays findable
            secondary.add(JobFamily.SOFTWARE_ENGINEERING);          // by software searches (hardware excepted)
            secondary = List.copyOf(secondary);
            reasons.add("generic title 'engineer' -> also SOFTWARE_ENGINEERING");
        }
        boolean technical = family.isTech() || secondary.stream().anyMatch(JobFamily::isTech);
        Specialization specialization = technical
                ? firstMatch(specializationRules, title).map(Hit::value).orElse(null)
                : null;
        boolean guessed = genericTitle && !departmentDecides && family == JobFamily.SOFTWARE_ENGINEERING;
        return new Classification(family, secondary, specialization, score, List.copyOf(reasons), guessed);
    }

    /**
     * The other technical families the evidence points to, so a search for any of them still finds the job:
     * every other title rule that matched, the department, the description's winning keyword set and any other
     * technical keyword set with at least MIN_SECONDARY_KEYWORD_HITS distinct hits. Generic TECH evidence means
     * SOFTWARE_ENGINEERING for a job whose own family is not technical (a forward deployed or solutions engineer
     * whose work is engineering). Only engineering-like jobs get secondary families: product, design and business
     * families stay separate on purpose (the user opts in to them).
     */
    private static List<JobFamily> secondaryFamilies(JobFamily family, List<Hit<JobFamily>> titleHits,
                                                     Optional<Hit<JobFamily>> department,
                                                     Optional<Hit<JobFamily>> description,
                                                     List<KeywordCount> keywordCounts, List<String> reasons) {
        if (!family.isTech() && family != JobFamily.SALES_ENGINEERING && family != JobFamily.HARDWARE_ENGINEERING
                && family != JobFamily.UNCLASSIFIED) {
            return List.of();
        }
        // generic TECH evidence ("Engineering" department) means software for a sales engineer, nothing for a chip
        // designer: a hardware job becomes findable by software searches only through specific software evidence
        JobFamily generic = family.isTech() || family == JobFamily.HARDWARE_ENGINEERING ? family : JobFamily.SOFTWARE_ENGINEERING;
        Set<JobFamily> secondary = new LinkedHashSet<>();
        List<String> why = new ArrayList<>();
        for (Hit<JobFamily> hit : titleHits) {
            addSecondary(secondary, why, family, hit.value(), "title '" + hit.matched() + "'");
        }
        department.ifPresent(hit -> addSecondary(secondary, why, family, resolve(hit.value(), generic),
                "department '" + hit.matched() + "'"));
        description.ifPresent(hit -> addSecondary(secondary, why, family, resolve(hit.value(), generic),
                "description keywords"));
        for (KeywordCount count : keywordCounts) {
            if (count.family() != null && count.hits().size() >= MIN_SECONDARY_KEYWORD_HITS) {
                addSecondary(secondary, why, family, count.family(), "description keywords " + count.hits());
            }
        }
        reasons.addAll(why);
        return List.copyOf(secondary);
    }

    private static void addSecondary(Set<JobFamily> secondary, List<String> why, JobFamily family,
                                     JobFamily candidate, String evidence) {
        if (candidate.isTech() && candidate != family && secondary.add(candidate)) {
            why.add(evidence + " -> also " + candidate);
        }
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

    /** Every rule that matches, in file order, one per value (the first rule's text for each). */
    /**
     * The title rules on the ROLE part of the title, before the first comma, when it matches any: "Software
     * Development Engineer II, Sales Abuse Prevention" is software engineering, whatever the team after the comma
     * is called (Amazon and others name the team there). Otherwise ("Manager, Software Engineering") the whole title.
     * Only the main family: secondary families still come from every rule that matches the whole title.
     */
    private List<Hit<JobFamily>> titleHits(String title) {
        String role = rolePart(title);
        List<Hit<JobFamily>> hits = role == null ? List.of() : allMatches(titleRules, role);
        return hits.isEmpty() ? allMatches(titleRules, title) : hits;
    }

    /** The title before its first comma, or null when it has none. */
    static String rolePart(String title) {
        int comma = title == null ? -1 : title.indexOf(',');
        return comma > 0 ? title.substring(0, comma) : null;
    }

    private static <T> List<Hit<T>> allMatches(List<Rule<T>> rules, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Hit<T>> hits = new ArrayList<>();
        for (Rule<T> rule : rules) {
            Matcher m = rule.pattern().matcher(text);
            if (m.find() && hits.stream().noneMatch(h -> h.value().equals(rule.value()))) {
                hits.add(new Hit<>(rule.value(), m.group()));
            }
        }
        return hits;
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

    /** The distinct keywords of one keyword set found in a description; family null = the TECH umbrella. */
    private record KeywordCount(JobFamily family, List<String> hits) {}

    /** Each keyword set's distinct hits in the description, most hits first (empty for no description). */
    private static List<KeywordCount> keywordCounts(String description, List<KeywordSet> sets) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        return sets.stream()
                .map(set -> new KeywordCount(set.family(), set.keywords().stream()
                        .map(k -> k.matcher(description))
                        .filter(Matcher::find)
                        .map(m -> m.group().toLowerCase())
                        .distinct()
                        .toList()))
                .sorted(Comparator.comparingInt((KeywordCount c) -> c.hits().size()).reversed())
                .toList();
    }

    /** One weak vote for the family whose keywords appear most (at least MIN_KEYWORD_HITS, no tie). */
    private static Optional<Hit<JobFamily>> descriptionVote(List<KeywordCount> counts) {
        if (counts.isEmpty()) {
            return Optional.empty();
        }
        KeywordCount best = counts.getFirst();
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