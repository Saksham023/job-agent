package io.github.saksham023.jobagent.job;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Writes crawled jobs into the `jobs` table.
 * One upsert per job: insert if new, otherwise refresh it, bump last_seen_at, and bump content_changed_at only
 * when the content hash differs. The outcome tells the crawl what happened.
 */
@Repository
public class JobRepository {

    public enum UpsertOutcome {
        INSERTED,
        UPDATED,
        UNCHANGED
    }

    private static final String UPSERT = """
            INSERT INTO jobs (company_id, external_id, title, department, function, locations, cities, country_codes, places,
                              remote, employment_type, url, description, posted_at, source_updated_at,
                              content_hash, raw, first_seen_at, last_seen_at, content_changed_at, closed_at,
                              list_hash, detail_fetched_at)
            VALUES (:companyId, :externalId, :title, :department, :function, :locations, :cities, :countryCodes,
                    CAST(:places AS jsonb), :remote, :employmentType, :url, :description, :postedAt,
                    :sourceUpdatedAt, :contentHash, CAST(:raw AS jsonb), :seenAt, :seenAt, :seenAt, NULL,
                    :listHash, CASE WHEN CAST(:detailFetched AS boolean) THEN CAST(:seenAt AS timestamptz) END)
            ON CONFLICT (company_id, external_id) DO UPDATE SET
                title              = EXCLUDED.title,
                department         = EXCLUDED.department,
                function           = EXCLUDED.function,
                locations          = EXCLUDED.locations,
                cities             = EXCLUDED.cities,
                country_codes      = EXCLUDED.country_codes,
                places             = EXCLUDED.places,
                remote             = EXCLUDED.remote,
                employment_type    = EXCLUDED.employment_type,
                url                = EXCLUDED.url,
                description        = EXCLUDED.description,
                posted_at          = EXCLUDED.posted_at,
                source_updated_at  = EXCLUDED.source_updated_at,
                raw                = EXCLUDED.raw,
                last_seen_at       = EXCLUDED.last_seen_at,
                content_changed_at = CASE
                                         WHEN jobs.content_hash IS DISTINCT FROM EXCLUDED.content_hash
                                         THEN EXCLUDED.last_seen_at
                                         ELSE jobs.content_changed_at
                                     END,
                content_hash       = EXCLUDED.content_hash,
                closed_at          = NULL,
                list_hash          = coalesce(EXCLUDED.list_hash, jobs.list_hash),
                detail_fetched_at  = coalesce(EXCLUDED.detail_fetched_at, jobs.detail_fetched_at)
            RETURNING first_seen_at = :seenAt AS inserted,
                      content_changed_at = :seenAt AS changed
            """;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public JobRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Inserts or refreshes one job.
     *
     * @param seenAt the crawl's timestamp, the same for every job of one crawl
     */
    public UpsertOutcome upsert(NormalizedJob job, Instant seenAt) {
        return jdbc.sql(UPSERT)
                .param("companyId", job.companyId())
                .param("externalId", job.externalId())
                .param("title", job.title())
                .param("department", job.department())
                .param("function", job.function())
                .param("locations", job.locations().toArray(String[]::new))
                .param("cities", job.cities().toArray(String[]::new))
                .param("countryCodes", job.countryCodes().toArray(String[]::new))
                .param("places", jsonMapper.writeValueAsString(job.places()))
                .param("remote", job.remote())
                .param("employmentType", job.employmentType())
                .param("url", job.url())
                .param("description", job.description())
                .param("postedAt", toOffset(job.postedAt()))
                .param("sourceUpdatedAt", toOffset(job.sourceUpdatedAt()))
                .param("contentHash", job.contentHash())
                .param("raw", job.raw() == null ? null : jsonMapper.writeValueAsString(job.raw()))
                .param("seenAt", toOffset(seenAt))
                .param("listHash", job.listHash())
                .param("detailFetched", job.detailFetched())
                .query((rs, rowNum) -> {
                    if (rs.getBoolean("inserted")) {
                        return UpsertOutcome.INSERTED;
                    }
                    return rs.getBoolean("changed") ? UpsertOutcome.UPDATED : UpsertOutcome.UNCHANGED;
                })
                .single();
    }

    /** The Postgres driver accepts OffsetDateTime for timestamptz, not Instant. */
    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}