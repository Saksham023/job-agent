package io.github.saksham023.jobagent.requirements;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The lines one company repeats in most of its postings: its intro ("PayPal has been revolutionizing commerce for
 * more than 25 years"), benefits, legal text. They describe the company, not the job, so the extractors skip them.
 * Found from the data, never configured: a line is boilerplate when it appears under more than half of the company's
 * distinct job titles (counting titles, not postings, so 20 copies of one "Senior Software Engineer" posting do not
 * turn its requirements into boilerplate). Headings are always kept (the extractors need "Minimum Qualifications"
 * to tell required from preferred), and so are short lines.
 */
public final class CompanyBoilerplate {

    /** Fewer distinct titles than this and nothing counts as boilerplate (too little evidence). */
    static final int MIN_TITLES = 5;
    static final double SHARE = 0.5;
    static final int MIN_LINE_LENGTH = 20;

    /** Leading bullets and list numbers, so "• Text" and "- Text" are the same line. */
    private static final Pattern LEADING_MARKS = Pattern.compile("^[\\s\\-\\u2022\\u25CF\\u00B7*\\u27A2\\u2713\\d.)]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** One posting of the company: only the title and the plain-text description matter. */
    public record Posting(String title, String description) {
    }

    private static final CompanyBoilerplate NONE = new CompanyBoilerplate(Set.of());

    private final Set<String> lines;

    private CompanyBoilerplate(Set<String> lines) {
        this.lines = lines;
    }

    public static CompanyBoilerplate none() {
        return NONE;
    }

    /** @param postings every open posting of ONE company */
    public static CompanyBoilerplate of(List<Posting> postings) {
        Set<String> titles = postings.stream().map(p -> key(p.title())).collect(Collectors.toSet());
        if (titles.size() < MIN_TITLES) {
            return NONE;
        }
        Map<String, Set<String>> titlesByLine = new HashMap<>();
        for (Posting posting : postings) {
            if (posting.description() == null) {
                continue;
            }
            String title = key(posting.title());
            for (String line : posting.description().split("\\R")) {
                if (candidate(line)) {
                    titlesByLine.computeIfAbsent(key(line), k -> new HashSet<>()).add(title);
                }
            }
        }
        Set<String> boilerplate = titlesByLine.entrySet().stream()
                .filter(e -> e.getValue().size() > titles.size() * SHARE)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        return new CompanyBoilerplate(boilerplate);
    }

    /** The description without the boilerplate lines (null stays null). */
    public String strip(String description) {
        if (description == null || lines.isEmpty()) {
            return description;
        }
        return description.lines()
                .filter(line -> !candidate(line) || !lines.contains(key(line)))
                .collect(Collectors.joining("\n"));
    }

    public int size() {
        return lines.size();
    }

    /** Long enough to be a sentence, and not a heading. */
    private static boolean candidate(String line) {
        String text = line.strip();
        return text.length() >= MIN_LINE_LENGTH && !DescriptionSections.isHeading(text);
    }

    /** Lower case, no leading bullet, single spaces. */
    static String key(String text) {
        String withoutMarks = LEADING_MARKS.matcher(text == null ? "" : text).replaceFirst("");
        return SPACES.matcher(withoutMarks).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }
}
