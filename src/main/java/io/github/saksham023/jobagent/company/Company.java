package io.github.saksham023.jobagent.company;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One row of the `companies` table: a company we crawl and how to reach its job board.
 * `config` is the raw platform-specific JSON; each adapter maps it to its own typed config record.
 */
public record Company(
        long id,
        String slug,
        String name,
        String platform,
        JsonNode config,
        String careersUrl,
        boolean enabled,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
}