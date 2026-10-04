package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.DescriptionSections.Line;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts one overall years-of-experience requirement per job from its title and plain-text description.
 * Steps: find every "N years" style mention, label it by context (required, preferred, noise), then combine:
 * a range in the title wins; otherwise the minimum is the LARGEST required lower bound (every requirement must
 * hold at once, so "2+ years software engineering and 1+ year Java" means 2), alternatives joined by "or"
 * count as their smallest. Low-evidence results are marked so a later LLM pass can review them.
 * Section and "preferred" detection is shared with the skill extractor (DescriptionSections).
 */
@Component
public class ExperienceExtractor {

    public enum Confidence {
        HIGH,      // a range in the title, or a single clear requirement
        MEDIUM,    // several consistent requirements
        LOW,       // only "preferred" mentions, alternatives, or an estimate from the title
        NONE       // no experience requirement found
    }

    /**
     * @param minYears          lower bound the candidate needs, or null when unknown
     * @param maxYears          upper bound from the range that set the minimum ("3-5 years"), or null when open-ended
     * @param preferredMinYears a "nice to have" lower bound, never used as a hard filter
     * @param evidence          the line the minimum came from, for debugging and for showing the user
     */
    public record Experience(Integer minYears, Integer maxYears, Integer preferredMinYears,
                             String evidence, Confidence confidence) {

        static Experience none() {
            return new Experience(null, null, null, null, Confidence.NONE);
        }
    }

    private static final String NUMBER =
            "\\d{1,2}(?:\\.\\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen";

    /** "5+ years", "3-5 yrs", "3 – 5 years", "8 to 12 years", "minimum 4 years", "up to ~2 years". */
    private static final Pattern MENTION = Pattern.compile(
            "(?i)(?<qualifier>\\b(?:minimum(?:\\s+of)?|min\\.?|at\\s+least|more\\s+than|over|up\\s*to)\\s+~?\\s*)?"
                    + "\\b(?<low>" + NUMBER + ")\\s*\\+?\\s*"
                    + "(?:(?:-|\\u2013|\\u2014|to)\\s*(?<high>" + NUMBER + ")\\s*)?"
                    + "\\+?\\s*(?:years?|yrs?)\\b");

    /** Words that make a mention about a person's experience. */
    private static final Pattern EXPERIENCE_CUE = Pattern.compile(
            "(?i)experien|\\bexp\\b|expertise|background|hands-on|track record|\\bworking\\b|\\bworked\\b"
                    + "|post[- ]qualification|in (?:the |a |an )?(?:industry|field|role)");

    /** Words that make a mention about something else (the company, a contract, the future). */
    private static final Pattern NOISE_CUE = Pattern.compile(
            "(?i)\\bfounded\\b|\\bago\\b|\\byears? old\\b|warranty|anniversar|\\bnext\\s+\\d"
                    + "|\\bwithin\\s+(?:the\\s+)?(?:first|next|\\d)|\\bper year\\b"
                    + "|\\bsince\\s+(?:19|20)\\d\\d|\\bhistory\\b|\\blegacy\\b|\\bour\\s+\\d|\\bfor over\\b");

    /** Alternatives inside one line: "5+ years with a BS or 3+ years with an MS". */
    private static final Pattern ALTERNATIVE = Pattern.compile("(?i)\\bor\\b[^;,.]{0,25}$");

    private static final Map<String, Integer> WORD_NUMBERS = Map.ofEntries(
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
            Map.entry("fifteen", 15));

    private static final int MAX_PLAUSIBLE_YEARS = 30;
    private static final int MAX_EVIDENCE_LENGTH = 200;

    /**
     * Fallback when the text states no years: unambiguous title words give an ESTIMATE (LOW confidence).
     * Order matters ("Senior Staff" is staff, "Senior Director" is director). Words that mean different
     * things per company (manager, executive, analyst, associate, lead, specialist) give nothing.
     */
    private record TitleEstimate(Pattern words, int minYears, Integer maxYears) {}

    private static final List<TitleEstimate> TITLE_ESTIMATES = List.of(
            new TitleEstimate(Pattern.compile("(?i)\\b(?:intern(?:ship)?|trainee|apprentice)\\b"), 0, 1),
            new TitleEstimate(Pattern.compile("(?i)\\b(?:director|head|vp|vice president|chief)\\b"), 12, null),
            new TitleEstimate(Pattern.compile("(?i)\\b(?:staff|principal|distinguished)\\b"), 8, null),
            new TitleEstimate(Pattern.compile("(?i)\\b(?:senior|sr)\\b\\.?(?!\\s*executive)"), 5, null),
            new TitleEstimate(Pattern.compile("(?i)\\b(?:junior|jr)\\b"), 0, 2));

    private static final Pattern INTERN_EMPLOYMENT = Pattern.compile("(?i)\\b(?:intern(?:ship)?|trainee)\\b");

    private enum Kind { REQUIRED, PREFERRED }

    /** One "N years" found in the text, already labeled. */
    private record Mention(Integer low, Integer high, boolean upToOnly, Kind kind,
                           int line, int start, int end, String lineText) {}

    public Experience extract(String title, String description) {
        return extract(title, description, null);
    }

    /** @param employmentType the platform's employment type ("Intern", "Full-time"...), may be null */
    public Experience extract(String title, String description, String employmentType) {
        Experience fromTitle = fromTitle(title);
        List<Mention> mentions = mentions(description);

        List<Mention> required = mentions.stream().filter(m -> m.kind() == Kind.REQUIRED).toList();
        Integer preferred = mentions.stream()
                .filter(m -> m.kind() == Kind.PREFERRED && m.low() != null)
                .map(Mention::low)
                .max(Integer::compare)
                .orElse(null);

        if (fromTitle != null) {
            return new Experience(fromTitle.minYears(), fromTitle.maxYears(), preferred, title, Confidence.HIGH);
        }
        if (!required.isEmpty()) {
            return combine(required, preferred);
        }
        if (preferred == null) {
            return estimateFromTitle(title, employmentType);
        }
        // only "nice to have" lines: some templates (ServiceNow, Razorpay) list everything as preferred,
        // so the preferred numbers are the best estimate of the requirement
        Experience softOnly = combine(mentions.stream().filter(m -> m.kind() == Kind.PREFERRED).toList(), null);
        return new Experience(softOnly.minYears(), softOnly.maxYears(), preferred, softOnly.evidence(), Confidence.LOW);
    }

    // ---------------------------------------------------------------- title

    /** No years in the text: estimate from the employment type or an unambiguous title word, else NONE. */
    private static Experience estimateFromTitle(String title, String employmentType) {
        if (employmentType != null && INTERN_EMPLOYMENT.matcher(employmentType).find()) {
            return new Experience(0, 1, null, "employment type: " + employmentType, Confidence.LOW);
        }
        if (title != null) {
            for (TitleEstimate estimate : TITLE_ESTIMATES) {
                if (estimate.words().matcher(title).find()) {
                    return new Experience(estimate.minYears(), estimate.maxYears(), null,
                            evidence("title: " + title), Confidence.LOW);
                }
            }
        }
        return Experience.none();
    }

    /** "Product Management | Experience : 7-9 yrs", "Site Reliability Engineer (5 to 8 years)". */
    private static Experience fromTitle(String title) {
        if (title == null) {
            return null;
        }
        Matcher m = MENTION.matcher(title);
        if (!m.find()) {
            return null;
        }
        Integer low = toYears(m.group("low"));
        Integer high = toYears(m.group("high"));
        if (low == null || (high != null && high < low)) {
            return null;
        }
        return new Experience(low, high, null, title, Confidence.HIGH);
    }

    // ---------------------------------------------------------------- description: find and label

    /** Every labeled "N years" mention, line by line; company-intro sections ("founded 12 years ago") are skipped. */
    private static List<Mention> mentions(String description) {
        List<Mention> mentions = new ArrayList<>();
        List<Line> lines = DescriptionSections.lines(description);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.section() == DescriptionSections.Kind.INTRO) {
                continue;
            }
            boolean inPreferredSection = line.section() == DescriptionSections.Kind.PREFERRED;
            Matcher m = MENTION.matcher(line.text());
            while (m.find()) {
                Mention mention = label(m, line.text(), i, inPreferredSection);
                if (mention != null) {
                    mentions.add(mention);
                }
            }
        }
        return mentions;
    }

    /** Turns one regex match into a labeled mention, or null when it is noise. */
    private static Mention label(Matcher m, String line, int lineIndex, boolean inPreferredSection) {
        Integer low = toYears(m.group("low"));
        Integer high = toYears(m.group("high"));
        String qualifier = m.group("qualifier") == null ? "" : m.group("qualifier").toLowerCase(Locale.ROOT);
        if (low == null || low > MAX_PLAUSIBLE_YEARS || (high != null && (high < low || high > MAX_PLAUSIBLE_YEARS))) {
            return null;
        }

        String window = line.substring(Math.max(0, m.start() - 50), Math.min(line.length(), m.end() + 80));
        if (NOISE_CUE.matcher(window).find()) {
            return null;
        }
        boolean bullet = line.startsWith("-");
        if (!EXPERIENCE_CUE.matcher(window).find() && !bullet) {
            return null;                                                // "a 3-year roadmap", "5 years of growth"
        }

        Kind kind = inPreferredSection || DescriptionSections.preferredInSentence(line, m.start())
                ? Kind.PREFERRED : Kind.REQUIRED;

        boolean upTo = qualifier.startsWith("up");
        return upTo
                ? new Mention(null, low, true, kind, lineIndex, m.start(), m.end(), line)   // "up to 2 years" = 0..2
                : new Mention(low, high, false, kind, lineIndex, m.start(), m.end(), line);
    }

    // ---------------------------------------------------------------- combine

    private static Experience combine(List<Mention> required, Integer preferred) {
        List<Integer> lowerBounds = new ArrayList<>();
        boolean sawAlternatives = false;

        // per line: alternatives joined by "or" count as their smallest lower bound; otherwise each counts
        for (List<Mention> line : groupByLine(required)) {
            List<Integer> lows = line.stream().map(Mention::low).filter(v -> v != null).toList();
            if (lows.size() > 1 && hasOrBetween(line)) {
                sawAlternatives = true;
                lowerBounds.add(lows.stream().min(Integer::compare).orElseThrow());
            } else {
                lowerBounds.addAll(lows);
            }
        }

        Integer min = lowerBounds.stream().max(Integer::compare).orElse(null);
        Mention source = required.stream()
                .filter(m -> min == null ? m.upToOnly() : min.equals(m.low()))
                .findFirst()
                .orElse(required.getFirst());

        Integer max = source.high();
        if (min == null) {                                              // only "up to N years"
            return new Experience(0, source.high(), preferred, evidence(source.lineText()), Confidence.MEDIUM);
        }
        if (max != null && max < min) {
            max = null;
        }

        Confidence confidence;
        if (sawAlternatives) {
            confidence = Confidence.LOW;
        } else if (required.size() == 1) {
            confidence = Confidence.HIGH;
        } else {
            confidence = Confidence.MEDIUM;
        }
        return new Experience(min, max, preferred, evidence(source.lineText()), confidence);
    }

    private static List<List<Mention>> groupByLine(List<Mention> mentions) {
        List<List<Mention>> lines = new ArrayList<>();
        int current = -1;
        for (Mention m : mentions) {
            if (m.line() != current) {
                lines.add(new ArrayList<>());
                current = m.line();
            }
            lines.getLast().add(m);
        }
        return lines;
    }

    /** True when some mention is introduced by "or" right after the previous one: "5+ years ... or 3+ years". */
    private static boolean hasOrBetween(List<Mention> sameLine) {
        String text = sameLine.getFirst().lineText();
        for (int i = 1; i < sameLine.size(); i++) {
            int from = sameLine.get(i - 1).end();
            int to = sameLine.get(i).start();
            if (from <= to && ALTERNATIVE.matcher(text.substring(from, to)).find()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private static Integer toYears(String token) {
        if (token == null) {
            return null;
        }
        Integer word = WORD_NUMBERS.get(token.toLowerCase(Locale.ROOT));
        if (word != null) {
            return word;
        }
        return (int) Math.round(Double.parseDouble(token));
    }

    private static String evidence(String line) {
        return line.length() <= MAX_EVIDENCE_LENGTH ? line : line.substring(0, MAX_EVIDENCE_LENGTH) + "...";
    }
}