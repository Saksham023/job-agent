package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import io.github.saksham023.jobagent.crawl.adapter.EightfoldAdapter.EightfoldConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The Eightfold adapter without the network: mapping real-shaped positions (Microsoft and Qualcomm, 2026-10-06),
 * the config, and the adaptive pace (slower after every throttle signal, same call retried).
 */
class EightfoldAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final EightfoldConfig MICROSOFT =
            new EightfoldConfig("apply.careers.microsoft.com", "microsoft.com", "India", Duration.ZERO, null);

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static Company company(String config) {
        return new Company(1, "acme", "Acme", "eightfold", json(config), null, true, null, null, null);
    }

    @Test
    void mapsADetail() {
        JsonNode detail = json("""
                {"id": "1970393556991445", "name": "Cloud Solution Architecture IC4", "department": "Cloud Solution Architecture",
                 "locations": ["Hyderabad, Telangāna, India"], "standardizedLocations": ["Hyderabad, TS, IN"],
                 "postedTs": 1790850896, "workLocationOption": "onsite", "efcustomTextEmploymentType": ["Full-Time"],
                 "jobDescription": "<p>Design cloud solutions.</p>",
                 "publicUrl": "https://apply.careers.microsoft.com/careers/job/1970393556991445"}""");

        RawJob job = EightfoldAdapter.toRawJob(MICROSOFT, detail, detail);

        assertThat(job.externalId()).isEqualTo("1970393556991445");
        assertThat(job.department()).isEqualTo("Cloud Solution Architecture");
        assertThat(job.locations()).containsExactly(new RawLocation("Hyderabad, Telangāna, India", null, null, "IN", false));
        assertThat(job.employmentType()).isEqualTo("Full-Time");
        assertThat(job.postedAt()).isEqualTo(Instant.ofEpochSecond(1790850896));
        assertThat(job.description()).isEqualTo("<p>Design cloud solutions.</p>");
        assertThat(job.url()).isEqualTo("https://apply.careers.microsoft.com/careers/job/1970393556991445");
    }

    @Test
    void withoutADetailTheSummaryIsKeptAndTheLinkIsBuilt() {
        JsonNode summary = json("""
                {"id": 446715560343, "name": "Engineer staff", "locations": ["India"], "standardizedLocations": ["IN"],
                 "workLocationOption": "remote", "positionUrl": "/careers/job/446715560343"}""");

        RawJob job = EightfoldAdapter.toRawJob(MICROSOFT, summary, null);

        assertThat(job.externalId()).isEqualTo("446715560343");
        assertThat(job.description()).isNull();
        assertThat(job.locations()).containsExactly(new RawLocation("India", null, null, "IN", true));
        assertThat(job.url()).isEqualTo("https://apply.careers.microsoft.com/careers/job/446715560343");
    }

    @Test
    void standardizedLocationsGiveTheCountryEvenWithoutMatchingDisplayTexts() {
        JsonNode job = json("""
                {"locations": ["Multiple Locations"], "standardizedLocations": ["Bengaluru, KA, IN", "Noida, UP, IN"]}""");

        assertThat(EightfoldAdapter.locations(job)).extracting(RawLocation::text, RawLocation::countryCode)
                .containsExactly(tuple("Bengaluru, KA, IN", "IN"), tuple("Noida, UP, IN", "IN"));
    }

    @Test
    void configDefaultsAndRequiredFields() {
        EightfoldConfig config = EightfoldConfig.from(company("{\"host\": \"careers.qualcomm.com\", \"domain\": \"qualcomm.com\"}"));
        assertThat(config.location()).isEqualTo("India");
        assertThat(config.delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(config.base()).isEqualTo("https://careers.qualcomm.com");
        assertThat(config.detailDepartments()).isNull();

        assertThatThrownBy(() -> EightfoldConfig.from(company("{\"host\": \"careers.qualcomm.com\"}")))
                .hasMessage("acme: config.host and config.domain are required");
    }

    @Test
    void detailDepartmentsLimitTheDetailRequests() {
        EightfoldConfig config = EightfoldConfig.from(company("""
                {"host": "apply.careers.microsoft.com", "domain": "microsoft.com",
                 "detailDepartments": "software|engineering|data|applied sciences"}"""));

        assertThat(config.wantsDetail("Software Engineering")).isTrue();
        assertThat(config.wantsDetail("Applied Sciences")).isTrue();
        assertThat(config.wantsDetail("Corporate Sales")).isFalse();
        assertThat(config.wantsDetail(null)).isFalse();
        assertThat(MICROSOFT.wantsDetail("Corporate Sales")).isTrue();            // no pattern: every job
    }

    @Test
    void eachThrottleSignalSlowsThePaceAndTheSameCallIsRetried() {
        EightfoldAdapter.Pace pace = new EightfoldAdapter.Pace(Duration.ZERO);
        AtomicInteger calls = new AtomicInteger();

        String answer = EightfoldAdapter.politely("acme", pace, Duration.ZERO, () -> {
            switch (calls.incrementAndGet()) {
                case 1 -> throw tooManyRequests();
                case 2 -> throw new ResourceAccessException("HTTP connect timed out");
                default -> {
                    return "ok";
                }
            }
        });

        assertThat(answer).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(pace.delay()).isEqualTo(Duration.ofSeconds(2));               // 0 s + 1 s per signal
    }

    @Test
    void theSlowerPaceStaysForTheRestOfTheCrawlUpToTheMaximum() {
        EightfoldAdapter.Pace pace = new EightfoldAdapter.Pace(Duration.ofSeconds(14));
        assertThat(pace.slowDown()).isEqualTo(Duration.ofSeconds(15));
        assertThat(pace.slowDown()).isEqualTo(EightfoldAdapter.MAX_DELAY);
    }

    @Test
    void givesUpAfterTooManySignalsInARow() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> EightfoldAdapter.politely("acme", new EightfoldAdapter.Pace(Duration.ZERO), Duration.ZERO, () -> {
            calls.incrementAndGet();
            throw tooManyRequests();
        })).isInstanceOf(HttpClientErrorException.TooManyRequests.class);
        assertThat(calls).hasValue(EightfoldAdapter.MAX_THROTTLED_TRIES);
    }

    @Test
    void otherErrorsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        EightfoldAdapter.Pace pace = new EightfoldAdapter.Pace(Duration.ZERO);
        assertThatThrownBy(() -> EightfoldAdapter.politely("acme", pace, Duration.ZERO, () -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(), new byte[0], null);
        })).isInstanceOf(HttpClientErrorException.Forbidden.class);
        assertThat(calls).hasValue(1);
        assertThat(pace.delay()).isEqualTo(Duration.ZERO);
    }

    private static HttpClientErrorException tooManyRequests() {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", new HttpHeaders(), new byte[0], null);
    }
}