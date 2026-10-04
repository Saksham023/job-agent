package io.github.saksham023.jobagent.crawl;

import java.util.Locale;

/**
 * One job location as the platform reported it. `text` is the platform's display string; the other fields
 * are set only when the platform sends them as structured data, and stay null otherwise.
 * The location parser resolves whatever is missing from `text`.
 *
 * @param text        display text, e.g. "Bangalore, IND" or even "Bangalore, IND; Mohali, IND" (the parser splits it)
 * @param city        structured city, if the platform provides one
 * @param region      structured state/province, if provided
 * @param countryCode ISO 3166-1 alpha-2 ("IN", "US"); only from structured fields, never guessed by an adapter
 * @param remote      TRUE/FALSE when the platform states it, null when it does not say
 */
public record RawLocation(String text, String city, String region, String countryCode, Boolean remote) {

    public RawLocation {
        text = blankToNull(text);
        city = blankToNull(city);
        region = blankToNull(region);
        countryCode = blankToNull(countryCode);

        if (countryCode != null) {
            countryCode = countryCode.toUpperCase(Locale.ROOT);
            if (!countryCode.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("countryCode must be ISO alpha-2, got: " + countryCode);
            }
        }
        if (text == null && city == null && region == null && countryCode == null) {
            throw new IllegalArgumentException("RawLocation needs text or at least one structured field");
        }
    }

    /** For platforms that only send free text, like Greenhouse. */
    public static RawLocation ofText(String text) {
        return new RawLocation(text, null, null, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}