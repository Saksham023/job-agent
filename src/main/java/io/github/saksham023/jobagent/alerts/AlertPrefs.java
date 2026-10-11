package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.api.JobSearch;
import io.github.saksham023.jobagent.requirements.JobFamily;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One user's job alert: the same filters as the website's filter panel. An empty list or a null year means "no filter".
 * Families, cities and companies match ANY of their values, skills ALL of them, as on the website.
 */
public record AlertPrefs(boolean enabled, List<String> families, Integer minYears, Integer maxYears,
                         boolean includeUnstated, List<String> skills, List<String> cities, List<String> companies) {

    static final int MAX_YEARS = 50;
    static final int MAX_VALUES = 50;
    static final int MAX_VALUE_LENGTH = 80;
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * The checked, tidied version of what a user sent: known families only, years 0-50 with from <= to, values
     * trimmed and de-duplicated, "remote" written as the website writes it. Throws IllegalArgumentException (400).
     */
    public AlertPrefs checked() {
        List<String> familyNames = new ArrayList<>();
        for (String name : nonNull(families)) {
            JobFamily family;
            try {
                family = JobFamily.valueOf(name.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("Unknown job family: " + name);
            }
            if (family != JobFamily.UNCLASSIFIED && !familyNames.contains(family.name())) {
                familyNames.add(family.name());
            }
        }
        if ((minYears != null && (minYears < 0 || minYears > MAX_YEARS)) || (maxYears != null && (maxYears < 0 || maxYears > MAX_YEARS))) {
            throw new IllegalArgumentException("Years must be between 0 and " + MAX_YEARS + ".");
        }
        if (minYears != null && maxYears != null && minYears > maxYears) {
            throw new IllegalArgumentException("'From' years must not be more than 'to' years.");
        }
        List<String> cityNames = tidy(cities, "cities").stream()
                .map(c -> c.equalsIgnoreCase(JobSearch.REMOTE) ? JobSearch.REMOTE : c).distinct().toList();
        List<String> slugs = tidy(companies, "companies").stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().toList();
        for (String slug : slugs) {
            if (!SLUG.matcher(slug).matches()) {
                throw new IllegalArgumentException("Unknown company: " + slug);
            }
        }
        return new AlertPrefs(enabled, List.copyOf(familyNames), minYears, maxYears, includeUnstated,
                tidy(skills, "skills"), cityNames, slugs);
    }

    private static List<String> tidy(List<String> values, String what) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : nonNull(values)) {
            if (value != null && !value.isBlank()) {
                String v = value.strip();
                if (v.length() > MAX_VALUE_LENGTH) {
                    throw new IllegalArgumentException("A value in " + what + " is too long.");
                }
                out.add(v);
            }
        }
        if (out.size() > MAX_VALUES) {
            throw new IllegalArgumentException("At most " + MAX_VALUES + " " + what + ".");
        }
        return List.copyOf(out);
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : list;
    }
}
