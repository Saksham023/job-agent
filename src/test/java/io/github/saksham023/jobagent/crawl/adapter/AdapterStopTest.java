package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.common.ShutdownSignal;
import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.CrawlStoppedException;
import io.github.saksham023.jobagent.crawl.DetailCache;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.geo.Gazetteer;
import io.github.saksham023.jobagent.geo.LocationParser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Workday, Oracle, SmartRecruiters and Amazon hand their jobs over in batches and stop when the app shuts down: the jobs
 * finished so far reach the sink, no further request is made. Each test runs a short crawl against a mock server and
 * raises the shutdown flag while the answer to the second request is being delivered.
 */
class AdapterStopTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Company company(String platform, String config) {
        return new Company(1, "acme", "Acme", platform, JSON.readTree(config), null, true, null, null, null);
    }

    private static DetailCache emptyCache() {
        DetailCache cache = mock(DetailCache.class);
        when(cache.load(1L)).thenReturn(DetailCache.Known.NONE);
        return cache;
    }

    private static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    /** The answer, delivered after the app has been told to stop. */
    private static ResponseCreator thenStop(ShutdownSignal shutdown, ResponseCreator answer) {
        return request -> {
            shutdown.stop();
            return answer.createResponse(request);
        };
    }

    private static List<String> ids(List<RawJob> jobs) {
        return jobs.stream().map(RawJob::externalId).toList();
    }

    // ---------------------------------------------------------------- SmartRecruiters

    private static String srList() {
        return "{\"totalFound\": 3, \"content\": [{\"id\": \"1\", \"name\": \"A\"}, {\"id\": \"2\", \"name\": \"B\"}, {\"id\": \"3\", \"name\": \"C\"}]}";
    }

    private static String srDetail(String id) {
        return "{\"id\": \"" + id + "\", \"name\": \"Job " + id + "\", \"jobAd\": {\"sections\": {\"jobDescription\": {\"text\": \"<p>d" + id + "</p>\"}}}}";
    }

    @Test
    void smartRecruitersStopsAfterTheDetailInProgressAndKeepsTheJobsDone() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ShutdownSignal shutdown = new ShutdownSignal();
        server.expect(requestTo(containsString("/companies/ACME/postings?country=in"))).andRespond(json(srList()));
        server.expect(requestTo(containsString("/postings/1"))).andRespond(json(srDetail("1")));
        server.expect(requestTo(containsString("/postings/2"))).andRespond(thenStop(shutdown, json(srDetail("2"))));
        List<RawJob> sunk = new ArrayList<>();

        assertThatThrownBy(() -> new SmartRecruitersAdapter(builder.build(), emptyCache(), shutdown)
                .fetchJobs(company("smartrecruiters", "{\"companyId\": \"ACME\"}"), sunk::addAll))
                .isInstanceOf(CrawlStoppedException.class);

        server.verify();                                            // job 3's detail was never requested
        assertThat(ids(sunk)).containsExactly("1", "2");
    }

    @Test
    void smartRecruitersWithoutAStopDeliversEverythingAndReturnsNothingExtra() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/postings?country=in"))).andRespond(json(srList()));
        for (String id : List.of("1", "2", "3")) {
            server.expect(requestTo(containsString("/postings/" + id))).andRespond(json(srDetail(id)));
        }
        SmartRecruitersAdapter adapter = new SmartRecruitersAdapter(builder.build(), emptyCache(), new ShutdownSignal());

        List<RawJob> all = adapter.fetchJobs(company("smartrecruiters", "{\"companyId\": \"ACME\"}"));

        server.verify();
        assertThat(ids(all)).containsExactly("1", "2", "3");
    }

    // ---------------------------------------------------------------- Oracle

    private static String oracleSummary(String id) {
        return "{\"Id\": \"" + id + "\", \"Title\": \"Engineer " + id + "\", \"PostedDate\": \"2026-10-06\", "
                + "\"PrimaryLocationCountry\": \"IN\", \"PrimaryLocation\": \"Bengaluru, Karnataka, India\", \"secondaryLocations\": []}";
    }

    private static String oracleDetail(String id) {
        return "{\"items\": [{\"Id\": \"" + id + "\", \"ExternalDescriptionStr\": \"<p>d" + id + "</p>\"}]}";
    }

    @Test
    void oracleStopsAfterTheDetailInProgressAndKeepsTheJobsDone() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ShutdownSignal shutdown = new ShutdownSignal();
        server.expect(requestTo(containsString("recruitingCEJobRequisitions?")))
                .andRespond(json("{\"items\": [{\"TotalJobsCount\": 3, \"requisitionList\": ["
                        + oracleSummary("1") + "," + oracleSummary("2") + "," + oracleSummary("3") + "]}]}"));
        server.expect(requestTo(containsString("Id=%221%22"))).andRespond(json(oracleDetail("1")));
        server.expect(requestTo(containsString("Id=%222%22"))).andRespond(thenStop(shutdown, json(oracleDetail("2"))));
        List<RawJob> sunk = new ArrayList<>();

        assertThatThrownBy(() -> new OracleAdapter(builder.build(), emptyCache(), shutdown).fetchJobs(company("oracle",
                "{\"host\": \"acme.fa.oraclecloud.com\", \"siteNumber\": \"CX\", \"locationId\": \"123\", \"delayMs\": 0}"),
                sunk::addAll)).isInstanceOf(CrawlStoppedException.class);

        server.verify();
        assertThat(ids(sunk)).containsExactly("1", "2");
    }

    @Test
    void aStopDuringOraclesPaceWaitEndsTheCrawlAtOnce() {
        RestClient.Builder builder = RestClient.builder();
        ShutdownSignal shutdown = new ShutdownSignal();
        shutdown.stop();

        assertThatThrownBy(() -> new OracleAdapter(builder.build(), emptyCache(), shutdown).fetchJobs(company("oracle",
                "{\"host\": \"acme.fa.oraclecloud.com\", \"siteNumber\": \"CX\", \"locationId\": \"123\", \"delayMs\": 60000}"),
                batch -> { })).isInstanceOf(CrawlStoppedException.class);          // no request, no 60 s wait
    }

    // ---------------------------------------------------------------- Workday

    private static String workdayPostings() {
        return "[{\"title\": \"A\", \"externalPath\": \"/job/Pune/A_R1\"}, {\"title\": \"B\", \"externalPath\": \"/job/Pune/B_R2\"},"
                + " {\"title\": \"C\", \"externalPath\": \"/job/Pune/C_R3\"}]";
    }

    private static String workdayDetail(String title) {
        return "{\"jobPostingInfo\": {\"title\": \"" + title + "\", \"location\": \"Pune, India\", "
                + "\"jobRequisitionLocation\": {\"country\": {\"alpha2Code\": \"IN\"}}, "
                + "\"externalUrl\": \"https://acme.wd1.myworkdayjobs.com/External/job/Pune/x\", \"jobDescription\": \"<p>d</p>\"}}";
    }

    @Test
    void workdayStopsAfterTheDetailInProgressAndKeepsTheJobsDone() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ShutdownSignal shutdown = new ShutdownSignal();
        String countryFacets = "[{\"facetParameter\": \"locationMainGroup\", \"values\": [{\"facetParameter\": \"locationCountry\", "
                + "\"descriptor\": \"Country\", \"values\": [{\"descriptor\": \"India\", \"id\": \"IND\", \"count\": 3}]}]}]";
        server.expect(method(HttpMethod.POST)).andExpect(requestTo(containsString("/wday/cxs/acme/External/jobs")))
                .andRespond(json("{\"total\": 3, \"jobPostings\": [], \"facets\": " + countryFacets + "}"));   // facet discovery
        server.expect(method(HttpMethod.POST)).andExpect(requestTo(containsString("/jobs")))
                .andRespond(json("{\"total\": 3, \"jobPostings\": " + workdayPostings() + ", \"facets\": []}")); // first page
        server.expect(method(HttpMethod.POST)).andExpect(requestTo(containsString("/jobs")))
                .andRespond(json("{\"total\": 3, \"jobPostings\": " + workdayPostings() + "}"));                // the listing
        server.expect(requestTo(containsString("/job/Pune/A_R1"))).andRespond(json(workdayDetail("A")));
        server.expect(requestTo(containsString("/job/Pune/B_R2"))).andRespond(thenStop(shutdown, json(workdayDetail("B"))));
        List<RawJob> sunk = new ArrayList<>();

        assertThatThrownBy(() -> new WorkdayAdapter(builder.build(), new LocationParser(new Gazetteer()), emptyCache(), shutdown)
                .fetchJobs(company("workday", "{\"host\": \"acme.wd1.myworkdayjobs.com\", \"tenant\": \"acme\", \"site\": \"External\"}"),
                        sunk::addAll)).isInstanceOf(CrawlStoppedException.class);

        server.verify();                                            // job C's detail was never requested
        assertThat(ids(sunk)).containsExactly("A_R1", "B_R2");
    }

    // ---------------------------------------------------------------- Amazon

    private static final String AMAZON_JOB = """
            {"id": "x", "id_icims": "ID", "title": "Software Development Engineer II", "job_category": "Software Development",
             "job_schedule_type": "full-time", "job_path": "/en/jobs/ID/sde", "posted_date": "October  6, 2026",
             "description": "Build.", "normalized_location": "Bengaluru, Karnataka, IND",
             "locations": ["{\\"normalizedStateName\\":\\"Karnataka\\",\\"countryIso2a\\":\\"IN\\",\\"normalizedCityName\\":\\"Bengaluru\\",\\"type\\":\\"ONSITE\\",\\"normalizedLocation\\":\\"Bengaluru, Karnataka, IND\\"}"]}""";

    private static String amazonPage(String id, int hits) {
        return "{\"hits\": " + hits + ", \"jobs\": [" + AMAZON_JOB.replace("ID", id) + "]}";
    }

    @Test
    void amazonHandsOverOnePagePerBatchAndSkipsAJobSeenOnAnEarlierPage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("offset=0"))).andRespond(json(amazonPage("1", 150)));
        server.expect(requestTo(containsString("offset=100")))
                .andRespond(json("{\"hits\": 150, \"jobs\": [" + AMAZON_JOB.replace("ID", "1") + "," + AMAZON_JOB.replace("ID", "2") + "]}"));
        List<List<String>> batches = new ArrayList<>();

        List<RawJob> rest = new AmazonAdapter(builder.build(), JSON, new ShutdownSignal()).fetchJobs(
                company("amazon", "{\"delayMs\": 0}"), batch -> batches.add(ids(batch)));

        server.verify();
        assertThat(batches).containsExactly(List.of("1"), List.of("2"));    // job 1 appeared on both pages: handed over once
        assertThat(rest).isEmpty();
    }

    @Test
    void amazonStopsBetweenPagesAndKeepsTheFirstPage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ShutdownSignal shutdown = new ShutdownSignal();
        server.expect(requestTo(containsString("offset=0"))).andRespond(thenStop(shutdown, json(amazonPage("1", 250))));
        List<List<String>> batches = new ArrayList<>();

        assertThatThrownBy(() -> new AmazonAdapter(builder.build(), JSON, shutdown).fetchJobs(
                company("amazon", "{\"delayMs\": 0}"), batch -> batches.add(ids(batch))))
                .isInstanceOf(CrawlStoppedException.class);

        server.verify();                                            // the second page was never requested
        assertThat(batches).containsExactly(List.of("1"));
    }
}
