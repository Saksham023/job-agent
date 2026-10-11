package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.api.JobSearch;
import io.github.saksham023.jobagent.api.PublicJobRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** The job alerts' queue (alert_job_queue) and matches (alert_matches). */
@Repository
public class AlertMatchRepository {

    /**
     * Which queued jobs are ready: their requirements are extracted (family, years and skills exist). Rows locked by
     * another matcher run are skipped, so parallel runs split the work instead of doing it twice.
     */
    private static final String CLAIM_JOBS = """
            SELECT q.job_id
            FROM alert_job_queue q
            JOIN job_requirements r ON r.job_id = q.job_id
            ORDER BY q.job_id
            LIMIT :limit
            FOR UPDATE OF q SKIP LOCKED
            """;

    /**
     * For each claimed job, the users whose alert fits it: the website's own filter rules (JobSearch), written the other
     * way round. Every condition below mirrors one filter of the website:
     * families   a job counts under its main family and its extra tags; any of the user's families
     * years      no range = any; a STATED job's range must overlap ("6+" is open-ended); an unstated job passes when
     *            the user includes unstated jobs
     * skills     ALL of the user's skills among the job's required + preferred skills
     * cities     any of them, or 'Remote' for remote jobs
     * companies  any of them
     * Only opted-in users with an active Telegram link; a pair already matched is skipped (nobody gets a job twice).
     */
    static final String MATCH = """
            INSERT INTO alert_matches (user_id, job_id)
            SELECT p.user_id, j.id
            FROM jobs j
            JOIN companies c ON c.id = j.company_id
            JOIN job_requirements r ON r.job_id = j.id
            JOIN alert_prefs p ON p.enabled
            JOIN telegram_links t ON t.user_id = p.user_id AND t.active
            WHERE j.id = ANY(:jobIds)
              AND j.closed_at IS NULL AND j.country_codes @> ARRAY[CAST(:country AS text)]
            """ + "  AND " + Conditions.ALL + """
            ON CONFLICT (user_id, job_id) DO NOTHING
            """;

    /** The alert conditions on their own (p = alert_prefs, j = jobs, r = job_requirements, c = companies). */
    static final class Conditions {

        static final String FAMILIES = "(p.families = '{}' OR p.families && array_append(r.secondary_families, r.family))";

        static final String YEARS = "((p.min_years IS NULL AND p.max_years IS NULL)"
                + " OR (" + JobSearch.STATED + " AND (p.max_years IS NULL OR r.min_years <= p.max_years)"
                + " AND (p.min_years IS NULL OR coalesce(r.max_years, 99) >= p.min_years))"
                + " OR (NOT " + JobSearch.STATED + " AND p.include_unstated))";

        static final String SKILLS = "p.skills <@ (r.required_skills || r.preferred_skills)";

        static final String CITIES = "(p.cities = '{}' OR j.cities && p.cities OR (j.remote AND '" + JobSearch.REMOTE
                + "' = ANY(p.cities)))";

        static final String COMPANIES = "(p.companies = '{}' OR c.slug = ANY(p.companies))";

        static final String ALL = FAMILIES + "\n  AND " + YEARS + "\n  AND " + SKILLS + "\n  AND " + CITIES
                + "\n  AND " + COMPANIES + "\n";

        private Conditions() {
        }
    }

    /** One job in a message, as the user sees it. */
    public record MatchedJob(long jobId, String title, String company, List<String> cities, boolean remote,
                             Integer minYears, Integer maxYears, boolean yearsStated, String url, boolean open) {
    }

    private final JdbcClient jdbc;

    public AlertMatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- matching

    /** Locks up to `limit` ready queued jobs for the caller's transaction. */
    public List<Long> claimQueuedJobs(int limit) {
        return jdbc.sql(CLAIM_JOBS).param("limit", limit).query(Long.class).list();
    }

    /** Writes the matches of these jobs; returns how many new (user, job) pairs were added. */
    public int match(List<Long> jobIds, String country) {
        return jdbc.sql(MATCH)
                .param("jobIds", jobIds.toArray(Long[]::new))
                .param("country", country)
                .update();
    }

    public void dequeue(List<Long> jobIds) {
        jdbc.sql("DELETE FROM alert_job_queue WHERE job_id = ANY(:jobIds)").param("jobIds", jobIds.toArray(Long[]::new)).update();
    }

    /** Queue rows whose job never got its requirements (extraction failed): dropped after a day. */
    public int dropStaleQueueRows(Duration age) {
        return jdbc.sql("DELETE FROM alert_job_queue WHERE queued_at < :before")
                .param("before", Timestamp.from(Instant.now().minus(age)))
                .update();
    }

    // ---------------------------------------------------------------- sending

    /** Matches stuck in SENDING (the app stopped mid-send) go back to PENDING. */
    public int releaseStuck(Duration timeout) {
        return jdbc.sql("""
                        UPDATE alert_matches SET status = 'PENDING', claimed_at = NULL
                        WHERE status = 'SENDING' AND claimed_at < :before
                        """)
                .param("before", Timestamp.from(Instant.now().minus(timeout)))
                .update();
    }

    /**
     * One user's due matches, marked SENDING (call inside a transaction): picks the user with the oldest due match,
     * skipping users another sender is working on.
     */
    public Optional<Claimed> claimNextUser() {
        Optional<Long> userId = jdbc.sql("""
                        SELECT user_id FROM alert_matches
                        WHERE status = 'PENDING' AND next_attempt_at <= now()
                        ORDER BY matched_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .query(Long.class).optional();
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        List<Long> jobIds = jdbc.sql("""
                        UPDATE alert_matches SET status = 'SENDING', claimed_at = now()
                        WHERE user_id = :userId AND status = 'PENDING' AND next_attempt_at <= now()
                        RETURNING job_id
                        """)
                .param("userId", userId.get())
                .query(Long.class).list();
        return jobIds.isEmpty() ? Optional.empty() : Optional.of(new Claimed(userId.get(), jobIds));
    }

    public record Claimed(long userId, List<Long> jobIds) {
    }

    /** The claimed jobs as the message shows them, oldest match first. */
    public List<MatchedJob> jobs(long userId, List<Long> jobIds, String country) {
        return jdbc.sql("""
                        SELECT j.id, j.title, c.name, """ + PublicJobRepository.LOCAL_CITIES + """
                         AS cities, j.remote, r.min_years, r.max_years,
                               coalesce(""" + JobSearch.STATED + """
                        , false) AS years_stated, j.url, j.closed_at IS NULL AS open
                        FROM alert_matches m
                        JOIN jobs j ON j.id = m.job_id
                        JOIN companies c ON c.id = j.company_id
                        LEFT JOIN job_requirements r ON r.job_id = j.id
                        WHERE m.user_id = :userId AND m.job_id = ANY(:jobIds)
                        ORDER BY m.matched_at, j.id
                        """)
                .param("userId", userId)
                .param("jobIds", jobIds.toArray(Long[]::new))
                .param("country", country)
                .query((rs, n) -> new MatchedJob(rs.getLong("id"), rs.getString("title"), rs.getString("name"),
                        list(rs.getArray("cities")), rs.getBoolean("remote"), rs.getObject("min_years", Integer.class),
                        rs.getObject("max_years", Integer.class), rs.getBoolean("years_stated"), rs.getString("url"),
                        rs.getBoolean("open")))
                .list();
    }

    /** Final state for these matches: SENT, SKIPPED (closed job, chat gone) or FAILED. */
    public void finish(long userId, List<Long> jobIds, String status, String error) {
        jdbc.sql("""
                        UPDATE alert_matches
                        SET status = :status, sent_at = CASE WHEN :status = 'SENT' THEN now() END, last_error = :error,
                            claimed_at = NULL
                        WHERE user_id = :userId AND job_id = ANY(:jobIds)
                        """)
                .param("status", status)
                .param("error", error)
                .param("userId", userId)
                .param("jobIds", jobIds.toArray(Long[]::new))
                .update();
    }

    /** A failed send: back to PENDING after `delay`, or FAILED once maxAttempts is reached. */
    public void retryLater(long userId, List<Long> jobIds, Duration delay, int maxAttempts, String error) {
        jdbc.sql("""
                        UPDATE alert_matches
                        SET attempts = attempts + 1, last_error = :error, claimed_at = NULL,
                            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                            next_attempt_at = :next
                        WHERE user_id = :userId AND job_id = ANY(:jobIds)
                        """)
                .param("error", error)
                .param("maxAttempts", maxAttempts)
                .param("next", Timestamp.from(Instant.now().plus(delay)))
                .param("userId", userId)
                .param("jobIds", jobIds.toArray(Long[]::new))
                .update();
    }

    /** How many times these matches were already tried (the highest), for the backoff. */
    public int attempts(long userId, List<Long> jobIds) {
        return jdbc.sql("SELECT coalesce(max(attempts), 0) FROM alert_matches WHERE user_id = :userId AND job_id = ANY(:jobIds)")
                .param("userId", userId)
                .param("jobIds", jobIds.toArray(Long[]::new))
                .query(Integer.class).single();
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
