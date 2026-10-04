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
        JsonNode raw
) {

    public RawJob {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(url, "url");
        locations = locations == null ? List.of() : List.copyOf(locations);
    }
}