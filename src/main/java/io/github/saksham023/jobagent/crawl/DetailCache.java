package io.github.saksham023.jobagent.crawl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The details we already have, so a crawl does not download a job's detail again when nothing suggests it changed.
 * Platforms whose list has no description (Workday, Eightfold, Oracle, SmartRecruiters) need one detail request per
 * job; a known job's stored detail (jobs.raw) is reused when
 * - its list entry is unchanged (same fingerprint of the list's stable fields: title, locations...), and
 * - the detail is younger than jobagent.crawl.detail-max-age-days (default 7).
 * A new job, a changed list entry or an old detail means a fresh request. A job whose list fingerprint was never
 * recorded (crawled before this feature) counts as unchanged once, so the first crawl with it already saves requests.
 */
@Component
public class DetailCache {

    /** One stored job: its list fingerprint (null before this feature), when its detail was fetched, the detail. */
    public record Stored(String listHash, Instant detailFetchedAt, JsonNode detail) {
    }

    /** The reusable details of one company, loaded once per crawl. */
    public record Known(Map<String, Stored> byExternalId) {

        public static final Known NONE = new Known(Map.of());

        /** The stored detail to reuse for this job, or null when it must be fetched. */
        public JsonNode reusable(String externalId, String listHash) {
            Stored stored = externalId == null ? null : byExternalId.get(externalId);
            if (stored == null || stored.detail() == null) {
                return null;                                        // new job (or no detail kept)
            }
            boolean listUnchanged = stored.listHash() == null || stored.listHash().equals(listHash);
            return listUnchanged ? stored.detail() : null;
        }
    }

    /** Only details still fresh enough are loaded: an older one is fetched again anyway. */
    private static final String LOAD = """
            SELECT external_id, list_hash, detail_fetched_at, raw::text AS raw
            FROM jobs
            WHERE company_id = :companyId AND raw IS NOT NULL AND description IS NOT NULL
              AND detail_fetched_at > :freshSince
            """;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Duration maxAge;

    public DetailCache(JdbcClient jdbc, JsonMapper jsonMapper,
                       @Value("${jobagent.crawl.detail-max-age-days:7}") int maxAgeDays) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.maxAge = Duration.ofDays(maxAgeDays);
    }

    /** The company's jobs whose stored detail is younger than the max age, by external id. */
    public Known load(long companyId) {
        Map<String, Stored> stored = jdbc.sql(LOAD)
                .param("companyId", companyId)
                .param("freshSince", Timestamp.from(Instant.now().minus(maxAge)))
                .query((rs, rowNum) -> Map.entry(rs.getString("external_id"), new Stored(rs.getString("list_hash"),
                        rs.getTimestamp("detail_fetched_at").toInstant(), jsonMapper.readTree(rs.getString("raw")))))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
        return new Known(stored);
    }

    /**
     * SHA-256 of the given list fields, in order (a JSON node counts with its JSON text; null and missing as "").
     * Adapters pass only STABLE fields: Workday's "Posted 3 Days Ago" changes every day and would refetch everything.
     */
    public static String fingerprint(Object... parts) {
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            String value = part == null ? "" : part instanceof JsonNode node
                    ? (node.isMissingNode() || node.isNull() ? "" : node.toString()) : part.toString();
            text.append(value).append('\u001F');                   // unit separator: "ab"+"c" != "a"+"bc"
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}