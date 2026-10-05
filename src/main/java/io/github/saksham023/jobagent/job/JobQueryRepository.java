package io.github.saksham023.jobagent.job;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Read-only views of stored jobs for the API and MCP tools (JobRepository is the crawl's write side).
 */
@Repository
public class JobQueryRepository {

    /**
     * Everything about one job: the posting, where and when, and what our extractors found in it.
     * filledByModel names the fields a model filled because the rules found nothing ("years", "family",
     * "primaryLanguages"); every other field comes from the rules.
     */
    public record JobDetails(long jobId, String company, String title, String url, String department,
                             List<String> locations, List<String> cities, boolean remote, String employmentType,
                             Instant postedAt, Instant firstSeenAt, boolean open,
                             Integer minYears, Integer maxYears, String yearsEvidence, String family,
                             List<String> secondaryFamilies, String specialization, List<String> requiredSkills, List<String> preferredSkills,
                             List<String> primaryLanguages, List<String> filledByModel, String description) {

        public JobDetails withDescription(String newDescription) {
            return new JobDetails(jobId, company, title, url, department, locations, cities, remote, employmentType,
                    postedAt, firstSeenAt, open, minYears, maxYears, yearsEvidence, family, secondaryFamilies,
                    specialization, requiredSkills, preferredSkills, primaryLanguages, filledByModel, newDescription);
        }
    }

    private static final String SELECT_DETAILS = """
            SELECT j.id, c.name AS company, j.title, j.url, j.department, j.locations, j.cities, j.remote,
                   coalesce(r.employment_type, j.employment_type) AS employment_type, j.posted_at,
                   j.first_seen_at, j.closed_at IS NULL AS open,
                   r.min_years, r.max_years, r.years_evidence, r.family, r.secondary_families, r.specialization,
                   r.required_skills, r.preferred_skills, r.primary_languages,
                   array_remove(ARRAY[CASE WHEN r.years_source <> 'rules' THEN 'years' END,
                                      CASE WHEN r.family_source <> 'rules' THEN 'family' END,
                                      CASE WHEN r.languages_source <> 'rules' THEN 'primaryLanguages' END], NULL)
                       AS filled_by_model,
                   j.description
            FROM jobs j
            JOIN companies c ON c.id = j.company_id
            LEFT JOIN job_requirements r ON r.job_id = j.id
            WHERE j.id = :id
            """;

    private final JdbcClient jdbc;

    public JobQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<JobDetails> findDetails(long jobId) {
        return jdbc.sql(SELECT_DETAILS)
                .param("id", jobId)
                .query(this::mapRow)
                .optional();
    }

    private JobDetails mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new JobDetails(
                rs.getLong("id"),
                rs.getString("company"),
                rs.getString("title"),
                rs.getString("url"),
                rs.getString("department"),
                strings(rs.getArray("locations")),
                strings(rs.getArray("cities")),
                rs.getBoolean("remote"),
                rs.getString("employment_type"),
                instant(rs.getTimestamp("posted_at")),
                instant(rs.getTimestamp("first_seen_at")),
                rs.getBoolean("open"),
                rs.getObject("min_years", Integer.class),
                rs.getObject("max_years", Integer.class),
                rs.getString("years_evidence"),
                rs.getString("family"),
                strings(rs.getArray("secondary_families")),
                rs.getString("specialization"),
                strings(rs.getArray("required_skills")),
                strings(rs.getArray("preferred_skills")),
                strings(rs.getArray("primary_languages")),
                strings(rs.getArray("filled_by_model")),
                rs.getString("description"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}