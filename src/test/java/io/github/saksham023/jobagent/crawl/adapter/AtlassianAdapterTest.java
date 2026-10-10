package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.crawl.HtmlToText;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Atlassian adapter without the network: real-shaped rows from www.atlassian.com/endpoint/careers/listings (2026-10-11). */
class AtlassianAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String INDIA_JOB = """
            {"id": 26643, "portalId": 17, "title": "Security Testing Engineer - Penetration Tester", "type": null,
             "category": "Security",
             "locations": ["Bengaluru - India -   Bengaluru,  560071 India", "Remote - India - Remote", "Remote - Remote"],
             "overview": "<p><strong>Working at Atlassian</strong></p><p>Atlassians can choose where they work.</p>",
             "responsibilities": "<ul><li><p>Run penetration tests on our cloud products</p></li></ul>",
             "qualifications": "<ul><li><p>3+ years of experience in application security testing</p></li></ul>",
             "applyUrl": "https://careers-apac-atlassian.icims.com/jobs/26643/security-testing-engineer/job?mode=apply",
             "portalJobPost": {"portalId": 17, "id": 26643, "updatedDate": "2026-09-24 03:33 PM",
               "portalUrl": "https://careers-apac-atlassian.icims.com/jobs/26643/security-testing-engineer/job"}}
            """;

    private static final JsonNode LISTINGS = JSON.readTree("[" + INDIA_JOB + "," + INDIA_JOB + """
            ,{"id": 27031, "title": "Principal Full Stack Software Engineer - Vancouver", "category": "Engineering",
              "locations": ["Vancouver - Canada - Vancouver"], "overview": "<p>x</p>"}
            ,{"id": 28001, "title": "Account Executive", "category": "Sales",
              "locations": ["Remote - Indiana - Remote"], "overview": "<p>x</p>"}
            ]""");

    @Test
    void keepsTheCountrysPostingsOnceAndNeverIndiana() {
        List<RawJob> jobs = AtlassianAdapter.inCountry(LISTINGS, "India");
        assertThat(jobs).extracting(RawJob::externalId).containsExactly("26643");
    }

    @Test
    void mapsAPosting() {
        RawJob job = AtlassianAdapter.inCountry(LISTINGS, "India").getFirst();

        assertThat(job.title()).isEqualTo("Security Testing Engineer - Penetration Tester");
        assertThat(job.department()).isEqualTo("Security");
        assertThat(job.url()).isEqualTo("https://careers-apac-atlassian.icims.com/jobs/26643/security-testing-engineer/job");
        assertThat(job.postedAt()).isNull();                       // only a last-updated time exists: never a posting date
        assertThat(job.locations()).extracting(RawLocation::text)
                .containsExactly("Bengaluru - India -   Bengaluru,  560071 India", "Remote - India - Remote", "Remote - Remote");
        String text = HtmlToText.convert(job.description());
        assertThat(text).contains("Responsibilities", "Run penetration tests", "Qualifications", "3+ years of experience");
    }
}
