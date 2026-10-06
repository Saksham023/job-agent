package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.HtmlToText;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import io.github.saksham023.jobagent.crawl.adapter.OracleAdapter.OracleConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Oracle Recruiting Cloud adapter without the network: real-shaped JPMorgan and TI data (2026-10-06). */
class OracleAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OracleConfig JPMC =
            new OracleConfig("jpmc.fa.oraclecloud.com", "CX_1001", "India", null, Duration.ZERO);

    private static final JsonNode SUMMARY = JSON.readTree("""
            {"Id": "210763096", "Title": "Software Engineer III - Java, Spring boot, Public cloud",
             "PostedDate": "2026-10-06", "PrimaryLocationCountry": "IN", "JobFamily": "Software Engineering",
             "JobFunction": "Technology", "WorkplaceTypeCode": null, "WorkplaceType": "",
             "PrimaryLocation": "Bengaluru, Karnataka, India",
             "secondaryLocations": [{"Name": "Hyderabad, Telangana, India", "CountryCode": "IN"}]}""");

    private static final JsonNode DETAIL = JSON.readTree("""
            {"Id": "210763096", "Category": "Software Engineering", "JobFunction": "Technology",
             "JobSchedule": "Full time", "ExternalPostedStartDate": "2026-10-06T05:43:39+00:00",
             "ExternalDescriptionStr": "<p>Build payment APIs.</p>",
             "ExternalQualificationsStr": "<ul><li>3+ years of Java</li></ul>",
             "CorporateDescriptionStr": "<p>JPMorgan Chase is one of the oldest financial institutions...</p>"}""");

    @Test
    void mapsAListEntryWithItsDetail() {
        RawJob job = OracleAdapter.toRawJob(JPMC, SUMMARY, DETAIL);

        assertThat(job.externalId()).isEqualTo("210763096");
        assertThat(job.title()).isEqualTo("Software Engineer III - Java, Spring boot, Public cloud");
        assertThat(job.department()).isEqualTo("Software Engineering");
        assertThat(job.function()).isEqualTo("Technology");
        assertThat(job.employmentType()).isEqualTo("Full time");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-10-06T05:43:39Z"));
        assertThat(job.url()).isEqualTo("https://jpmc.fa.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1001/job/210763096");
        assertThat(job.locations()).containsExactly(
                new RawLocation("Bengaluru, Karnataka, India", null, null, "IN", null),
                new RawLocation("Hyderabad, Telangana, India", null, null, "IN", null));
    }

    @Test
    void theDescriptionKeepsTheJobsOwnTextsOnly() {
        assertThat(HtmlToText.convert(OracleAdapter.description(DETAIL))).isEqualTo("""
                Build payment APIs.

                Qualifications
                - 3+ years of Java""");                                   // the corporate blurb is left out
    }

    @Test
    void withoutADetailTheListFactsAreKept() {
        RawJob job = OracleAdapter.toRawJob(JPMC, SUMMARY, null);
        assertThat(job.description()).isNull();
        assertThat(job.department()).isEqualTo("Software Engineering");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-10-06T00:00:00Z"));
    }

    @Test
    void theCountryIsTheExactFacetEntry() {
        JsonNode facet = JSON.readTree("""
                [{"Id": 300000081134215, "Name": "Karnataka, India", "TotalCount": 159},
                 {"Id": 300000000289360, "Name": "India", "TotalCount": 317}]""");
        assertThat(OracleAdapter.locationId(facet, "India")).isEqualTo("300000000289360");
        assertThat(OracleAdapter.locationId(facet, "Japan")).isNull();
    }

    @Test
    void configNeedsHostAndSite() {
        Company tiCompany = new Company(1, "texas-instruments", "TI", "oracle",
                JSON.readTree("{\"host\": \"edbz.fa.us2.oraclecloud.com\", \"siteNumber\": \"CX\"}"), null, true, null, null, null);
        OracleConfig ti = OracleConfig.from(tiCompany);
        assertThat(ti.country()).isEqualTo("India");
        assertThat(ti.locationId()).isNull();
        assertThat(ti.delay()).isEqualTo(Duration.ofSeconds(1));
        Company broken = new Company(2, "x", "X", "oracle", JSON.readTree("{\"host\": \"h\"}"), null, true, null, null, null);
        assertThatThrownBy(() -> OracleConfig.from(broken)).hasMessageContaining("config.host and config.siteNumber");
    }

    @Test
    void eachOracleTenantIsItsOwnServer() {
        Company jpmc = new Company(1, "jpmorgan", "JPMorgan", "oracle",
                JSON.readTree("""
                        {"host": "jpmc.fa.oraclecloud.com", "siteNumber": "CX_1001"}"""), null, true, null, null, null);
        assertThat(new OracleAdapter(null, null, null).serverKey(jpmc)).isEqualTo("jpmc.fa.oraclecloud.com");
    }
}
