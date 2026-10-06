package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Grouping companies by server: one thread per group, the companies of a group one after another. */
class CrawlRunServiceTest {

    private static Company company(String slug) {
        return new Company(1, slug, slug, "x", null, null, true, null, null, null);
    }

    @Test
    void companiesOnTheSameServerShareAGroupInOrder() {
        Map<String, String> servers = Map.of("paytm", "lever", "meesho", "lever", "adobe", "wd5.myworkdayjobs.com",
                "visa", "wd5.myworkdayjobs.com", "qualcomm", "careers.qualcomm.com");
        List<Company> companies = List.of(company("paytm"), company("adobe"), company("qualcomm"), company("meesho"),
                company("visa"));

        Map<String, List<Company>> groups = CrawlRunService.groupByServer(companies, c -> servers.get(c.slug()));

        assertThat(groups.keySet()).containsExactly("lever", "wd5.myworkdayjobs.com", "careers.qualcomm.com");
        assertThat(groups.get("lever")).extracting(Company::slug).containsExactly("paytm", "meesho");
        assertThat(groups.get("wd5.myworkdayjobs.com")).extracting(Company::slug).containsExactly("adobe", "visa");
    }

    @Test
    void aCompanyWithAMinimumIntervalWaitsUntilItHasPassed() {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        Company qualcomm = new Company(1, "qualcomm", "Qualcomm", "eightfold",
                JsonMapper.builder().build().readTree("{\"host\": \"careers.qualcomm.com\", \"minCrawlHours\": 24}"),
                null, true, null, null, null);
        assertThat(CrawlRunService.tooSoon(qualcomm, now.minusSeconds(3 * 3600), now)).isEqualTo("last crawl 3 h ago, minimum 24 h");
        assertThat(CrawlRunService.tooSoon(qualcomm, now.minusSeconds(25 * 3600), now)).isNull();
        assertThat(CrawlRunService.tooSoon(qualcomm, null, now)).isNull();                       // never crawled
        assertThat(CrawlRunService.tooSoon(company("adobe"), now.minusSeconds(60), now)).isNull();  // no minimum
    }
}
