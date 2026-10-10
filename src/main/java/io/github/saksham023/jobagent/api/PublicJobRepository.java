package io.github.saksham023.jobagent.api;

import io.github.saksham023.jobagent.api.JobSearch.Where;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The read side of the public job board: plain SQL over jobs, job_requirements, companies and crawl_runs. */
@Repository
public class PublicJobRepository {

    public record CompanyRow(String slug, String name, String platform, int openJobs, Instant lastCrawlAt) {
    }

    public record Count(String name, int jobs) {
    }

    public record Totals(int openJobs, int companies, int platforms, int postedThisWeek, int remoteJobs,
                         Instant lastCrawlAt) {
    }

    /** One job in a result list. */
    public record JobCard(long id, String title, String companySlug, String company, List<String> cities, boolean remote,
                          Integer minYears, Integer maxYears, boolean yearsStated, String family,
                          String specialization, List<String> skills, Instant postedAt, String url) {
    }

    /** One job in full. */
    public record JobDetail(long id, String title, String companySlug, String company, String url, String department,
                            List<String> locations, List<String> cities, boolean remote, String employmentType,
                            Instant postedAt, Integer minYears, Integer maxYears,
                            boolean yearsStated, String yearsEvidence, String family, List<String> secondaryFamilies, String specialization,
                            List<String> requiredSkills, List<String> preferredSkills, List<String> primaryLanguages,
                            String description, String linkedinCompanyId) {
    }

    private static final String OPEN = "j.closed_at IS NULL AND j.country_codes @> ARRAY[CAST(:country AS text)]";

    private static final String CARD_COLUMNS = """
            j.id, j.title, c.slug, c.name, j.cities, j.remote, r.min_years, r.max_years,
            """ + JobSearch.STATED + """
             AS years_stated, r.family, r.specialization,
            (r.required_skills || r.preferred_skills)[1 : 6] AS skills, """ + JobSearch.POSTED + """
             AS posted_at, j.url
            """;

    private final JdbcClient jdbc;

    public PublicJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- search

    public int count(JobSearch search, String country) {
        Where where = search.where(country);
        return jdbc.sql("SELECT count(*) FROM jobs j JOIN companies c ON c.id = j.company_id "
                        + "LEFT JOIN job_requirements r ON r.job_id = j.id WHERE " + where.sql())
                .params(where.params())
                .query(Integer.class)
                .single();
    }

    public List<JobCard> search(JobSearch search, String country) {
        Where where = search.where(country);
        return jdbc.sql("SELECT " + CARD_COLUMNS + " FROM jobs j JOIN companies c ON c.id = j.company_id "
                        + "LEFT JOIN job_requirements r ON r.job_id = j.id WHERE " + where.sql()
                        + " ORDER BY " + search.orderBy() + " LIMIT :limit OFFSET :offset")
                .params(where.params())
                .param("limit", search.size())
                .param("offset", search.page() * search.size())
                .query(this::card)
                .list();
    }

    /**
     * Live counts for the filter lists under the current search (see JobSearch.without). A family counts a job when it
     * is the job's family or one of its secondary families, exactly like the family filter.
     */
    public record Facets(Map<String, Integer> families, Map<String, Integer> companies, Map<String, Integer> cities,
                         int remote, List<Count> skills) {
    }

    private static final String FROM = " FROM jobs j JOIN companies c ON c.id = j.company_id "
            + "LEFT JOIN job_requirements r ON r.job_id = j.id";

    public Facets facets(JobSearch search, String country, int topSkills) {
        Where families = search.without(JobSearch.Facet.FAMILY).where(country);
        Where companies = search.without(JobSearch.Facet.COMPANY).where(country);
        Where locations = search.without(JobSearch.Facet.LOCATION).where(country);
        Where skills = search.where(country);
        return new Facets(
                countMap("SELECT f AS name, count(DISTINCT j.id) AS jobs" + FROM
                        + ", unnest(array_append(r.secondary_families, r.family)) AS f WHERE " + families.sql()
                        + " GROUP BY 1", families),
                countMap("SELECT c.slug AS name, count(*) AS jobs" + FROM + " WHERE " + companies.sql() + " GROUP BY 1",
                        companies),
                countMap("SELECT city AS name, count(DISTINCT j.id) AS jobs" + FROM + ", unnest(j.cities) AS city WHERE "
                        + locations.sql() + " GROUP BY 1", locations),
                jdbc.sql("SELECT count(*)" + FROM + " WHERE " + locations.sql() + " AND j.remote")
                        .params(locations.params()).query(Integer.class).single(),
                jdbc.sql("SELECT skill AS name, count(DISTINCT j.id) AS jobs" + FROM
                                + ", unnest(r.required_skills || r.preferred_skills) AS skill WHERE " + skills.sql()
                                + " GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT " + topSkills)
                        .params(skills.params())
                        .query((rs, n) -> new Count(rs.getString("name"), rs.getInt("jobs"))).list());
    }

    private Map<String, Integer> countMap(String sql, Where where) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.sql(sql).params(where.params()).query((rs, n) -> addCount(counts, rs.getString("name"), rs.getInt("jobs"))).list();
        return counts;
    }

    /**
     * Adds one facet count. A job a crawl saved a moment ago may not have its requirements yet, so its family comes back as NULL;
     * a null key cannot be written as JSON (the whole response would fail), and such a job has no family to count anyway.
     */
    static String addCount(Map<String, Integer> counts, String name, int jobs) {
        if (name != null) {
            counts.put(name, jobs);
        }
        return name;
    }

    public Optional<JobDetail> detail(long id) {
        return jdbc.sql("""
                        SELECT j.id, j.title, c.slug, c.name, j.url, j.department, j.locations, j.cities, j.remote,
                               coalesce(r.employment_type, j.employment_type) AS employment_type,
                               """ + JobSearch.POSTED + """
                         AS posted_at,
                               r.min_years, r.max_years,
                               """ + JobSearch.STATED + """
                         AS years_stated, r.years_evidence, r.family, r.secondary_families, r.specialization,
                               r.required_skills, r.preferred_skills, r.primary_languages, j.description,
                               c.config ->> 'linkedinCompanyId' AS linkedin_company_id
                        FROM jobs j JOIN companies c ON c.id = j.company_id
                        LEFT JOIN job_requirements r ON r.job_id = j.id
                        WHERE j.id = :id AND j.closed_at IS NULL
                        """)
                .param("id", id)
                .query((rs, n) -> new JobDetail(rs.getLong("id"), rs.getString("title"), rs.getString("slug"),
                        rs.getString("name"), rs.getString("url"), rs.getString("department"), list(rs, "locations"),
                        list(rs, "cities"), rs.getBoolean("remote"), rs.getString("employment_type"),
                        instant(rs, "posted_at"), integer(rs, "min_years"), integer(rs, "max_years"),
                        rs.getBoolean("years_stated"), rs.getString("years_evidence"), rs.getString("family"),
                        list(rs, "secondary_families"), rs.getString("specialization"), list(rs, "required_skills"),
                        list(rs, "preferred_skills"), list(rs, "primary_languages"), rs.getString("description"),
                        rs.getString("linkedin_company_id")))
                .optional();
    }

    // ---------------------------------------------------------------- filter lists

    /** Enabled companies with their open jobs, most jobs first. */
    public List<CompanyRow> companies(String country) {
        return jdbc.sql("""
                        SELECT c.slug, c.name, c.platform,
                               (SELECT count(*) FROM jobs j WHERE j.company_id = c.id AND\s""" + OPEN + """
                        ) AS open_jobs,
                               (SELECT max(r.finished_at) FROM crawl_runs r WHERE r.company_id = c.id AND r.status = 'OK')
                                   AS last_crawl
                        FROM companies c WHERE c.enabled
                        ORDER BY open_jobs DESC, c.name
                        """)
                .param("country", country)
                .query((rs, n) -> new CompanyRow(rs.getString("slug"), rs.getString("name"), rs.getString("platform"),
                        rs.getInt("open_jobs"), instant(rs, "last_crawl")))
                .list();
    }

    /** Open jobs per primary family. */
    public List<Count> families(String country) {
        return counts("SELECT r.family AS name, count(*) AS jobs FROM jobs j JOIN job_requirements r ON r.job_id = j.id "
                + "WHERE " + OPEN + " GROUP BY 1 ORDER BY 2 DESC", country);
    }

    /** The most common cities. */
    public List<Count> cities(String country, int limit) {
        return counts("SELECT city AS name, count(*) AS jobs FROM jobs j, unnest(j.cities) AS city WHERE " + OPEN
                + " GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT " + limit, country);
    }

    /** The skills open jobs mention most (required or preferred). */
    public List<Count> skills(String country, int limit) {
        return counts("SELECT skill AS name, count(DISTINCT j.id) AS jobs FROM jobs j JOIN job_requirements r ON "
                + "r.job_id = j.id, unnest(r.required_skills || r.preferred_skills) AS skill WHERE " + OPEN
                + " GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT " + limit, country);
    }

    public Totals totals(String country) {
        return jdbc.sql("""
                        SELECT count(*) AS open_jobs, count(DISTINCT j.company_id) AS companies,
                               count(DISTINCT c.platform) AS platforms,
                               count(*) FILTER (WHERE\s""" + JobSearch.POSTED + """
                         >= now() - interval '7 days') AS posted_week,
                               count(*) FILTER (WHERE j.remote) AS remote,
                               (SELECT max(finished_at) FROM crawl_runs WHERE status = 'OK') AS last_crawl
                        FROM jobs j JOIN companies c ON c.id = j.company_id
                        WHERE\s""" + OPEN)
                .param("country", country)
                .query((rs, n) -> new Totals(rs.getInt("open_jobs"), rs.getInt("companies"), rs.getInt("platforms"),
                        rs.getInt("posted_week"), rs.getInt("remote"), instant(rs, "last_crawl")))
                .single();
    }

    // ---------------------------------------------------------------- mapping

    private List<Count> counts(String sql, String country) {
        return jdbc.sql(sql).param("country", country)
                .query((rs, n) -> new Count(rs.getString("name"), rs.getInt("jobs"))).list();
    }

    private JobCard card(ResultSet rs, int rowNum) throws SQLException {
        return new JobCard(rs.getLong("id"), rs.getString("title"), rs.getString("slug"), rs.getString("name"),
                list(rs, "cities"), rs.getBoolean("remote"), integer(rs, "min_years"), integer(rs, "max_years"),
                rs.getBoolean("years_stated"), rs.getString("family"), rs.getString("specialization"),
                list(rs, "skills"), instant(rs, "posted_at"), rs.getString("url"));
    }

    private static List<String> list(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
