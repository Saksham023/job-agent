package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.common.CsvResource;
import io.github.saksham023.jobagent.requirements.DescriptionSections.Kind;
import io.github.saksham023.jobagent.requirements.DescriptionSections.Line;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the skills a job asks for, using the dictionary in classify/skills.csv (canonical names + aliases).
 * Skills in the title or in requirement/responsibility sections are REQUIRED; skills only in "nice to have"
 * sections or sentences are PREFERRED; company-intro sections are ignored, and so is the hiring company's own
 * product name (Databricks jobs mention Databricks everywhere). Ambiguous aliases (Go, R, Swift, Excel...)
 * count only when written as in the dictionary AND either next to another skill ("Python, Bash, or Go") or
 * right after "in / with / using" ("experience in Go").
 */
@Component
public class SkillExtractor {

    private static final Logger log = LoggerFactory.getLogger(SkillExtractor.class);

    static final int AMBIGUOUS_NEIGHBOUR_DISTANCE = 40;
    static final int MAX_PRIMARY_LANGUAGES = 3;

    public enum Category { LANGUAGE, FRAMEWORK, FRONTEND, MOBILE, DATASTORE, MESSAGING, CLOUD, DEVOPS, DATA, ML_AI, CONCEPT, TOOL }

    /**
     * @param required         canonical skill names the job requires, in order of first mention
     * @param preferred        skills that appear only as "nice to have"
     * @param primaryLanguages the programming languages the job is mainly about (title, else required, else preferred)
     */
    public record Skills(List<String> required, List<String> preferred, List<String> primaryLanguages) {
    }

    private record Alias(String skill, Category category, Pattern pattern, boolean ambiguous) {}

    private record Found(String skill, Category category, int start) {}

    /** "in Go", "with Spring", "using Excel": the word right before an ambiguous alias that makes it a name. */
    private static final Pattern NAME_CONTEXT = Pattern.compile("(?i)\\b(?:in|with|using)\\s+$");

    private final List<Alias> aliases;

    public SkillExtractor() {
        this.aliases = load("classify/skills.csv");
        log.info("Skill dictionary loaded: {} skills, {} aliases",
                aliases.stream().map(Alias::skill).distinct().count(), aliases.size());
    }

    /**
     * @param companyName the hiring company's name, so its own product is not counted as a skill (may be null)
     */
    public Skills extract(String title, String description, String companyName) {
        String company = companyName == null ? "" : normalize(companyName);
        Set<String> required = new LinkedHashSet<>();
        Set<String> preferred = new LinkedHashSet<>();
        List<String> titleLanguages = new ArrayList<>();
        List<String> requiredLanguages = new ArrayList<>();
        List<String> preferredLanguages = new ArrayList<>();

        for (Found f : find(title, company)) {
            required.add(f.skill());
            if (f.category() == Category.LANGUAGE) {
                titleLanguages.add(f.skill());
            }
        }

        for (Line line : DescriptionSections.lines(description)) {
            if (line.section() == Kind.INTRO) {
                continue;
            }
            for (Found f : find(line.text(), company)) {
                boolean isPreferred = line.section() == Kind.PREFERRED
                        || DescriptionSections.preferredInSentence(line.text(), f.start());
                if (isPreferred) {
                    preferred.add(f.skill());
                    if (f.category() == Category.LANGUAGE) {
                        preferredLanguages.add(f.skill());
                    }
                } else {
                    required.add(f.skill());
                    if (f.category() == Category.LANGUAGE) {
                        requiredLanguages.add(f.skill());
                    }
                }
            }
        }
        preferred.removeAll(required);

        // the title says what the job is written in; otherwise the required languages; otherwise the preferred ones
        List<String> languages = !titleLanguages.isEmpty() ? titleLanguages
                : !requiredLanguages.isEmpty() ? requiredLanguages
                : preferredLanguages;
        List<String> primary = languages.stream()
                .distinct()
                .limit(MAX_PRIMARY_LANGUAGES)
                .toList();
        return new Skills(List.copyOf(required), List.copyOf(preferred), primary);
    }

    // ---------------------------------------------------------------- matching

    /** Every dictionary skill in one piece of text, in order of position, without duplicates. */
    private List<Found> find(String text, String company) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Found> clear = new ArrayList<>();
        List<Found> ambiguous = new ArrayList<>();
        for (Alias alias : aliases) {
            if (normalize(alias.skill()).equals(company)) {
                continue;                                               // a company's own product
            }
            Matcher m = alias.pattern().matcher(text);
            while (m.find()) {
                (alias.ambiguous() ? ambiguous : clear).add(new Found(alias.skill(), alias.category(), m.start()));
            }
        }
        List<Found> result = new ArrayList<>(clear);
        for (Found candidate : ambiguous) {
            boolean hasNeighbour = clear.stream()
                    .anyMatch(f -> Math.abs(f.start() - candidate.start()) <= AMBIGUOUS_NEIGHBOUR_DISTANCE);
            String before = text.substring(Math.max(0, candidate.start() - 8), candidate.start());
            if (hasNeighbour || NAME_CONTEXT.matcher(before).find()) {
                result.add(candidate);
            }
        }
        result.sort((a, b) -> Integer.compare(a.start(), b.start()));

        Set<String> seen = new LinkedHashSet<>();
        return result.stream().filter(f -> seen.add(f.skill())).toList();
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ---------------------------------------------------------------- loading

    /**
     * Rows "canonical,CATEGORY,alias|alias|~Ambiguous". Clear aliases match case-insensitively as whole tokens
     * (so "java" does not match "javascript"); ambiguous ones match case-sensitively and never before a hyphen.
     */
    private static List<Alias> load(String path) {
        List<Alias> aliases = new ArrayList<>();
        for (String[] row : CsvResource.read(path, 3)) {
            String skill = row[0];
            Category category = Category.valueOf(row[1]);
            for (String raw : row[2].split("\\|")) {
                String alias = raw.strip();
                if (alias.isEmpty()) {
                    continue;
                }
                boolean ambiguous = alias.startsWith("~");
                String word = ambiguous ? alias.substring(1) : alias;
                // a slash separates skills ("Python/Java", "Java/Go"), so it is not part of the guards
                String regex = "(?<![\\w+#.-])" + Pattern.quote(word) + (ambiguous ? "(?![\\w+#&-])" : "(?![\\w+#])");
                Pattern pattern = ambiguous ? Pattern.compile(regex) : Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                aliases.add(new Alias(skill, category, pattern, ambiguous));
            }
        }
        return List.copyOf(aliases);
    }
}