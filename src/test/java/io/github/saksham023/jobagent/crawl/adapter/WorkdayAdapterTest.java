package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import io.github.saksham023.jobagent.crawl.adapter.WorkdayAdapter.Summary;
import io.github.saksham023.jobagent.crawl.adapter.WorkdayAdapter.WorkdayConfig;
import io.github.saksham023.jobagent.geo.Gazetteer;
import io.github.saksham023.jobagent.geo.LocationParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Workday adapter without the network: finding the country filter in real-shaped facets, and mapping a job.
 * The JSON is cut down from real responses (Adobe, Mastercard, Samsung, Nvidia; 2026-10-05).
 */
class WorkdayAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocationParser PARSER = new LocationParser(new Gazetteer());
    private static final WorkdayConfig CONFIG =
            new WorkdayConfig("sec.wd3.myworkdayjobs.com", "sec", "Samsung_Careers", "IN", "jobFamilyGroup");

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    // ---------------------------------------------------------------- the country filter

    @Test
    void countryFacetInsideAGroupWins() {
        JsonNode facets = json("""
                [{"facetParameter": "jobFamilyGroup", "values": [{"descriptor": "Engineering", "id": "e1", "count": 226}]},
                 {"facetParameter": "locationMainGroup", "values": [
                    {"facetParameter": "locationCountry", "descriptor": "Country", "values": [
                        {"descriptor": "United States of America", "id": "us", "count": 300},
                        {"descriptor": "India", "id": "c4f78be1a8f14da0ab49ce1162348a5e", "count": 78}]},
                    {"facetParameter": "locations", "descriptor": "Locations", "values": [
                        {"descriptor": "Bangalore", "id": "b1", "count": 48}]}]}]""");

        assertThat(WorkdayAdapter.countryFilter(facets, "IN", PARSER))
                .isEqualTo(Map.of("locationCountry", List.of("c4f78be1a8f14da0ab49ce1162348a5e")));
    }

    @Test
    void withoutACountryFacetTheIndianCityEntriesAreUsedButNotIndiana() {
        JsonNode facets = json("""
                [{"facetParameter": "locationMainGroup", "values": [
                    {"facetParameter": "locations", "descriptor": "Locations", "values": [
                        {"descriptor": "Pune, India", "id": "pune", "count": 158},
                        {"descriptor": "Navi Mumbai, India (Finicity)", "id": "navi", "count": 11},
                        {"descriptor": "Remote - Indiana", "id": "indiana", "count": 3},
                        {"descriptor": "O'Fallon, Missouri", "id": "ofallon", "count": 90},
                        {"descriptor": "Virtual India", "id": "virtual", "count": 1}]}]}]""");

        assertThat(WorkdayAdapter.countryFilter(facets, "IN", PARSER))
                .isEqualTo(Map.of("locations", List.of("pune", "navi", "virtual")));
    }

    @Test
    void noIndianEntryGivesNoFilter() {
        JsonNode facets = json("""
                [{"facetParameter": "locations", "values": [{"descriptor": "Remote - Indiana", "id": "indiana", "count": 3}]}]""");

        assertThat(WorkdayAdapter.countryFilter(facets, "IN", PARSER)).isEmpty();
    }

    // ---------------------------------------------------------------- mapping

    @Test
    void mapsTheDetail() {
        Summary summary = new Summary(json("""
                {"title": "Senior Staff Engineer", "remoteType": "Hybrid",
                 "externalPath": "/job/SSIR-Angkor-West-Bangalore-India/Senior-Staff-Engineer_R120924"}"""), "Software R&D (JFG)");
        JsonNode detail = json("""
                {"jobPostingInfo": {
                    "title": "Senior Staff Engineer", "location": "SSIR, Angkor West, Bangalore, India",
                    "additionalLocations": ["India, Pune"], "startDate": "2026-09-24", "timeType": "Full time",
                    "jobRequisitionLocation": {"country": {"descriptor": "India", "alpha2Code": "IN"}},
                    "externalUrl": "https://sec.wd3.myworkdayjobs.com/Samsung_Careers/job/x/Senior-Staff-Engineer_R120924",
                    "jobDescription": "<p>Build things.</p>"}}""");

        RawJob job = WorkdayAdapter.toRawJob(CONFIG, summary, detail);

        assertThat(job.externalId()).isEqualTo("Senior-Staff-Engineer_R120924");
        assertThat(job.department()).isEqualTo("Software R&D (JFG)");
        assertThat(job.locations()).containsExactly(
                new RawLocation("SSIR, Angkor West, Bangalore, India", null, null, "IN", false),
                new RawLocation("India, Pune", null, null, null, false));
        assertThat(job.employmentType()).isEqualTo("Full time");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-09-24T00:00:00Z"));
        assertThat(job.url()).endsWith("/Samsung_Careers/job/x/Senior-Staff-Engineer_R120924");
        assertThat(job.description()).isEqualTo("<p>Build things.</p>");
    }

    @Test
    void withoutADetailTheSummaryIsKept() {
        Summary summary = new Summary(json("""
                {"title": "Verification Engineer", "locationsText": "3 Locations",
                 "externalPath": "/job/India-Bengaluru/Verification-Engineer_JR2025159"}"""), null);

        RawJob job = WorkdayAdapter.toRawJob(CONFIG, summary, null);

        assertThat(job.title()).isEqualTo("Verification Engineer");
        assertThat(job.locations()).isEmpty();                        // "3 Locations" names no place
        assertThat(job.description()).isNull();
        assertThat(job.url()).isEqualTo(
                "https://sec.wd3.myworkdayjobs.com/Samsung_Careers/job/India-Bengaluru/Verification-Engineer_JR2025159");
    }

    @Test
    void configNeedsHostTenantAndSite() {
        Company company = new Company(1, "acme", "Acme", "workday",
                json("{\"host\": \"acme.wd1.myworkdayjobs.com\", \"tenant\": \"acme\"}"), null, true, null, null, null);

        assertThatThrownBy(() -> WorkdayConfig.from(company)).hasMessage("acme: config.site is missing");
    }

    @Test
    void tenantsOnOneWorkdayClusterShareAServer() {
        Company adobe = new Company(1, "adobe", "Adobe", "workday", json("""
                {"host": "adobe.wd5.myworkdayjobs.com", "tenant": "adobe", "site": "external_experienced"}"""),
                null, true, null, null, null);
        assertThat(new WorkdayAdapter(null, null, null).serverKey(adobe)).isEqualTo("wd5.myworkdayjobs.com");
    }

    @Test
    void theListFingerprintIgnoresTheDailyChangingPostedOn() {
        JsonNode monday = json("""
                {"title": "Senior Staff Engineer", "externalPath": "/job/Bangalore/Senior-Staff-Engineer_R120924",
                 "locationsText": "Bangalore", "postedOn": "Posted 3 Days Ago", "bulletFields": ["R120924"]}""");
        JsonNode tuesday = json(monday.toString().replace("Posted 3 Days Ago", "Posted 4 Days Ago"));
        JsonNode moved = json(monday.toString().replace("Bangalore\"", "Pune\""));
        String hash = WorkdayAdapter.listHash(new WorkdayAdapter.Summary(monday, "Engineering"));
        assertThat(WorkdayAdapter.listHash(new WorkdayAdapter.Summary(tuesday, "Engineering"))).isEqualTo(hash);
        assertThat(WorkdayAdapter.listHash(new WorkdayAdapter.Summary(moved, "Engineering"))).isNotEqualTo(hash);
        assertThat(WorkdayAdapter.externalId("/job/Bangalore/Senior-Staff-Engineer_R120924"))
                .isEqualTo("Senior-Staff-Engineer_R120924");
    }
}
