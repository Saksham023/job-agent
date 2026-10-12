package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.crawl.HtmlToText;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The D. E. Shaw adapter without the network: a careers page cut down from www.deshawindia.com/careers (2026-10-12). */
class DeShawAdapterTest {

    private static final DeShawAdapter ADAPTER = new DeShawAdapter(null, JsonMapper.builder().build());
    private static final DeShawAdapter.DeShawConfig CONFIG = new DeShawAdapter.DeShawConfig(DeShawAdapter.CAREERS, "IN");

    @Test
    void keepsEveryListedPositionIncludingGeneralApplications() {
        List<RawJob> jobs = positions();

        assertThat(jobs).extracting(RawJob::title).containsExactlyInAnyOrder("All positions in Financial Operations",
                "Lead, Tech (Python Infra)", "Software Engineer (Network) - Senior Member");
    }

    @Test
    void mapsAPosition() {
        RawJob job = byTitle("Lead, Tech (Python Infra)");

        assertThat(job.externalId()).isEqualTo("7337");
        assertThat(job.department()).isEqualTo("Core Tech");
        assertThat(job.function()).isEqualTo("INFORMATION TECHNOLOGY");
        assertThat(job.employmentType()).isEqualTo("Regular FT");
        assertThat(job.url()).isEqualTo("https://www.deshawindia.com/careers/lead-tech-python-infra-7337");
        assertThat(job.postedAt()).isNull();                         // the site has no posting dates
        assertThat(job.locations()).extracting(RawLocation::city).containsExactly("Hyderabad", "Bengaluru", "Gurugram");
        assertThat(job.locations()).extracting(RawLocation::countryCode).containsOnly("IN");
        String text = HtmlToText.convert(job.description());
        assertThat(text).contains("Python Infrastructure team", "Responsibilities", "Who we are looking for",
                "4-6 years of robust experience in advanced Python");
    }

    @Test
    void plainTextKeepsItsLinesAndTheHtmlVersionWinsWhenBothExist() {
        String text = HtmlToText.convert(byTitle("Software Engineer (Network) - Senior Member").description());

        assertThat(text).contains("at least 3 years of experience in the network domain");
        assertThat(text).contains("\nResponsibilities\n");
    }

    @Test
    void plainBulletsBecomeListItems() {
        assertThat(HtmlToText.convert(PlainText.toHtml("Basic qualifications:\n─\t3+ years of Java\n─\tKafka")))
                .isEqualTo("Basic qualifications:\n- 3+ years of Java\n- Kafka");
    }

    private static RawJob byTitle(String title) {
        return positions().stream().filter(j -> j.title().equals(title)).findFirst().orElseThrow();
    }

    private static List<RawJob> positions() {
        JsonNode data = ADAPTER.nextData(null, AppleAdapterTest.page("deshaw-careers.html"));
        return DeShawAdapter.positions(data.path("props").path("pageProps").path("regularJobs"), CONFIG);
    }
}
