package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.GapFiller.Fill;
import io.github.saksham023.jobagent.requirements.GapFiller.Gaps;
import io.github.saksham023.jobagent.requirements.GapFiller.Result;
import io.github.saksham023.jobagent.requirements.RequirementsRepository.JobText;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

/**
 * job_gap_fills: which jobs still have gaps, the stored model answers, and writing the accepted values into
 * job_requirements. A fill only ever writes into a field that is still a gap (the WHERE clauses), so a rules
 * result with HIGH or MEDIUM confidence is never overwritten.
 */
@Repository
public class GapFillRepository {

    /** A job to send to the model, with the content hash it is asked for. */
    public record GapJob(JobText job, String contentHash, Gaps gaps) {
    }

    /**
     * Open jobs whose family or a secondary family is in scope, that have a gap and no stored answer for this
     * version of the posting. A newer prompt version does not re-ask: delete the rows to ask again.
     */
    private static final String SELECT_JOBS_WITH_GAPS = """
            SELECT j.id, j.title, j.department, j.function, j.description, j.employment_type,
                   c.name AS company_name, j.content_hash,
                   r.years_confidence IN ('NONE', 'LOW') AS years_gap,
                   r.family = 'UNCLASSIFIED' AS family_gap,
                   cardinality(r.primary_languages) = 0 AS languages_gap
            FROM jobs j
            JOIN companies c ON c.id = j.company_id
            JOIN job_requirements r ON r.job_id = j.id
            LEFT JOIN job_gap_fills f ON f.job_id = j.id AND f.content_hash = j.content_hash
            WHERE j.closed_at IS NULL
              AND j.country_codes @> ARRAY[CAST(:country AS text)]
              AND f.job_id IS NULL
              AND (r.family = ANY(:families) OR r.secondary_families && CAST(:families AS text[]))
              AND (r.years_confidence IN ('NONE', 'LOW') OR r.family = 'UNCLASSIFIED'
                   OR cardinality(r.primary_languages) = 0)
            ORDER BY j.id
            """;

    private static final String SAVE = """
            INSERT INTO job_gap_fills (job_id, content_hash, prompt_version, model, answer, accepted, rejected,
                                       cost_usd, millis, filled_at)
            VALUES (:jobId, :contentHash, :promptVersion, :model, CAST(:answer AS jsonb), CAST(:accepted AS jsonb),
                    CAST(:rejected AS jsonb), :costUsd, :millis, now())
            ON CONFLICT (job_id) DO UPDATE SET
                content_hash   = EXCLUDED.content_hash,
                prompt_version = EXCLUDED.prompt_version,
                model          = EXCLUDED.model,
                answer         = EXCLUDED.answer,
                accepted       = EXCLUDED.accepted,
                rejected       = EXCLUDED.rejected,
                cost_usd       = EXCLUDED.cost_usd,
                millis         = EXCLUDED.millis,
                filled_at      = EXCLUDED.filled_at
            """;

    private static final String APPLY_YEARS = """
            UPDATE job_requirements
            SET min_years = :minYears, max_years = :maxYears, years_evidence = :evidence,
                years_confidence = 'MEDIUM', years_source = :source
            WHERE job_id = :jobId AND years_confidence IN ('NONE', 'LOW')
            """;

    /** The new family leaves the secondary families (a job's family is never also its secondary). */
    private static final String APPLY_FAMILY = """
            UPDATE job_requirements
            SET family = :family,
                specialization = coalesce(:specialization, specialization),
                secondary_families = array_remove(secondary_families, CAST(:family AS text)),
                family_reasons = family_reasons || jsonb_build_array(CAST(:reason AS text)),
                family_source = :source
            WHERE job_id = :jobId AND family = 'UNCLASSIFIED'
            """;

    private static final String APPLY_LANGUAGES = """
            UPDATE job_requirements
            SET primary_languages = :languages, languages_source = :source
            WHERE job_id = :jobId AND cardinality(primary_languages) = 0
            """;

    /** The stored fill for the job's current content, if any. */
    private static final String SELECT_CURRENT_FILL = """
            SELECT f.model, f.accepted::text AS accepted
            FROM job_gap_fills f
            JOIN jobs j ON j.id = f.job_id AND j.content_hash = f.content_hash
            WHERE f.job_id = :jobId
            """;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public GapFillRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /** @param families the job families in scope (names; include UNCLASSIFIED to ask about unclassified jobs) */
    public List<GapJob> findJobsWithGaps(String country, String[] families) {
        return jdbc.sql(SELECT_JOBS_WITH_GAPS)
                .param("country", country)
                .param("families", families)
                .query((rs, rowNum) -> new GapJob(
                        new JobText(rs.getLong("id"), rs.getString("title"), rs.getString("department"),
                                rs.getString("function"), rs.getString("description"),
                                rs.getString("employment_type"), rs.getString("company_name")),
                        rs.getString("content_hash"),
                        new Gaps(rs.getBoolean("years_gap"), rs.getBoolean("family_gap"), rs.getBoolean("languages_gap"))))
                .list();
    }

    public void save(GapJob gapJob, String promptVersion, String model, Result result, long millis) {
        jdbc.sql(SAVE)
                .param("jobId", gapJob.job().jobId())
                .param("contentHash", gapJob.contentHash())
                .param("promptVersion", promptVersion)
                .param("model", model)
                .param("answer", jsonMapper.writeValueAsString(result.answer()))
                .param("accepted", jsonMapper.writeValueAsString(result.accepted()))
                .param("rejected", jsonMapper.writeValueAsString(result.rejected()))
                .param("costUsd", result.costUsd())
                .param("millis", (int) millis)
                .update();
    }

    /** Writes the filled fields into job_requirements; fields that are no longer gaps are left alone. */
    public void apply(long jobId, Fill fill, String model) {
        String source = source(model);
        if (fill.hasYears()) {
            jdbc.sql(APPLY_YEARS)
                    .param("jobId", jobId)
                    .param("minYears", fill.minYears())
                    .param("maxYears", fill.maxYears())
                    .param("evidence", fill.yearsEvidence())
                    .param("source", source)
                    .update();
        }
        if (fill.hasFamily()) {
            jdbc.sql(APPLY_FAMILY)
                    .param("jobId", jobId)
                    .param("family", fill.family().name())
                    .param("specialization", fill.specialization() == null ? null : fill.specialization().name())
                    .param("reason", source + ": " + fill.familyReason())
                    .param("source", source)
                    .update();
        }
        if (fill.hasLanguages()) {
            jdbc.sql(APPLY_LANGUAGES)
                    .param("jobId", jobId)
                    .param("languages", fill.languages().toArray(String[]::new))
                    .param("source", source)
                    .update();
        }
    }

    /** After a rules re-extraction: applies the stored fill again when the posting has not changed since. */
    public void reapply(long jobId) {
        record Stored(String model, String accepted) {
        }
        Optional<Stored> stored = jdbc.sql(SELECT_CURRENT_FILL)
                .param("jobId", jobId)
                .query((rs, rowNum) -> new Stored(rs.getString("model"), rs.getString("accepted")))
                .optional();
        stored.ifPresent(s -> apply(jobId, jsonMapper.readValue(s.accepted(), Fill.class), s.model()));
    }

    static String source(String model) {
        return "claude:" + model;
    }
}
