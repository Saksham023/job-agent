package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import org.junit.jupiter.api.Test;

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
}