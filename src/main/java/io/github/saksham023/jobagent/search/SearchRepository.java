package io.github.saksham023.jobagent.search;

import io.github.saksham023.jobagent.eval.Judgment;
import io.github.saksham023.jobagent.eval.Judgment.Verdict;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The searches and judgments tables. A search's state is never kept in memory: which candidates are judged, which
 * are ready (judged APPLY, not shown yet) and the current NO streak are computed here from the two tables, with the
 * candidate list in rule-score order (unnest ... WITH ORDINALITY gives each candidate its position).
 * A judgment counts only for the job's CURRENT content (content_hash) and the search's rubric version.
 *
 * Two tiers: candidates from searches.low_priority_from on are the LOW-PRIORITY tier (likely NO: a senior title with
 * no years stated, embedded work the candidate has no C/C++ for, main languages the candidate lacks). They are judged
 * only once the main tier is done (all judged, or stopAfterNos NO in a row there), and each tier has its own NO
 * streak, so a run of NO at the end of the main tier does not keep the low-priority tier from being judged later.
 */
@Repository
public class SearchRepository {

    /** A stored search: what is needed to judge for it again. */
    public record SearchRow(UUID id, String profileId, String profileJson, String profileHash, String rubricVersion,
                            int candidates) {
    }

    /**
     * @param readyApply  judged APPLY, not shown yet
     * @param readyMaybe  judged MAYBE, not shown yet
     * @param unjudged    open candidates without a verdict (both tiers)
     * @param judged      candidates with a verdict
     * @param trailingNos the NO streak of the tier being judged (below its lowest-ranked APPLY or MAYBE)
     */
    public record Status(int readyApply, int readyMaybe, int unjudged, int judged, int trailingNos) {
    }

    /** A candidate and its rule score. */
    public record Ranked(long jobId, int score) {
    }

    /** What the tiering needs beyond the rule match: the job's specialization and main languages. */
    public record JobTraits(String specialization, List<String> primaryLanguages) {
    }

    public record StoredJudgment(Verdict verdict, String roleFit, String experienceFit, String stackFit, String reason) {
    }

    /** Every candidate of one search with its position, rule score, tier (1 main, 2 low priority), shown, verdict. */
    private static final String CANDIDATES = """
            WITH s AS (SELECT * FROM searches WHERE id = :id),
            c AS (
                SELECT u.job_id, u.score, u.pos, u.job_id = ANY(s.shown_ids) AS shown,
                       CASE WHEN u.pos >= s.low_priority_from THEN 2 ELSE 1 END AS tier,
                       j.closed_at IS NULL AS open, g.verdict
                FROM s
                CROSS JOIN LATERAL unnest(s.candidate_ids, s.candidate_scores) WITH ORDINALITY AS u(job_id, score, pos)
                JOIN jobs j ON j.id = u.job_id
                LEFT JOIN judgments g ON g.job_id = u.job_id AND g.profile_hash = s.profile_hash
                                     AND g.rubric_version = s.rubric_version AND g.content_hash = j.content_hash
            )
            """;

    /**
     * Appended to CANDIDATES where the tiers matter: per tier the open candidates still to judge (failed calls
     * excluded) and the NO streak below the tier's lowest-ranked APPLY or MAYBE; the main tier is done when nothing
     * is left in it or its streak reached :stop.
     */
    private static final String TIERS = """
            , t AS (
                SELECT tier,
                       count(*) FILTER (WHERE verdict IS NULL AND open
                                        AND NOT (job_id = ANY(CAST(:excluded AS bigint[])))) AS todo,
                       count(*) FILTER (WHERE verdict = 'NO' AND pos > coalesce(
                           (SELECT max(c2.pos) FROM c c2 WHERE c2.tier = c.tier AND c2.verdict IN ('APPLY', 'MAYBE')),
                           (SELECT min(c3.pos) - 1 FROM c c3 WHERE c3.tier = c.tier))) AS streak
                FROM c GROUP BY tier
            ),
            main AS (SELECT coalesce((SELECT todo FROM t WHERE tier = 1), 0) AS todo,
                            coalesce((SELECT streak FROM t WHERE tier = 1), 0) AS streak),
            low AS (SELECT coalesce((SELECT todo FROM t WHERE tier = 2), 0) AS todo,
                           coalesce((SELECT streak FROM t WHERE tier = 2), 0) AS streak),
            phase AS (SELECT (main.todo = 0 OR main.streak >= :stop) AS main_done FROM main)
            """;

    private static final String STATUS = CANDIDATES + TIERS + """
            SELECT count(*) FILTER (WHERE verdict = 'APPLY' AND NOT shown AND open) AS ready_apply,
                   count(*) FILTER (WHERE verdict = 'MAYBE' AND NOT shown AND open) AS ready_maybe,
                   count(*) FILTER (WHERE verdict IS NULL AND open)                 AS unjudged,
                   count(*) FILTER (WHERE verdict IS NOT NULL)                      AS judged,
                   (SELECT CASE WHEN phase.main_done THEN low.streak ELSE main.streak END
                    FROM phase, main, low)                                          AS trailing_nos
            FROM c
            """;

    private static final String READY = CANDIDATES + """
            SELECT job_id, score FROM c
            WHERE verdict = :verdict AND NOT shown AND open
            ORDER BY pos LIMIT :limit
            """;

    private static final String WITH_VERDICT = CANDIDATES + """
            SELECT job_id, score FROM c WHERE verdict = :verdict ORDER BY pos
            """;

    /** The main tier until it is done, then the low-priority tier until its own streak stops it. */
    private static final String NEXT_UNJUDGED = CANDIDATES + TIERS + """
            SELECT c.job_id FROM c, phase, low
            WHERE c.verdict IS NULL AND c.open AND NOT (c.job_id = ANY(CAST(:excluded AS bigint[])))
              AND c.pos <= :maxPosition
              AND ((c.tier = 1 AND NOT phase.main_done)
                   OR (c.tier = 2 AND phase.main_done AND low.streak < :stop))
            ORDER BY c.pos LIMIT :limit
            """;

    /** The job's content_hash is read at save time, so the verdict is tied to the text that was judged. */
    private static final String SAVE_JUDGMENT = """
            INSERT INTO judgments (profile_hash, job_id, rubric_version, content_hash, model, verdict,
                                   role_fit, experience_fit, stack_fit, reason, cost_usd, millis)
            SELECT :profileHash, j.id, :rubric, j.content_hash, :model, :verdict,
                   :roleFit, :experienceFit, :stackFit, :reason, :costUsd, :millis
            FROM jobs j WHERE j.id = :jobId
            ON CONFLICT (profile_hash, job_id, rubric_version, content_hash) DO NOTHING
            """;

    private static final String JUDGMENT = """
            SELECT g.verdict, g.role_fit, g.experience_fit, g.stack_fit, g.reason
            FROM judgments g JOIN jobs j ON j.id = g.job_id AND j.content_hash = g.content_hash
            WHERE g.profile_hash = :profileHash AND g.rubric_version = :rubric AND g.job_id = :jobId
            """;

    private final JdbcClient jdbc;

    public SearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- searches

    /**
     * @param lowPriorityFrom the 1-based position where the low-priority tier starts, or null for one tier
     * @param profileId       the saved profile the search is for
     */
    public UUID create(String profileJson, String profileHash, String rubricVersion, List<Long> candidateIds,
                       List<Integer> candidateScores, Integer lowPriorityFrom, String profileId) {
        return jdbc.sql("""
                        INSERT INTO searches (profile, profile_hash, rubric_version, candidate_ids, candidate_scores,
                                              low_priority_from, profile_id)
                        VALUES (CAST(:profile AS jsonb), :profileHash, :rubric, :ids, :scores, :lowPriorityFrom,
                                :profileId)
                        RETURNING id
                        """)
                .param("profile", profileJson)
                .param("profileHash", profileHash)
                .param("rubric", rubricVersion)
                .param("ids", candidateIds.toArray(Long[]::new))
                .param("scores", candidateScores.toArray(Integer[]::new))
                .param("lowPriorityFrom", lowPriorityFrom)
                .param("profileId", profileId)
                .query(UUID.class)
                .single();
    }

    public Optional<SearchRow> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, profile_id, profile::text AS profile, profile_hash, rubric_version, cardinality(candidate_ids) AS n
                        FROM searches WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new SearchRow(rs.getObject("id", UUID.class), rs.getString("profile_id"), rs.getString("profile"),
                        rs.getString("profile_hash"), rs.getString("rubric_version"), rs.getInt("n")))
                .optional();
    }

    /** @param stopAfterNos the per-tier NO streak that ends a tier; excluded = failed calls, not counted as to-do */
    public Status status(UUID id, int stopAfterNos, Collection<Long> excluded) {
        return jdbc.sql(STATUS)
                .param("id", id)
                .param("stop", stopAfterNos)
                .param("excluded", excluded.toArray(Long[]::new))
                .query((rs, rowNum) -> new Status(rs.getInt("ready_apply"), rs.getInt("ready_maybe"),
                        rs.getInt("unjudged"), rs.getInt("judged"), rs.getInt("trailing_nos")))
                .single();
    }

    /** Judged, not shown, open: the best-ranked first. */
    public List<Ranked> ready(UUID id, Verdict verdict, int limit) {
        return jdbc.sql(READY)
                .param("id", id)
                .param("verdict", verdict.name())
                .param("limit", limit)
                .query((rs, rowNum) -> new Ranked(rs.getLong("job_id"), rs.getInt("score")))
                .list();
    }

    /** Every candidate with this verdict, shown or not, in rank order (for the export). */
    public List<Ranked> withVerdict(UUID id, Verdict verdict) {
        return jdbc.sql(WITH_VERDICT)
                .param("id", id)
                .param("verdict", verdict.name())
                .query((rs, rowNum) -> new Ranked(rs.getLong("job_id"), rs.getInt("score")))
                .list();
    }

    /**
     * The next candidates to judge, best-ranked first, skipping the excluded ones (calls that failed): main tier
     * first, then the low-priority tier. Empty = nothing more will be judged.
     */
    public List<Long> nextUnjudged(UUID id, int limit, int stopAfterNos, Collection<Long> excluded) {
        return nextUnjudged(id, limit, stopAfterNos, excluded, Integer.MAX_VALUE);
    }

    /** As above, but only among the first `maxPosition` candidates of the list (the first answer's batch). */
    public List<Long> nextUnjudged(UUID id, int limit, int stopAfterNos, Collection<Long> excluded, int maxPosition) {
        return jdbc.sql(NEXT_UNJUDGED)
                .param("id", id)
                .param("limit", limit)
                .param("maxPosition", maxPosition)
                .param("stop", stopAfterNos)
                .param("excluded", excluded.toArray(Long[]::new))
                .query(Long.class)
                .list();
    }

    /** Specialization and main languages of the given jobs (for the tiering at search start). */
    public Map<Long, JobTraits> traits(List<Long> jobIds) {
        Map<Long, JobTraits> traits = new HashMap<>();
        jdbc.sql("SELECT job_id, specialization, primary_languages FROM job_requirements WHERE job_id = ANY(:ids)")
                .param("ids", jobIds.toArray(Long[]::new))
                .query(rs -> {
                    Array languages = rs.getArray("primary_languages");
                    traits.put(rs.getLong("job_id"), new JobTraits(rs.getString("specialization"),
                            languages == null ? List.of() : List.of((String[]) languages.getArray())));
                });
        return traits;
    }

    public void markShown(UUID id, List<Long> jobIds) {
        jdbc.sql("UPDATE searches SET shown_ids = shown_ids || CAST(:ids AS bigint[]), last_used_at = now() WHERE id = :id")
                .param("id", id)
                .param("ids", jobIds.toArray(Long[]::new))
                .update();
    }

    // ---------------------------------------------------------------- judgments

    public void saveJudgment(String profileHash, String rubricVersion, long jobId, String model, Judgment judgment,
                             double costUsd, long millis) {
        jdbc.sql(SAVE_JUDGMENT)
                .param("profileHash", profileHash)
                .param("rubric", rubricVersion)
                .param("jobId", jobId)
                .param("model", model)
                .param("verdict", judgment.verdict().name())
                .param("roleFit", judgment.roleFit() == null ? null : judgment.roleFit().name())
                .param("experienceFit", judgment.experienceFit() == null ? null : judgment.experienceFit().name())
                .param("stackFit", judgment.stackFit() == null ? null : judgment.stackFit().name())
                .param("reason", judgment.reason())
                .param("costUsd", costUsd)
                .param("millis", (int) millis)
                .update();
    }

    /** The verdict for the job's current content, if judged. */
    public Optional<StoredJudgment> judgment(String profileHash, String rubricVersion, long jobId) {
        return jdbc.sql(JUDGMENT)
                .param("profileHash", profileHash)
                .param("rubric", rubricVersion)
                .param("jobId", jobId)
                .query((rs, rowNum) -> new StoredJudgment(Verdict.valueOf(rs.getString("verdict")),
                        rs.getString("role_fit"), rs.getString("experience_fit"), rs.getString("stack_fit"),
                        rs.getString("reason")))
                .optional();
    }
}