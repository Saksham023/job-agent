package io.github.saksham023.jobagent.job;

import io.github.saksham023.jobagent.geo.ParsedLocation;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * A job after normalization, in our own format and ready to store.
 * Derived fields (places, cities, countryCodes, remote, contentHash) are computed once, by JobNormalizer.
 *
 * @param function     the platform's structured job function, when it has one (SmartRecruiters)
 * @param locations    the platform's raw location texts, kept as-is for display and debugging
 * @param places       every parsed location (canonical city/region/country + status)
 * @param cities       distinct canonical cities across places, e.g. ["Bengaluru", "Pune"]
 * @param countryCodes distinct ISO alpha-2 codes of RESOLVED places, e.g. ["IN"]
 * @param description  plain text (HTML already converted), or null
 * @param contentHash  SHA-256 of the fields a human cares about; changes when the posting's content changes
 * @param listHash     the list entry's fingerprint (detail platforms), or null
 * @param detailFetched the detail was downloaded in this crawl (sets jobs.detail_fetched_at)
 */
public record NormalizedJob(
        long companyId,
        String externalId,
        String title,
        String department,
        String function,
        List<String> locations,
        List<ParsedLocation> places,
        List<String> cities,
        List<String> countryCodes,
        boolean remote,
        String employmentType,
        String url,
        String description,
        Instant postedAt,
        Instant sourceUpdatedAt,
        String contentHash,
        JsonNode raw,
        String listHash,
        boolean detailFetched
) {

    public NormalizedJob {
        locations = List.copyOf(locations);
        places = List.copyOf(places);
        cities = List.copyOf(cities);
        countryCodes = List.copyOf(countryCodes);
    }

    /** True when at least one resolved place is in one of these countries (e.g. Set.of("IN")). */
    public boolean isInAnyCountry(Collection<String> wantedCountries) {
        return countryCodes.stream().anyMatch(wantedCountries::contains);
    }

    /** True when not a single location could be placed in a country; these feed the unresolved report. */
    public boolean hasNoResolvedCountry() {
        return countryCodes.isEmpty();
    }
}