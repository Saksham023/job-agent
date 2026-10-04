package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.geo.LocationParser;
import io.github.saksham023.jobagent.geo.ParsedLocation;
import io.github.saksham023.jobagent.geo.ParsedLocation.Status;
import io.github.saksham023.jobagent.job.NormalizedJob;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * RawJob (platform shape) -> NormalizedJob (our shape): parses locations, converts HTML to text,
 * computes the content hash. Applies no keep/drop policy; CrawlService decides what to store.
 */
@Component
public class JobNormalizer {

    /** Separates fields inside the hash input so ("ab", "c") and ("a", "bc") hash differently. */
    private static final String FIELD_SEPARATOR = "\u001F";

    private final LocationParser locationParser;

    public JobNormalizer(LocationParser locationParser) {
        this.locationParser = locationParser;
    }

    public NormalizedJob normalize(Company company, RawJob raw) {
        List<String> locationTexts = raw.locations().stream()
                .map(RawLocation::text)
                .filter(Objects::nonNull)
                .toList();

        List<ParsedLocation> places = parseLocations(raw.locations());

        List<String> cities = places.stream()
                .map(ParsedLocation::city)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<String> countryCodes = places.stream()
                .filter(place -> place.status() == Status.RESOLVED)
                .map(ParsedLocation::countryCode)
                .distinct()
                .toList();

        boolean remote = places.stream().anyMatch(ParsedLocation::remote);
        String title = raw.title().strip();
        String description = HtmlToText.convert(raw.description());

        return new NormalizedJob(
                company.id(),
                raw.externalId(),
                title,
                raw.department(),
                locationTexts,
                places,
                cities,
                countryCodes,
                remote,
                raw.employmentType(),
                raw.url(),
                description,
                raw.postedAt(),
                raw.sourceUpdatedAt(),
                contentHash(title, raw.department(), locationTexts, description),
                raw.raw()
        );
    }

    /** Every RawLocation through the parser; structured-only locations become "city, region" text. */
    private List<ParsedLocation> parseLocations(List<RawLocation> locations) {
        Set<ParsedLocation> places = new LinkedHashSet<>();
        for (RawLocation location : locations) {
            String text = location.text() != null ? location.text() : structuredText(location);
            places.addAll(locationParser.parse(text, location.countryCode(), location.remote()));
        }
        return List.copyOf(places);
    }

    private static String structuredText(RawLocation location) {
        String joined = String.join(", ", Objects.requireNonNullElse(location.city(), ""),
                Objects.requireNonNullElse(location.region(), "")).replaceAll("^, |, $", "");
        return joined.isBlank() ? null : joined;
    }

    /** SHA-256 hex over the fields a human would notice changing. Excludes dates and the raw JSON. */
    static String contentHash(String title, String department, List<String> locations, String description) {
        String canonical = String.join(FIELD_SEPARATOR,
                Objects.requireNonNullElse(title, ""),
                Objects.requireNonNullElse(department, ""),
                String.join("|", locations),
                Objects.requireNonNullElse(description, ""));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);   // every JDK has it
        }
    }
}