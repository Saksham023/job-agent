package io.github.saksham023.jobagent.geo;

import io.github.saksham023.jobagent.geo.ParsedLocation.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real location strings seen on job boards (Greenhouse 2026-10-04) plus the known traps.
 * Plain unit test: no Spring context, the Gazetteer loads the CSVs from the classpath directly.
 */
class LocationParserTest {

    private static final LocationParser parser = new LocationParser(new Gazetteer());

    private static List<ParsedLocation> parse(String text) {
        return parser.parse(text, null, null);
    }

    private static ParsedLocation single(String text) {
        List<ParsedLocation> results = parse(text);
        assertThat(results).as("results for '%s'", text).hasSize(1);
        return results.getFirst();
    }

    // ---------------------------------------------------------------- basics and aliases

    @Test
    void bareIndianCityAliasResolvesToCanonicalCity() {
        ParsedLocation location = single("Bangalore");

        assertThat(location.city()).isEqualTo("Bengaluru");
        assertThat(location.region()).isEqualTo("Karnataka");
        assertThat(location.countryCode()).isEqualTo("IN");
        assertThat(location.status()).isEqualTo(Status.RESOLVED);
    }

    @Test
    void countryOnlyHasNoCity() {
        ParsedLocation location = single("India");

        assertThat(location.countryCode()).isEqualTo("IN");
        assertThat(location.city()).isNull();
    }

    @Test
    void officeSuffixFallsBackToCityPrefix() {
        assertThat(single("Bengaluru-VTP, India").city()).isEqualTo("Bengaluru");
    }

    @Test
    void techParkAreaResolvesToItsCity() {
        assertThat(single("Hinjewadi Phase 2").city()).isEqualTo("Pune");
    }

    @Test
    void accentsAreIgnored() {
        ParsedLocation location = single("Remote - Ôsaka, Japan");

        assertThat(location.city()).isEqualTo("Osaka");
        assertThat(location.countryCode()).isEqualTo("JP");
    }

    // ---------------------------------------------------------------- the India / Indiana trap

    @Test
    void indianaIsNotIndia() {
        ParsedLocation location = single("Remote - Indiana, USA");

        assertThat(location.countryCode()).isEqualTo("US");
        assertThat(location.region()).isEqualTo("Indiana");
        assertThat(location.isInCountry("IN")).isFalse();
    }

    @Test
    void bareIndianaWithoutCountryIsStillTheUsState() {
        assertThat(parse("Chicago, Illinois; Indiana"))
                .extracting(ParsedLocation::countryCode)
                .containsOnly("US");
    }

    // ---------------------------------------------------------------- several locations in one string

    @Test
    void semicolonSeparatesLocations() {
        assertThat(parse("Bangalore, IND; Mohali, IND"))
                .extracting(ParsedLocation::city)
                .containsExactly("Bengaluru", "Mohali");
    }

    @Test
    void duplicateSegmentsAreMerged() {
        assertThat(parse("Bangalore, IND; Bangalore, IND")).hasSize(1);
    }

    @Test
    void orGivesBothCities() {
        assertThat(parse("Bangalore or Pune"))
                .extracting(ParsedLocation::city)
                .containsExactly("Bengaluru", "Pune");
    }

    @Test
    void ampersandGivesBothCities() {
        assertThat(parse("Pune & Mumbai"))
                .extracting(ParsedLocation::city)
                .containsExactly("Pune", "Mumbai");
    }

    @Test
    void commaListOfCitiesWithCountryGivesEveryCity() {
        List<ParsedLocation> results = parse("Bengaluru, Pune, India");

        assertThat(results).extracting(ParsedLocation::city).containsExactly("Bengaluru", "Pune");
        assertThat(results).allSatisfy(location -> assertThat(location.countryCode()).isEqualTo("IN"));
    }

    @Test
    void commaListOfCitiesWithoutCountryGivesEveryCity() {
        assertThat(parse("Bengaluru, Pune, Hyderabad"))
                .extracting(ParsedLocation::city)
                .containsExactly("Bengaluru", "Pune", "Hyderabad");
    }

    @Test
    void citiesInDifferentCountriesKeepTheirOwnCountry() {
        assertThat(parse("London, Bangalore"))
                .extracting(ParsedLocation::countryCode)
                .containsExactly("GB", "IN");
    }

    @Test
    void eachCityKeepsItsOwnRegion() {
        assertThat(parse("Gurgaon, Noida"))
                .extracting(ParsedLocation::region)
                .containsExactly("Haryana", "Uttar Pradesh");
    }

    @Test
    void namesThatContainConnectorsAreNotSplit() {
        assertThat(single("Jammu and Kashmir").region()).isEqualTo("Jammu and Kashmir");
        assertThat(single("Delhi/NCR").metroKey()).isEqualTo("NCR");
    }

    @Test
    void spacedDashSeparatesPartsLikeAComma() {
        assertThat(single("USA - Update Location").countryCode()).isEqualTo("US");
        assertThat(single("Northeast - United States").countryCode()).isEqualTo("US");
        assertThat(single("Hyderabad - Telangana").region()).isEqualTo("Telangana");
        assertThat(single("Bengaluru-VTP, India").city()).isEqualTo("Bengaluru");   // no spaces: not a separator
    }

    // ---------------------------------------------------------------- metros

    @Test
    void cityInsideAMetroIsOneLocationWithTheMetro() {
        ParsedLocation location = single("Gurgaon, Delhi NCR");

        assertThat(location.city()).isEqualTo("Gurugram");
        assertThat(location.metroKey()).isEqualTo("NCR");
    }

    // ---------------------------------------------------------------- ambiguity and context

    @Test
    void votingPicksTheUsStateWhenTheCityIsAmerican() {
        ParsedLocation location = single("Atlanta, Georgia");

        assertThat(location.countryCode()).isEqualTo("US");
        assertThat(location.region()).isEqualTo("Georgia");
    }

    @Test
    void unknownCityWithGeorgiaFallsBackToTheCountry() {
        assertThat(single("Tbilisi, Georgia").countryCode()).isEqualTo("GE");
    }

    @Test
    void stateCodesAreReadOnlyWithCountryContext() {
        assertThat(single("Kochi, KL").region()).isEqualTo("Kerala");
        assertThat(single("Austin, TX, USA").region()).isEqualTo("Texas");
        assertThat(single("Portland, OR").status()).isEqualTo(Status.UNRESOLVED);   // not Odisha
    }

    @Test
    void cityOrStateIsDecidedByPosition() {
        ParsedLocation newYork = single("New York, New York, USA");
        assertThat(newYork.city()).isEqualTo("New York City");
        assertThat(newYork.region()).isEqualTo("New York");

        ParsedLocation seattle = single("Seattle, Washington");
        assertThat(seattle.city()).isEqualTo("Seattle");
        assertThat(seattle.region()).isEqualTo("Washington");

        assertThat(parse("Mumbai, Delhi"))
                .extracting(ParsedLocation::city)
                .containsExactly("Mumbai", "Delhi");
    }

    // ---------------------------------------------------------------- remote and hybrid

    @Test
    void remoteWithCountry() {
        ParsedLocation location = single("Remote - India");

        assertThat(location.remote()).isTrue();
        assertThat(location.countryCode()).isEqualTo("IN");
        assertThat(location.city()).isNull();
    }

    @Test
    void workdayDottedCodes() {
        ParsedLocation pune = single("IND.Pune");
        assertThat(pune.city()).isEqualTo("Pune");
        assertThat(pune.countryCode()).isEqualTo("IN");
    }

    @Test
    void workdayDashedCodes() {
        ParsedLocation bangalore = single("IND-Bangalore Electronic City - S1");
        assertThat(bangalore.countryCode()).isEqualTo("IN");
        assertThat(bangalore.city()).isEqualTo("Bengaluru");
        assertThat(single("IND-Hyderabad 115 IT Park Area").countryCode()).isEqualTo("IN");
    }

    @Test
    void aDashWithASpaceOnlyAfterItSeparatesParts() {
        ParsedLocation location = single("Remote- India- Gurugram");
        assertThat(location.city()).isEqualTo("Gurugram");
        assertThat(location.remote()).isTrue();
    }

    @Test
    void virtualMeansRemote() {
        ParsedLocation location = single("Virtual India");

        assertThat(location.remote()).isTrue();
        assertThat(location.countryCode()).isEqualTo("IN");
    }

    @Test
    void remoteAloneIsUnresolvedButRemote() {
        ParsedLocation location = single("Remote");

        assertThat(location.status()).isEqualTo(Status.UNRESOLVED);
        assertThat(location.remote()).isTrue();
    }

    @Test
    void hybridIsStrippedAndNotRemote() {
        ParsedLocation location = single("Bengaluru (Hybrid)");

        assertThat(location.city()).isEqualTo("Bengaluru");
        assertThat(location.remote()).isFalse();
    }

    // ---------------------------------------------------------------- unknown text and structured hints

    @Test
    void unknownTextIsUnresolvedNotGuessed() {
        assertThat(single("Multiple Locations").status()).isEqualTo(Status.UNRESOLVED);
    }

    @Test
    void structuredCountryHintDecidesTheCountry() {
        ParsedLocation location = parser.parse("Bengaluru, KA", "in", false).getFirst();

        assertThat(location.countryCode()).isEqualTo("IN");
        assertThat(location.region()).isEqualTo("Karnataka");
        assertThat(location.remote()).isFalse();
    }

    @Test
    void countryHintWithoutTextGivesCountryOnly() {
        assertThat(parser.parse(null, "IN", null))
                .singleElement()
                .satisfies(location -> assertThat(location.countryCode()).isEqualTo("IN"));
        assertThat(parser.parse(null, null, null)).isEmpty();
    }
}