package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.common.ShutdownSignal;
import io.github.saksham023.jobagent.crawl.CrawlHealth.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** crawl_runs: the history of every company crawl, the health overview, and closing jobs that disappeared. */
@Repository
public class CrawlRunRepository {

    /** One finished crawl of one company, as it is stored. */
    public record CrawlRun(long companyId, String trigger, Instant startedAt, Instant finishedAt, Status status,
                           int fetched, int kept, int inserted, int updated, int unchanged, int unresolved,
                           int noDescription, int detailsFetched, int detailsReused, List<String> alerts, String error,
                           long elapsedMs, String kind) {

        public static final String FULL = "FULL";
        public static final String PEEK = "PEEK";
    }

    /** A company with its open jobs and its latest crawl (null fields when it was never crawled). */
    public record CompanyHealth(String company, String platform, boolean enabled, int openJobs, String status,
                                Instant lastCrawlAt, Instant lastGoodCrawlAt, Integer kept, Integer inserted,
                                Integer closed, List<String> alerts, String error) {
    }

    /**
     * A job is missing when it was last seen before the start of at least :misses OK crawls of its company, i.e. the
     * last :misses good crawls did not see it. Every job a crawl sees gets last_seen_at = that crawl's started_at.
     */
    private static final String CLOSE_MISSING = """
            UPDATE jobs j SET closed_at = now()
            WHERE j.company_id = :companyId AND j.closed_at IS NULL
              AND (SELECT count(*) FROM crawl_runs r
                   WHERE r.company_id = j.company_id AND r.status = 'OK' AND r.kind = 'FULL'
                     AND r.started_at > j.last_seen_at) >= :misses
            """;

    private static final String HEALTH = """
            SELECT c.slug, c.platform, c.enabled,
                   (SELECT count(*) FROM jobs j WHERE j.company_id = c.id AND j.closed_at IS NULL) AS open_jobs,
                   r.status, r.started_at, r.kept, r.inserted, r.closed, r.alerts, r.error,
                   (SELECT max(o.started_at) FROM crawl_runs o WHERE o.company_id = c.id AND o.status = 'OK' AND o.kind = 'FULL') AS last_ok
            FROM companies c
            LEFT JOIN LATERAL (SELECT * FROM crawl_runs r WHERE r.company_id = c.id AND r.kind = 'FULL'
                               ORDER BY r.started_at DESC LIMIT 1) r ON true
            ORDER BY (r.status IS DISTINCT FROM 'OK' OR cardinality(r.alerts) > 0) DESC, c.slug
            """;

    private final JdbcClient jdbc;

    public CrawlRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the new run's id */
    public long insert(CrawlRun run) {
        return jdbc.sql("""
                        INSERT INTO crawl_runs (company_id, trigger, started_at, finished_at, status, fetched, kept, inserted,
                                                updated, unchanged, unresolved, no_description, details_fetched,
                                                details_reused, alerts, error, elapsed_ms, kind)
                        VALUES (:companyId, :trigger, :startedAt, :finishedAt, :status, :fetched, :kept, :inserted,
                                :updated, :unchanged, :unresolved, :noDescription, :detailsFetched, :detailsReused,
                                :alerts, :error, :elapsedMs, :kind)
                        RETURNING id
                        """)
                .param("companyId", run.companyId())
                .param("trigger", run.trigger())
                .param("startedAt", Timestamp.from(run.startedAt()))
                .param("finishedAt", Timestamp.from(run.finishedAt()))
                .param("status", run.status().name())
                .param("fetched", run.fetched())
                .param("kept", run.kept())
                .param("inserted", run.inserted())
                .param("updated", run.updated())
                .param("unchanged", run.unchanged())
                .param("unresolved", run.unresolved())
                .param("noDescription", run.noDescription())
                .param("detailsFetched", run.detailsFetched())
                .param("detailsReused", run.detailsReused())
                .param("alerts", run.alerts().toArray(String[]::new))
                .param("error", run.error())
                .param("elapsedMs", run.elapsedMs())
                .param("kind", run.kind())
                .query(Long.class)
                .single();
    }

    /**
     * When each company's last crawl started (any status), by company id. A crawl that was interrupted by a shutdown
     * (a deploy) does not count: the company should be crawled again at the next run, not a day later.
     */
    public Map<Long, Instant> lastCrawlStarts() {
        return lastStarts(CrawlRun.FULL);
    }

    /** When each company's last quick check for new jobs (PEEK) started; interrupted ones do not count. */
    public Map<Long, Instant> lastPeekStarts() {
        return lastStarts(CrawlRun.PEEK);
    }

    private Map<Long, Instant> lastStarts(String kind) {
        Map<Long, Instant> starts = new HashMap<>();
        jdbc.sql("SELECT company_id, max(started_at) AS last FROM crawl_runs WHERE kind = :kind "
                        + "AND (error IS NULL OR position(:interrupted IN error) = 0) GROUP BY company_id")
                .param("kind", kind)
                .param("interrupted", ShutdownSignal.INTERRUPTED)
                .query((rs, n) -> starts.put(rs.getLong("company_id"), rs.getTimestamp("last").toInstant())).list();
        return starts;
    }

    /** How one finished crawl ended, for deciding on a retry. */
    public record RunState(Status status, Instant finishedAt) {
    }

    /**
     * Each company's latest finished crawls, newest first (at most :limit each). Crawls interrupted by a shutdown are left
     * out, like in lastCrawlStarts: they say nothing about the site.
     */
    public Map<Long, List<RunState>> recentRunStates(int limit) {
        Map<Long, List<RunState>> states = new HashMap<>();
        jdbc.sql("""
                        SELECT company_id, status, finished_at FROM (
                            SELECT company_id, status, finished_at,
                                   row_number() OVER (PARTITION BY company_id ORDER BY started_at DESC) AS rn
                            FROM crawl_runs WHERE kind = 'FULL' AND (error IS NULL OR position(:interrupted IN error) = 0)) t
                        WHERE rn <= :limit ORDER BY company_id, rn
                        """)
                .param("interrupted", ShutdownSignal.INTERRUPTED)
                .param("limit", limit)
                .query((rs, n) -> states.computeIfAbsent(rs.getLong("company_id"), k -> new java.util.ArrayList<>())
                        .add(new RunState(Status.valueOf(rs.getString("status")), rs.getTimestamp("finished_at").toInstant())))
                .list();
        return states;
    }

    /** Kept counts of the company's latest OK crawls, newest first. */
    public List<Integer> recentOkKept(long companyId, int limit) {
        return jdbc.sql("SELECT kept FROM crawl_runs WHERE company_id = :companyId AND status = 'OK' AND kind = 'FULL' "
                        + "ORDER BY started_at DESC LIMIT :limit")
                .param("companyId", companyId)
                .param("limit", limit)
                .query(Integer.class)
                .list();
    }

    /** Closes the company's jobs the last :misses OK crawls did not see; records the count on the run. */
    public int closeMissing(long companyId, int misses, long runId) {
        int closed = jdbc.sql(CLOSE_MISSING)
                .param("companyId", companyId)
                .param("misses", misses)
                .update();
        jdbc.sql("UPDATE crawl_runs SET closed = :closed WHERE id = :id").param("closed", closed).param("id", runId).update();
        return closed;
    }

    /** Every company, the ones that need a look (not OK, or with alerts) first. */
    public List<CompanyHealth> health() {
        return jdbc.sql(HEALTH).query(this::mapHealth).list();
    }

    private CompanyHealth mapHealth(ResultSet rs, int rowNum) throws SQLException {
        Array alerts = rs.getArray("alerts");
        return new CompanyHealth(rs.getString("slug"), rs.getString("platform"), rs.getBoolean("enabled"),
                rs.getInt("open_jobs"), rs.getString("status"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("last_ok")), (Integer) rs.getObject("kept"), (Integer) rs.getObject("inserted"),
                (Integer) rs.getObject("closed"), alerts == null ? List.of() : List.of((String[]) alerts.getArray()),
                rs.getString("error"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}