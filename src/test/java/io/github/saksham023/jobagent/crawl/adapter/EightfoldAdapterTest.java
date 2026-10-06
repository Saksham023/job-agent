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
import io.github.saksham023.jobagent.crawl.DetailCache;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Eightfold adapter without the network: mapping real-shaped positions (Microsoft and Qualcomm, 2026-10-06),
 * the config, and the adaptive pace (slower after every throttle signal, same call retried).
 */
class EightfoldAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final EightfoldConfig MICROSOFT =
            new EightfoldConfig("apply.careers.microsoft.com", "microsoft.com", "India", Duration.ZERO, null, null, 10);

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
        assertThat(config.maxDetailsPerCrawl()).isNull();
        assertThat(config.saveEvery()).isEqualTo(10);

        assertThatThrownBy(() -> EightfoldConfig.from(company("{\"host\": \"careers.qualcomm.com\"}")))
                .hasMessage("acme: config.host and config.domain are required");
    }

    @Test
    void maxDetailsPerCrawlLeavesTheRestForTheNextCrawlAndKeepsOlderDetails() {
        Company company = company("""
                {"host": "apply.careers.microsoft.com", "domain": "microsoft.com", "delayMs": 0, "maxDetailsPerCrawl": 1}""");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/api/pcsx/search"))).andRespond(withSuccess("""
                {"status": 200, "data": {"count": 3, "positions": [
                  {"id": 1, "name": "New A", "locations": ["India"], "standardizedLocations": ["IN"]},
                  {"id": 2, "name": "Known B", "locations": ["India"], "standardizedLocations": ["IN"]},
                  {"id": 3, "name": "New C", "locations": ["India"], "standardizedLocations": ["IN"]}]}}""",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("position_id=1"))).andRespond(withSuccess("""
                {"status": 200, "data": {"id": 1, "name": "New A", "jobDescription": "<p>A</p>"}}""",
                MediaType.APPLICATION_JSON));                       // the only detail request: the cap is 1
        JsonNode olderB = json("{\"id\": 2, \"name\": \"Known B\", \"jobDescription\": \"<p>B</p>\"}");
        DetailCache cache = mock(DetailCache.class);
        when(cache.load(1L)).thenReturn(new DetailCache.Known(Map.of("2",
                new DetailCache.Stored("old-hash", Instant.parse("2026-09-01T00:00:00Z"), olderB)), Instant.now()));

        List<RawJob> jobs = new EightfoldAdapter(builder.build(), cache).fetchJobs(company);

        server.verify();
        assertThat(jobs).extracting(RawJob::externalId, RawJob::description, RawJob::detail).containsExactly(
                tuple("1", "<p>A</p>", RawJob.DetailSource.FETCHED),
                tuple("2", "<p>B</p>", RawJob.DetailSource.REUSED),        // too old to reuse normally, kept over nothing
                tuple("3", null, RawJob.DetailSource.NONE));                // no description: the next crawl fetches it
    }

    private static String positions(int from, int to, int total) {
        StringBuilder items = new StringBuilder();
        for (int id = from; id <= to; id++) {
            items.append(id > from ? "," : "").append("{\"id\": ").append(id)
                    .append(", \"name\": \"Job ").append(id).append("\", \"locations\": [\"India\"], ")
                    .append("\"standardizedLocations\": [\"IN\"]}");
        }
        return "{\"status\": 200, \"data\": {\"count\": " + total + ", \"positions\": [" + items + "]}}";
    }

    private static String detailOf(int id) {
        return "{\"status\": 200, \"data\": {\"id\": " + id + ", \"name\": \"Job " + id
                + "\", \"jobDescription\": \"<p>Description " + id + "</p>\"}}";
    }

    @Test
    void jobsGoToTheSinkInBatchesWhileTheCrawlRuns() {
        Company company = company("""
                {"host": "apply.careers.microsoft.com", "domain": "microsoft.com", "delayMs": 0, "saveEvery": 2}""");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/api/pcsx/search"))).andRespond(withSuccess(positions(1, 5, 5), MediaType.APPLICATION_JSON));
        for (int id = 1; id <= 5; id++) {
            server.expect(requestTo(containsString("position_id=" + id)))
                    .andRespond(withSuccess(detailOf(id), MediaType.APPLICATION_JSON));
        }
        DetailCache cache = mock(DetailCache.class);
        when(cache.load(1L)).thenReturn(DetailCache.Known.NONE);
        List<List<String>> batches = new java.util.ArrayList<>();

        List<RawJob> rest = new EightfoldAdapter(builder.build(), cache).fetchJobs(company,
                batch -> batches.add(batch.stream().map(RawJob::externalId).toList()));

        server.verify();
        assertThat(batches).containsExactly(List.of("1", "2"), List.of("3", "4"), List.of("5"));
        assertThat(rest).isEmpty();                                  // everything went to the sink
    }

    @Test
    void theCrawlStopsAfterThreeFailedDescriptionsInARowButKeepsWhatItHas() {
        Company company = company("""
                {"host": "apply.careers.microsoft.com", "domain": "microsoft.com", "delayMs": 0}""");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/api/pcsx/search"))).andRespond(withSuccess(positions(1, 6, 6), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("position_id=1"))).andRespond(withSuccess(detailOf(1), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("position_id=2"))).andRespond(withServerError());
        server.expect(requestTo(containsString("position_id=3"))).andRespond(withSuccess(detailOf(3), MediaType.APPLICATION_JSON));  // resets the count
        server.expect(requestTo(containsString("position_id=4"))).andRespond(withServerError());
        server.expect(requestTo(containsString("position_id=5"))).andRespond(withServerError());
        server.expect(requestTo(containsString("position_id=6"))).andRespond(withServerError());       // the third in a row
        DetailCache cache = mock(DetailCache.class);
        when(cache.load(1L)).thenReturn(DetailCache.Known.NONE);
        List<RawJob> sunk = new java.util.ArrayList<>();

        assertThatThrownBy(() -> new EightfoldAdapter(builder.build(), cache).fetchJobs(company, sunk::addAll))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("3 description requests in a row");

        server.verify();
        assertThat(sunk).extracting(RawJob::externalId).containsExactly("1", "2", "3", "4", "5", "6");   // saved before stopping
        assertThat(sunk).extracting(RawJob::description).containsExactly("<p>Description 1</p>", null,
                "<p>Description 3</p>", null, null, null);
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

    @Test
    void eachEightfoldCompanyIsItsOwnServer() {
        assertThat(new EightfoldAdapter(null, null).serverKey(company("""
                {"host": "careers.qualcomm.com", "domain": "qualcomm.com"}"""))).isEqualTo("careers.qualcomm.com");
    }
}
