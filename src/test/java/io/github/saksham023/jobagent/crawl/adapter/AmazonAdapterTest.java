package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.crawl.HtmlToText;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The Amazon adapter without the network: mapping a real-shaped search hit (amazon.jobs, 2026-10-06). */
class AmazonAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final JsonNode JOB = JSON.readTree("""
            {"id": "0d51c2fc-699f-47ee-9432-75330936d9a3", "id_icims": "10388318",
             "title": "Software Development Engineer II", "job_category": "Software Development",
             "job_family": "Software Development", "job_schedule_type": "full-time",
             "job_path": "/en/jobs/10388318/software-development-engineer-ii",
             "posted_date": "October  6, 2026",
             "description": "Build services for Amazon Pay.",
             "basic_qualifications": "- 3+ years of non-internship professional software development experience<br/>- Experience programming with at least one software programming language",
             "preferred_qualifications": "- Experience with AWS",
             "normalized_location": "Bengaluru, Karnataka, IND",
             "locations": [
               "{\\"normalizedStateName\\":\\"Karnataka\\",\\"countryIso2a\\":\\"IN\\",\\"normalizedCityName\\":\\"Bengaluru\\",\\"type\\":\\"ONSITE\\",\\"normalizedLocation\\":\\"Bengaluru, Karnataka, IND\\"}",
               "{\\"normalizedStateName\\":\\"Bihar\\",\\"countryIso2a\\":\\"IN\\",\\"type\\":\\"VIRTUAL\\",\\"normalizedLocation\\":\\"Bihar, IND\\"}"
             ]}""");

    @Test
    void mapsASearchHit() {
        RawJob job = AmazonAdapter.toRawJob(JOB, JSON);

        assertThat(job.externalId()).isEqualTo("10388318");
        assertThat(job.title()).isEqualTo("Software Development Engineer II");
        assertThat(job.department()).isEqualTo("Software Development");
        assertThat(job.employmentType()).isEqualTo("full-time");
        assertThat(job.url()).isEqualTo("https://www.amazon.jobs/en/jobs/10388318/software-development-engineer-ii");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-10-05T18:30:00Z"));      // midnight in India
        assertThat(job.locations()).containsExactly(
                new RawLocation("Bengaluru, Karnataka, IND", "Bengaluru", "Karnataka", "IN", false),
                new RawLocation("Bihar, IND", null, "Bihar", "IN", true));
    }

    @Test
    void qualificationsGetTheirOwnHeadings() {
        String text = HtmlToText.convert(AmazonAdapter.description(JOB));
        assertThat(text).isEqualTo("""
                Build services for Amazon Pay.

                Basic Qualifications
                - 3+ years of non-internship professional software development experience
                - Experience programming with at least one software programming language

                Preferred Qualifications
                - Experience with AWS""");
    }

    @Test
    void oddDatesAndMissingLocationsDoNotFail() {
        assertThat(AmazonAdapter.postedAt("yesterday")).isNull();
        assertThat(AmazonAdapter.postedAt(null)).isNull();
        JsonNode bare = JSON.readTree("""
                {"id": "x", "title": "T", "normalized_location": "Pune, Maharashtra, IND", "locations": []}""");
        assertThat(AmazonAdapter.toRawJob(bare, JSON).locations())
                .containsExactly(RawLocation.ofText("Pune, Maharashtra, IND"));
        assertThat(AmazonAdapter.toRawJob(bare, JSON).description()).isNull();
    }
}