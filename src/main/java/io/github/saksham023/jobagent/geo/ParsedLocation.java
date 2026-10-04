package io.github.saksham023.jobagent.geo;

/**
 * One location after parsing: canonical names from the Gazetteer plus how sure we are.
 * A job has one ParsedLocation per location segment ("Bangalore, IND; Mohali, IND" gives two).
 *
 * @param raw         the segment exactly as it appeared, e.g. "Remote - Indiana, USA" (kept for reports)
 * @param city        canonical city ("Bengaluru"), null when the segment names no known city
 * @param region      canonical subdivision ("Karnataka"), null when unknown
 * @param countryCode ISO alpha-2 ("IN"), null when unresolved
 * @param metroKey    metro key ("NCR") when the segment names a metro or a city in one, else null
 * @param remote      true when the segment (or the platform) says remote
 * @param status      RESOLVED: country known; AMBIGUOUS: several countries fit equally; UNRESOLVED: no country
 */
public record ParsedLocation(
        String raw,
        String city,
        String region,
        String countryCode,
        String metroKey,
        boolean remote,
        Status status
) {

    public enum Status {
        RESOLVED,
        AMBIGUOUS,
        UNRESOLVED
    }

    public boolean isInCountry(String iso2) {
        return status == Status.RESOLVED && iso2.equals(countryCode);
    }

    public static ParsedLocation unresolved(String raw, boolean remote) {
        return new ParsedLocation(raw, null, null, null, null, remote, Status.UNRESOLVED);
    }
}