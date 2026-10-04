package io.github.saksham023.jobagent.crawl.adapter;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;

/**
 * Small, null-safe helpers shared by the job-board adapters for reading platform JSON.
 * Every adapter used to carry its own copy of these; they live here once now.
 */
final class JsonFields {

    private JsonFields() {
    }

    /** The field as a string, or null when it is missing, JSON null, or blank. */
    static String text(JsonNode node, String field) {
        return value(node.path(field));
    }

    /** The node itself as a string (e.g. an array element), or null when missing, JSON null, or blank. */
    static String value(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asString();
        return s.isBlank() ? null : s;
    }

    /** Adds the value unless it is null (List.copyOf and friends reject null elements). */
    static void addIfPresent(Collection<String> target, String value) {
        if (value != null) {
            target.add(value);
        }
    }

    /** Only a well-formed 2-letter code is accepted; anything else becomes null instead of failing the crawl. */
    static String isoCountry(String code) {
        return code != null && code.matches("[A-Za-z]{2}") ? code : null;
    }

    /** "2026-09-14T03:52:53-04:00" or "...Z" to an Instant; null stays null. */
    static Instant toInstant(String isoWithOffset) {
        return isoWithOffset == null ? null : OffsetDateTime.parse(isoWithOffset).toInstant();
    }

    /** Epoch milliseconds (Lever's createdAt) to an Instant, or null when the node is not a number. */
    static Instant fromEpochMillis(JsonNode millis) {
        return millis.isNumber() ? Instant.ofEpochMilli(millis.asLong()) : null;
    }
}
