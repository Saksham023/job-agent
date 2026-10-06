package io.github.saksham023.jobagent.crawl;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A job exactly as one platform reported it, mapped into a common shape.
 * Adapters produce these; the normalizer turns them into stored jobs (location parsing, HTML to text,
 * content hash). No business rules here.
 *
 * @param externalId  the platform's own job id (unique within one company's board)
 * @param function    a structured job function when the platform has one (SmartRecruiters "Engineering"), else null
 * @param locations   the job's locations as reported: free text and/or structured parts (see RawLocation)
 * @param description as the platform sent it; may contain HTML (the normalizer converts it)
 * @param raw         the platform's original JSON for this job, kept for re-parsing and debugging
 * @param listHash    fingerprint of the list entry's stable fields (detail platforms only, else null; see DetailCache)
 * @param detail      whether the description came from a detail request made now, from a stored detail, or neither
 */
public record RawJob(
        String externalId,
        String title,
        String department,
        String function,
        List<RawLocation> locations,
        String employmentType,
        String url,
        String description,
        Instant postedAt,
        Instant sourceUpdatedAt,
        JsonNode raw,
        String listHash,
        DetailSource detail
) {

    /** Where a job's detail (its description) came from in this crawl. */
    public enum DetailSource {
        /** the list carries everything, or the platform gives no detail for this job */
        NONE,
        /** downloaded in this crawl */
        FETCHED,
        /** the stored detail, reused because nothing suggests it changed */
        REUSED
    }

    /** A job from a platform without separate detail requests. */
    public RawJob(String externalId, String title, String department, String function, List<RawLocation> locations,
                  String employmentType, String url, String description, Instant postedAt, Instant sourceUpdatedAt,
                  JsonNode raw) {
        this(externalId, title, department, function, locations, employmentType, url, description, postedAt,
                sourceUpdatedAt, raw, null, DetailSource.NONE);
    }

    /** This job with its list fingerprint and the source of its detail. */
    public RawJob withDetail(String listHash, DetailSource detail) {
        return new RawJob(externalId, title, department, function, locations, employmentType, url, description,
                postedAt, sourceUpdatedAt, raw, listHash, detail);
    }


    public RawJob {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        locations = locations == null ? List.of() : List.copyOf(locations);
        detail = detail == null ? DetailSource.NONE : detail;
    }
}