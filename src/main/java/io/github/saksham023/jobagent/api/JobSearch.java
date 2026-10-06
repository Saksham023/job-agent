package io.github.saksham023.jobagent.api;

import io.github.saksham023.jobagent.requirements.JobFamily;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One search of the public job board, already validated, and its SQL WHERE clause. Every filter is optional; filters
 * combine with AND, the values inside one filter with OR (except skills: a job must ask for all of them).
 *
 * @param companies       company slugs
 * @param families        job families; a job matches on its family or one of its secondary families
 * @param minYears        lowest experience the user has in mind ("from")
 * @param maxYears        highest ("to"); a job matches when its range OVERLAPS from..to (hard, no stretch)
 * @param includeUnstated also show jobs whose posting states no years (shown with a badge)
 * @param cities          canonical cities; "Remote" selects remote jobs
 * @param skills          canonical skills; the job must mention all of them (required or preferred)
 * @param query           keyword in the title or the company name
 * @param postedWithinDays only jobs posted in the last N days
 */
public record JobSearch(List<String> companies, List<JobFamily> families, Integer minYears, Integer maxYears,
                        boolean includeUnstated, List<String> cities, List<String> skills, String query,
                        Integer postedWithinDays, Sort sort, int page, int size) {

    public enum Sort { NEWEST, COMPANY, EXPERIENCE }

    public static final String REMOTE = "Remote";
    /** Page size for an ordinary visitor; a caller with the API key may ask for up to MAX_TRUSTED_SIZE. */
    static final int MAX_SIZE = 60;
    static final int MAX_TRUSTED_SIZE = 1000;
    /** Limits for an ordinary visitor of the public API: no requests built to be expensive (thousands of values, a huge offset). */
    static final int MAX_VALUES = 50;
    static final int MAX_QUERY_LENGTH = 100;
    static final int MAX_PAGE = 1000;

    /** The SQL filter and its named parameters. */
    public record Where(String sql, Map<String, Object> params) {
    }

    public JobSearch {
        companies = clean(companies);
        families = families == null ? List.of() : List.copyOf(families);
        cities = clean(cities);
        skills = clean(skills);
        query = query == null || query.isBlank() ? null : query.strip();
        sort = sort == null ? Sort.NEWEST : sort;
        if (minYears != null && (minYears < 0 || minYears > 50) || maxYears != null && (maxYears < 0 || maxYears > 50)) {
            throw new IllegalArgumentException("Experience must be between 0 and 50 years");
        }
        if (minYears != null && maxYears != null && minYears > maxYears) {
            throw new IllegalArgumentException("Experience 'from' must not be greater than 'to'");
        }
        if (postedWithinDays != null && (postedWithinDays < 1 || postedWithinDays > 365)) {
            throw new IllegalArgumentException("postedWithinDays must be between 1 and 365");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        size = Math.clamp(size, 1, MAX_TRUSTED_SIZE);
    }

    /**
     * The limits for an ordinary visitor (a caller with the API key skips this call): bounded lists, keyword, page and
     * page size, so nobody can make one request expensive.
     */
    public JobSearch checkedForVisitor() {
        if (page > MAX_PAGE) {
            throw new IllegalArgumentException("page must be between 0 and " + MAX_PAGE);
        }
        if (companies.size() > MAX_VALUES || cities.size() > MAX_VALUES || skills.size() > MAX_VALUES) {
            throw new IllegalArgumentException("at most " + MAX_VALUES + " companies, cities or skills per search");
        }
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("the keyword must not be longer than " + MAX_QUERY_LENGTH + " characters");
        }
        return size <= MAX_SIZE ? this : new JobSearch(companies, families, minYears, maxYears, includeUnstated, cities,
                skills, query, postedWithinDays, sort, page, MAX_SIZE);
    }

    /** "years stated" = the posting says it (rules or the model with a quote); LOW is only a guess from the title. */
    static final String STATED = "(r.years_confidence IN ('HIGH', 'MEDIUM') AND r.min_years IS NOT NULL)";

    /** The WHERE clause for open jobs in the given country that pass every filter. */
    public Where where(String country) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        conditions.add("j.closed_at IS NULL AND j.country_codes @> ARRAY[CAST(:country AS text)]");
        params.put("country", country);

        if (!companies.isEmpty()) {
            conditions.add("c.slug = ANY(CAST(:companies AS text[]))");
            params.put("companies", companies.toArray(String[]::new));
        }
        if (!families.isEmpty()) {
            conditions.add("(r.family = ANY(CAST(:families AS text[])) OR r.secondary_families && CAST(:families AS text[]))");
            params.put("families", families.stream().map(Enum::name).toArray(String[]::new));
        }
        if (minYears != null || maxYears != null) {
            List<String> overlap = new ArrayList<>(List.of(STATED));
            if (maxYears != null) {
                overlap.add("r.min_years <= :maxYears");             // the job does not ask for more than "to"
                params.put("maxYears", maxYears);
            }
            if (minYears != null) {
                overlap.add("coalesce(r.max_years, 99) >= :minYears"); // ...and its range reaches "from" ("3+" is open)
                params.put("minYears", minYears);
            }
            String stated = "(" + String.join(" AND ", overlap) + ")";
            conditions.add(includeUnstated ? "(" + stated + " OR NOT " + STATED + ")" : stated);
        }
        List<String> places = cities.stream().filter(c -> !c.equalsIgnoreCase(REMOTE)).toList();
        boolean remote = places.size() < cities.size();
        if (!places.isEmpty() || remote) {
            List<String> anyOf = new ArrayList<>();
            if (!places.isEmpty()) {
                anyOf.add("j.cities && CAST(:cities AS text[])");
                params.put("cities", places.toArray(String[]::new));
            }
            if (remote) {
                anyOf.add("j.remote");
            }
            conditions.add("(" + String.join(" OR ", anyOf) + ")");
        }
        if (!skills.isEmpty()) {
            conditions.add("(r.required_skills || r.preferred_skills) @> CAST(:skills AS text[])");
            params.put("skills", skills.toArray(String[]::new));
        }
        if (query != null) {
            conditions.add("(j.title ILIKE :query ESCAPE '\\' OR c.name ILIKE :query ESCAPE '\\')");
            params.put("query", "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (postedWithinDays != null) {
            conditions.add("coalesce(j.posted_at, j.first_seen_at) >= now() - make_interval(days => :days)");
            params.put("days", postedWithinDays);
        }
        return new Where(String.join("\n  AND ", conditions), params);
    }

    /** A list the UI shows counts for. */
    public enum Facet { FAMILY, COMPANY, LOCATION, SKILL }

    /**
     * This search without its own selection in one "any of" list, for that list's counts: with Amazon picked, the
     * other companies still show how many jobs they would add. Skills are "all of", so their counts keep the
     * selected skills (each count = jobs left after adding that skill).
     */
    public JobSearch without(Facet facet) {
        return new JobSearch(facet == Facet.COMPANY ? List.of() : companies, facet == Facet.FAMILY ? List.of() : families,
                minYears, maxYears, includeUnstated, facet == Facet.LOCATION ? List.of() : cities, skills, query,
                postedWithinDays, sort, 0, size);
    }

    /** ORDER BY for the chosen sort; the job id last, so pages never overlap. */
    public String orderBy() {
        return switch (sort) {
            case NEWEST -> "coalesce(j.posted_at, j.first_seen_at) DESC, j.id DESC";
            case COMPANY -> "c.name, j.title, j.id";
            case EXPERIENCE -> STATED + " DESC, r.min_years NULLS LAST, coalesce(j.posted_at, j.first_seen_at) DESC, j.id";
        };
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(v -> v != null && !v.isBlank()).map(String::strip).distinct().toList();
    }

    /** "SOFTWARE_ENGINEERING" -> SOFTWARE_ENGINEERING; unknown names are an error, not silently dropped. */
    static List<JobFamily> families(List<String> names) {
        if (names == null) {
            return List.of();
        }
        List<JobFamily> families = new ArrayList<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                try {
                    families.add(JobFamily.valueOf(name.strip().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown job family: " + name);
                }
            }
        }
        return families;
    }
}
