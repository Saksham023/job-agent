package io.github.saksham023.jobagent.skills;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Finds candidate skills in posting text, without any model: a skill is known by the company it keeps. Postings list
 * skills together ("experience with Gradle, Maven, Git, SonarQube, Artifactory"); when a list already names at least
 * MIN_KNOWN skills we know, its other items are probably skills too. Whether a candidate really is a skill is decided
 * later (by a model); this class only collects them, so it errs towards collecting too many.
 */
public final class SkillMiner {

    /** A list must name at least this many known skills before its other items count. */
    static final int MIN_KNOWN = 2;
    static final int MAX_LENGTH = 30;
    static final int MAX_WORDS = 3;

    /** Splits a sentence into list items: commas, semicolons, colons, brackets, "and", "or", "&", bullets. */
    private static final Pattern ITEM_SEPARATOR = Pattern.compile("\\s*(?:[,;:()\\[\\]|•●]|\\band\\b|\\bor\\b|\\s&\\s|\\s+-\\s+)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])|\\n");
    /** Words in front of the actual name: "experience with Kafka", "tools like Jira", "e.g. Redis", "etc". */
    private static final Pattern LEAD_IN = Pattern.compile(
            "^(?:.*?\\b(?:such as|like|including|incl\\.?|e\\.g\\.?|i\\.e\\.?|experience (?:with|in|of)|knowledge of|"
                    + "familiarity with|proficiency (?:with|in)|expertise in|exposure to|using|with)\\s+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EDGES = Pattern.compile("^[\\s\"'`*\\-–—.+/]+|[\\s\"'`*\\-–—.!?/]+$");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");

    /** Words that never start a skill name: "similar tools", "other languages", "strong Java". */
    private static final Set<String> NOT_FIRST = Set.of(
            "a", "an", "the", "one", "two", "any", "all", "other", "others", "etc", "similar", "equivalent", "related",
            "relevant", "various", "multiple", "several", "different", "modern", "more", "most", "our", "your", "their",
            "its", "some", "such", "same", "strong", "good", "excellent", "solid", "deep", "basic", "advanced",
            "working", "hands-on", "experience", "knowledge", "understanding", "familiarity", "proficiency", "e.g",
            "i.e", "ie", "eg", "incl", "including", "plus", "also", "both", "either", "years", "year", "degree",
            "bachelor's", "master's", "develop", "build", "building", "implement", "maintain", "support", "deploy",
            "design", "using", "use");
    /** Words that end a description, not a name: "CI/CD tools", "AWS services", "cloud platforms". */
    private static final Set<String> NOT_LAST = Set.of(
            "tools", "tooling", "frameworks", "languages", "technologies", "technology", "platforms", "platform",
            "systems", "services", "solutions", "applications", "products", "concepts", "practices", "principles",
            "pipelines", "pipeline", "stack", "ecosystem", "environment", "environments", "skills", "experience",
            "knowledge", "etc");
    /** Words that are generic on their own (as part of a longer name they are fine: "API Gateway", "Data Factory"). */
    private static final Set<String> NOT_ALONE = Set.of(
            "testing", "security", "storage", "databases", "database", "monitoring", "automation", "reliability",
            "scalability", "performance", "reporting", "analytics", "data", "cloud", "apis", "api", "web", "mobile",
            "backend", "frontend", "infrastructure", "networking", "operations", "management", "engineering",
            "programming", "scripting", "coding", "software", "hardware", "development", "design", "new", "core");

    /** One candidate occurrence: the item as written and the sentence it came from. */
    public record Found(String term, String sentence) {
    }

    private SkillMiner() {
    }

    /**
     * The candidate items in one piece of posting text.
     *
     * @param known    is this item a skill we already know? (dictionary lookup of one name)
     * @param excluded is this item something else we know (a company, a city)?
     */
    public static List<Found> candidates(String text, Predicate<String> known, Predicate<String> excluded) {
        List<Found> found = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        for (String sentence : SENTENCE.split(text)) {
            String clean = sentence.strip().replaceFirst("^(?:[-*•●➢✓]|\\d+[.)])\\s*", "");
            if (clean.length() < 8) {
                continue;
            }
            List<String> items = items(clean, known);
            long knownCount = items.stream().filter(known).count();
            if (knownCount < MIN_KNOWN) {
                continue;
            }
            for (String item : items) {
                if (!known.test(item) && plausible(item) && !excluded.test(item)) {
                    found.add(new Found(item, clean.length() > 240 ? clean.substring(0, 240) : clean));
                }
            }
        }
        return found;
    }

    /** The list items of one sentence; "CI/CD" stays whole when known, "Python/Java" is split. */
    static List<String> items(String sentence, Predicate<String> known) {
        List<String> items = new ArrayList<>();
        for (String part : ITEM_SEPARATOR.split(sentence)) {
            String item = clean(part);
            if (item.isEmpty()) {
                continue;
            }
            boolean slashWordKnown = Arrays.stream(item.split(" ")).anyMatch(w -> w.contains("/") && known.test(w));
            if (item.contains("/") && !known.test(item) && !slashWordKnown) {
                for (String piece : item.split("/")) {
                    String p = clean(piece);
                    if (!p.isEmpty()) {
                        items.add(p);
                    }
                }
            } else {
                items.add(item);
            }
        }
        return items;
    }

    private static String clean(String part) {
        String item = LEAD_IN.matcher(part.strip()).replaceFirst("");
        item = EDGES.matcher(item).replaceAll("").replaceAll("\\s+", " ");
        return item.replaceFirst("(?i)\\s+(?:etc|e\\.g)\\.?$", "").strip();
    }

    /** Short, has a letter, not a sentence fragment, does not start with a non-name word. */
    static boolean plausible(String item) {
        if (item.length() < 2 || item.length() > MAX_LENGTH || !HAS_LETTER.matcher(item).find()) {
            return false;
        }
        String[] words = item.split(" ");
        if (words.length > MAX_WORDS) {
            return false;
        }
        String lower = item.toLowerCase(Locale.ROOT);
        return !NOT_ALONE.contains(lower) && !NOT_FIRST.contains(words[0].toLowerCase(Locale.ROOT))
                && !NOT_LAST.contains(words[words.length - 1].toLowerCase(Locale.ROOT));
    }

    /** The key a term is stored under: lower case, single spaces ("Node.JS" and "node.js" are one term). */
    public static String key(String term) {
        return term.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Collects occurrences per term key: the most common spelling, jobs, companies, example sentences. */
    public static final class Tally {
        final Map<String, Map<String, Integer>> spellings = new LinkedHashMap<>();
        final Map<String, Set<Long>> jobs = new LinkedHashMap<>();
        final Map<String, Map<Long, String>> sentencesByCompany = new LinkedHashMap<>();

        public void add(long jobId, long companyId, List<Found> found) {
            for (Found f : found) {
                String key = key(f.term());
                spellings.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(f.term(), 1, Integer::sum);
                jobs.computeIfAbsent(key, k -> new HashSet<>()).add(jobId);
                sentencesByCompany.computeIfAbsent(key, k -> new LinkedHashMap<>()).putIfAbsent(companyId, f.sentence());
            }
        }

        public List<Candidate> candidates(int maxContexts) {
            List<Candidate> result = new ArrayList<>();
            for (String key : spellings.keySet()) {
                String term = spellings.get(key).entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                Map<Long, String> byCompany = sentencesByCompany.get(key);
                result.add(new Candidate(key, term, jobs.get(key).size(), byCompany.size(),
                        byCompany.values().stream().limit(maxContexts).toList()));
            }
            return result;
        }
    }

    /** A term with its counts and example sentences (from different companies). */
    public record Candidate(String key, String term, int jobs, int companies, List<String> contexts) {
    }
}
