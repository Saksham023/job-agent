package io.github.saksham023.jobagent.matching;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * Applies the hard filters of matching in SQL and returns the eligible jobs with what the scorer needs.
 */
@Repository
public class MatchCandidateRepository {

    /** One eligible job: the fields shown to the user plus the extracted requirements. */
    public record Candidate(long jobId, String company, String title, String url, List<String> cities,
                            boolean remote, Integer minYears, Integer maxYears, String family,
                            List<String> secondaryFamilies, List<String> requiredSkills, List<String> preferredSkills, List<String> primaryLanguages) {
    }

    /**
     * @param cities             canonical city names; empty = anywhere in the country
     * @param years              the candidate's years of experience, or null to skip the experience filter
     * @param underqualifiedBy   how many years below a job's minimum still count as eligible
     * @param overqualifiedBy    how many years above a job's maximum still count as eligible
     */
    public record Criteria(String countryCode, List<String> families, List<String> cities, boolean openToRemote,
                           Integer years, int underqualifiedBy, int overqualifiedBy) {
    }

    /**
     * Open jobs in the country whose primary OR secondary family is one asked for (recall first); in one of the cities, or remote (when acceptable), or
     * with no city at all ("India" only); and whose years range fits. Unknown years never exclude a job.
     */
    private static final String SELECT_CANDIDATES = """
            SELECT j.id, c.name AS company, j.title, j.url, j.cities, j.remote,
                   r.min_years, r.max_years, r.family, r.secondary_families, r.required_skills, r.preferred_skills, r.primary_languages
            FROM jobs j
            JOIN companies c ON c.id = j.company_id
            JOIN job_requirements r ON r.job_id = j.id
            WHERE j.closed_at IS NULL
              AND j.country_codes @> ARRAY[CAST(:country AS text)]
              AND (r.family = ANY(:families) OR r.secondary_families && CAST(:families AS text[]))
              AND (:anywhere
                   OR j.cities && CAST(:cities AS text[])
                   OR (:openToRemote AND j.remote)
                   OR cardinality(j.cities) = 0)
              AND (CAST(:years AS integer) IS NULL
                   OR ((r.min_years IS NULL OR CAST(:years AS integer) >= r.min_years - :under)
                       AND (r.max_years IS NULL OR CAST(:years AS integer) <= r.max_years + :over)))
            """;

    private final JdbcClient jdbc;

    public MatchCandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> find(Criteria criteria) {
        return jdbc.sql(SELECT_CANDIDATES)
                .param("country", criteria.countryCode())
                .param("families", criteria.families().toArray(String[]::new))
                .param("anywhere", criteria.cities().isEmpty())
                .param("cities", criteria.cities().toArray(String[]::new))
                .param("openToRemote", criteria.openToRemote())
                .param("years", criteria.years())
                .param("under", criteria.underqualifiedBy())
                .param("over", criteria.overqualifiedBy())
                .query(this::mapRow)
                .list();
    }

    private Candidate mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Candidate(
                rs.getLong("id"),
                rs.getString("company"),
                rs.getString("title"),
                rs.getString("url"),
                strings(rs.getArray("cities")),
                rs.getBoolean("remote"),
                rs.getObject("min_years", Integer.class),
                rs.getObject("max_years", Integer.class),
                rs.getString("family"),
                strings(rs.getArray("secondary_families")),
                strings(rs.getArray("required_skills")),
                strings(rs.getArray("preferred_skills")),
                strings(rs.getArray("primary_languages")));
    }

    /** A Postgres text[] as a Java list (never null). */
    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}