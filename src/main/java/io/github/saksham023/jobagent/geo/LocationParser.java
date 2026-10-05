package io.github.saksham023.jobagent.geo;

import io.github.saksham023.jobagent.geo.Gazetteer.City;
import io.github.saksham023.jobagent.geo.Gazetteer.Country;
import io.github.saksham023.jobagent.geo.Gazetteer.Metro;
import io.github.saksham023.jobagent.geo.Gazetteer.Subdivision;
import io.github.saksham023.jobagent.geo.ParsedLocation.Status;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns free-text job locations into canonical places using the Gazetteer.
 * Steps: split into segments; per segment detect and strip remote/hybrid words, split into comma parts
 * (and "or"/"and"/"&"/"/" when every piece is a known place), decide the country by voting, then resolve
 * cities, region and metro inside that country. One segment can yield several locations
 * ("Bengaluru, Pune, India" gives two). Stateless and thread-safe.
 */
@Component
public class LocationParser {

    /** Between different locations: ";", "|", " / " (with spaces), line breaks. Not commas. */
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("\\s*(?:;|\\||\\s/\\s|\\R)\\s*");

    /** Inside a part: "Bangalore or Pune", "Pune and Mumbai", "Pune & Mumbai", "Bangalore/Pune". */
    private static final Pattern CONNECTOR = Pattern.compile("(?i)\\s+(?:or|and)\\s+|\\s*[&/]\\s*");

    /** "Virtual India" is Intel's Workday name for remote work in India. */
    private static final Pattern REMOTE = Pattern.compile("(?i)\\b(?:remote|virtual|work from home|wfh|anywhere)\\b");
    private static final Pattern HYBRID = Pattern.compile("(?i)\\b(?:hybrid|on-?site|in-?office)\\b");

    /** Leftover dashes, colons, & and / at the edges of a part, e.g. " - Indiana" -> "Indiana". */
    private static final Pattern EDGE_JUNK = Pattern.compile("^[\\s\\-\\u2013\\u2014:&/]+|[\\s\\-\\u2013\\u2014:&/]+$");

    /** Between parts of one location: commas, and a dash with spaces around it ("USA - Update Location"). */
    private static final Pattern PART_SEPARATOR = Pattern.compile(",|\\s+[-\\u2013\\u2014]\\s+");

    /** Word boundaries for the prefix fallback: "Bengaluru-VTP" -> ["Bengaluru", "VTP"]. */
    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s\\-/]+");

    private final Gazetteer gazetteer;

    public LocationParser(Gazetteer gazetteer) {
        this.gazetteer = gazetteer;
    }

    /**
     * @param text        the platform's location text; may hold several locations ("A; B", "A, B, India", "A or B")
     * @param countryHint ISO alpha-2 from structured platform data, or null; when valid it decides the country
     * @param remoteHint  the platform's own remote flag, or null to detect it from the text
     * @return one entry per distinct location, in the order they appear
     */
    public List<ParsedLocation> parse(String text, String countryHint, Boolean remoteHint) {
        String hint = validCountry(countryHint);
        if (text == null || text.isBlank()) {
            return hint == null ? List.of() : List.of(countryOnly(hint, hint, Boolean.TRUE.equals(remoteHint)));
        }
        Set<ParsedLocation> results = new LinkedHashSet<>();          // drops exact duplicates, keeps order
        for (String segment : SEGMENT_SEPARATOR.split(text.strip())) {
            if (!segment.isBlank()) {
                results.addAll(parseSegment(segment.strip(), hint, remoteHint));
            }
        }
        return List.copyOf(results);
    }

    // ---------------------------------------------------------------- one segment

    private List<ParsedLocation> parseSegment(String segment, String hint, Boolean remoteHint) {
        boolean remote = remoteHint != null ? remoteHint : REMOTE.matcher(segment).find();
        List<String> parts = parts(segment);

        if (parts.isEmpty()) {                                        // e.g. just "Remote"
            return List.of(hint == null ? ParsedLocation.unresolved(segment, remote) : countryOnly(segment, hint, remote));
        }

        // "Bangalore or Pune", "London, Bangalore": every part is a city on its own, so resolve each separately
        if (parts.size() > 1 && parts.stream().allMatch(this::isCityName)) {
            List<ParsedLocation> results = new ArrayList<>();
            for (String part : parts) {
                results.addAll(resolve(segment, List.of(part), hint, remote));
            }
            return results;
        }
        return resolve(segment, parts, hint, remote);
    }

    private List<ParsedLocation> resolve(String segment, List<String> parts, String hint, boolean remote) {
        String country = hint;
        if (country == null) {
            CountryVote vote = voteCountry(parts);
            if (vote.country() == null) {
                Status status = vote.ambiguous() ? Status.AMBIGUOUS : Status.UNRESOLVED;
                return List.of(new ParsedLocation(segment, null, null, null, null, remote, status));
            }
            country = vote.country();
        }
        return resolveWithin(segment, parts, country, remote);
    }

    /** "Remote - Indiana, USA" -> ["Indiana", "USA"]; "Bangalore or Pune (Hybrid)" -> ["Bangalore", "Pune"]. */
    private List<String> parts(String segment) {
        String cleaned = REMOTE.matcher(segment).replaceAll(" ");
        cleaned = HYBRID.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replace('(', ' ').replace(')', ' ');

        List<String> parts = new ArrayList<>();
        for (String part : PART_SEPARATOR.split(cleaned)) {
            String trimmed = EDGE_JUNK.matcher(part).replaceAll("");
            if (!trimmed.isBlank()) {
                parts.addAll(splitConnectors(trimmed));
            }
        }
        return parts;
    }

    /** Splits on or/and/&// only when the whole part is not itself a name and every piece is a known place. */
    private List<String> splitConnectors(String part) {
        if (isExactName(part)) {                                      // "Jammu and Kashmir", "J&K", "Delhi/NCR"
            return List.of(part);
        }
        List<String> pieces = Arrays.stream(CONNECTOR.split(part))
                .map(String::strip)
                .filter(piece -> !piece.isEmpty())
                .toList();
        boolean everyPieceIsAPlace = pieces.size() > 1 && pieces.stream().allMatch(this::isKnownPlace);
        return everyPieceIsAPlace ? pieces : List.of(part);
    }

    // ---------------------------------------------------------------- step A: which country?

    private record CountryVote(String country, boolean ambiguous) {}

    /**
     * Each part votes once for every country it could belong to (as a country name, metro, subdivision or city).
     * Most votes wins; a tie goes to the country that a part names directly; otherwise it is ambiguous.
     * "Atlanta, Georgia": Georgia votes {GE, US}, Atlanta votes {US} -> US.
     */
    private CountryVote voteCountry(List<String> parts) {
        Map<String, Integer> votes = new HashMap<>();
        Set<String> namedDirectly = new HashSet<>();

        for (String part : parts) {
            Set<String> candidates = new HashSet<>();
            gazetteer.countryByName(part).ifPresent(country -> {
                candidates.add(country.code());
                namedDirectly.add(country.code());
            });
            gazetteer.metroByName(part).ifPresent(metro -> candidates.add(metro.countryCode()));
            gazetteer.subdivisionsByName(part).forEach(subdivision -> candidates.add(subdivision.countryCode()));
            matchCities(part).forEach(city -> candidates.add(city.countryCode()));
            candidates.forEach(code -> votes.merge(code, 1, Integer::sum));
        }

        if (votes.isEmpty()) {
            return new CountryVote(null, false);
        }
        int best = Collections.max(votes.values());
        List<String> leaders = votes.entrySet().stream()
                .filter(entry -> entry.getValue() == best)
                .map(Map.Entry::getKey)
                .toList();
        if (leaders.size() == 1) {
            return new CountryVote(leaders.getFirst(), false);
        }
        List<String> named = leaders.stream().filter(namedDirectly::contains).toList();
        if (named.size() == 1) {
            return new CountryVote(named.getFirst(), false);
        }
        return new CountryVote(null, true);
    }

    // ---------------------------------------------------------------- step B: details inside that country

    /**
     * Right to left, collecting every city. A part that is both a city and a state ("Delhi", "New York")
     * counts as a city when it is the leftmost part or when another part is clearly only a city
     * ("Mumbai, Delhi"); otherwise it is the state ("New York, New York, USA").
     */
    private List<ParsedLocation> resolveWithin(String segment, List<String> parts, String country, boolean remote) {
        boolean listsCities = parts.stream()
                .anyMatch(part -> cityIn(country, part).isPresent() && subdivisionIn(country, part).isEmpty());

        List<City> cities = new ArrayList<>();
        String region = null;
        String metro = null;

        for (int i = parts.size() - 1; i >= 0; i--) {
            String part = parts.get(i);

            boolean isTheCountry = gazetteer.countryByName(part).map(Country::code).filter(country::equals).isPresent();
            if (isTheCountry) {
                continue;
            }

            Optional<Metro> metroMatch = gazetteer.metroByName(part).filter(m -> m.countryCode().equals(country));
            if (metroMatch.isPresent()) {
                if (metro == null) {
                    metro = metroMatch.get().key();
                }
                continue;
            }

            Optional<City> city = cityIn(country, part);
            Optional<Subdivision> subdivision = subdivisionIn(country, part);
            boolean leftmost = i == 0;

            if (city.isPresent() && (subdivision.isEmpty() || leftmost || listsCities)) {
                if (!cities.contains(city.get())) {
                    cities.addFirst(city.get());                      // keep left-to-right order
                }
            } else if (subdivision.isPresent() && region == null) {
                region = subdivision.get().name();
            }
            // anything else (an unknown area like "Phase 2") is ignored
        }

        if (cities.isEmpty()) {
            return List.of(new ParsedLocation(segment, null, region, country, metro, remote, Status.RESOLVED));
        }
        List<ParsedLocation> results = new ArrayList<>();
        for (City city : cities) {
            results.add(new ParsedLocation(
                    segment,
                    city.name(),
                    city.region() != null ? city.region() : region,
                    country,
                    city.metroKey() != null ? city.metroKey() : metro,
                    remote,
                    Status.RESOLVED));
        }
        return results;
    }

    /** Codes ("KA", "TX") are tried only here, where the country is already known. */
    private Optional<Subdivision> subdivisionIn(String country, String part) {
        return gazetteer.subdivisionByCode(country, part.strip())
                .or(() -> gazetteer.subdivisionsByName(part).stream()
                        .filter(s -> s.countryCode().equals(country))
                        .findFirst());
    }

    /** Highest-priority city with this name in this country. */
    private Optional<City> cityIn(String country, String part) {
        return matchCities(part).stream()
                .filter(c -> c.countryCode().equals(country))
                .findFirst();
    }

    /** Exact name/alias first; else the longest leading words that name a city ("Bengaluru-VTP" -> Bengaluru). */
    private List<City> matchCities(String part) {
        List<City> exact = gazetteer.citiesByName(part);
        if (!exact.isEmpty()) {
            return exact;
        }
        String[] words = Arrays.stream(WORD_SPLIT.split(part.strip()))
                .filter(word -> !word.isEmpty())
                .toArray(String[]::new);
        for (int n = words.length - 1; n >= 1; n--) {
            List<City> prefixMatch = gazetteer.citiesByName(String.join(" ", Arrays.copyOf(words, n)));
            if (!prefixMatch.isEmpty()) {
                return prefixMatch;
            }
        }
        return List.of();
    }

    // ---------------------------------------------------------------- small predicates and helpers

    /** The whole text is exactly a known name or alias (no prefix matching). */
    private boolean isExactName(String text) {
        return gazetteer.countryByName(text).isPresent()
                || gazetteer.metroByName(text).isPresent()
                || !gazetteer.subdivisionsByName(text).isEmpty()
                || !gazetteer.citiesByName(text).isEmpty();
    }

    /** Known as anything, allowing the city prefix fallback ("Bengaluru-VTP"). */
    private boolean isKnownPlace(String text) {
        return isExactName(text) || !matchCities(text).isEmpty();
    }

    /** Names a city and is neither a country ("Singapore") nor a metro ("Delhi NCR" starts with "Delhi"). */
    private boolean isCityName(String part) {
        return !matchCities(part).isEmpty()
                && gazetteer.countryByName(part).isEmpty()
                && gazetteer.metroByName(part).isEmpty();
    }

    private String validCountry(String countryHint) {
        return gazetteer.countryByCode(countryHint).map(Country::code).orElse(null);
    }

    private static ParsedLocation countryOnly(String raw, String country, boolean remote) {
        return new ParsedLocation(raw, null, null, country, null, remote, Status.RESOLVED);
    }
}