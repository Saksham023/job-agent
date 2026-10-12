package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.crawl.HtmlToText;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The Apple adapter without the network: pages cut down from jobs.apple.com/en-in (2026-10-12). */
class AppleAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AppleAdapter ADAPTER = new AppleAdapter(null, null, null, JSON);
    private static final AppleAdapter.AppleConfig CONFIG = new AppleAdapter.AppleConfig("en-in", "india-INDC", null);

    @Test
    void readsTheDataThePageEmbeds() {
        JsonNode search = ADAPTER.hydration(null, page("apple-search.html")).path("loaderData").path("search");

        assertThat(search.path("totalRecords").asInt()).isEqualTo(6);
        assertThat(search.path("searchResults")).hasSize(6);
    }

    @Test
    void oneRoleInSeveralCitiesIsOneJobWithEveryCity() {
        List<List<JsonNode>> roles = AppleAdapter.roles(entries());

        assertThat(roles).hasSize(3);
        List<JsonNode> leader = roles.stream().filter(r -> r.size() > 1).findFirst().orElseThrow();
        RawJob job = AppleAdapter.toRawJob(CONFIG, leader, null);

        assertThat(job.externalId()).isEqualTo("200682703");
        assertThat(job.title()).isEqualTo("Employee & Labour Relations Leader, India");
        assertThat(job.department()).isEqualTo("Corporate Functions");
        assertThat(job.locations()).extracting(RawLocation::city).containsExactly("Bengaluru", "Gurugram", "Hyderabad", "Mumbai");
        assertThat(job.locations()).extracting(RawLocation::text).allMatch(text -> text.endsWith("India"));
        assertThat(job.url()).isEqualTo("https://jobs.apple.com/en-in/details/200682703-0321/"
                + "employee-labour-relations-leader-india");
        assertThat(job.description()).isNotBlank();                 // the list's summary when there is no detail
    }

    @Test
    void theJobPageGivesTheFullTextWithItsHeadingsAndBullets() {
        JsonNode detail = ADAPTER.hydration(null, page("apple-detail.html")).path("loaderData").path("jobDetails").path("jobsData");
        List<JsonNode> java = AppleAdapter.roles(entries()).stream()
                .filter(r -> "200687867".equals(r.getFirst().path("positionId").asString())).findFirst().orElseThrow();

        RawJob job = AppleAdapter.toRawJob(CONFIG, java, detail);

        assertThat(job.externalId()).isEqualTo("200687867");
        assertThat(job.department()).isEqualTo("Software and Services");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2026-10-08T17:43:48.104Z"));
        assertThat(job.locations()).extracting(RawLocation::text).containsExactly("Hyderabad, India");
        String text = HtmlToText.convert(job.description());
        assertThat(text).contains("Description", "Responsibilities", "Minimum Qualifications", "Preferred Qualifications",
                "- 8–12 years of professional software engineering experience",
                "- Design and develop highly scalable and reliable backend services using Java and Spring Boot.");
        assertThat(job.raw()).isSameAs(detail);                      // stored, so later crawls can reuse it
    }

    @Test
    void storeRolesNameOnlyTheCountryAndLinkToTheirPositionNumber() {
        List<JsonNode> genius = AppleAdapter.roles(entries()).stream()
                .filter(r -> "IN-Genius".equals(r.getFirst().path("postingTitle").asString())).findFirst().orElseThrow();

        RawJob job = AppleAdapter.toRawJob(CONFIG, genius, null);

        assertThat(job.department()).isEqualTo("Apple Retail");
        assertThat(job.locations()).extracting(RawLocation::text).containsExactly("India");
        assertThat(job.url()).isEqualTo("https://jobs.apple.com/en-in/details/200314015/in-genius");
        assertThat(job.postedAt()).isNull();                         // its "posting time" is just the request's time
    }

    @Test
    void aStoreRolesFingerprintIgnoresItsEverChangingPostingTime() {
        List<JsonNode> genius = AppleAdapter.roles(entries()).stream()
                .filter(r -> "IN-Genius".equals(r.getFirst().path("postingTitle").asString())).findFirst().orElseThrow();
        JsonNode later = ((tools.jackson.databind.node.ObjectNode) genius.getFirst().deepCopy())
                .put("postDateInGMT", "2026-10-12T11:58:18.727192689Z");

        assertThat(AppleAdapter.listHash(List.of(later))).isEqualTo(AppleAdapter.listHash(genius));
    }

    @Test
    void theListFingerprintChangesWhenACityIsAddedButNotWhenTheSummaryIsEdited() {
        List<JsonNode> leader = AppleAdapter.roles(entries()).stream().filter(r -> r.size() > 1).findFirst().orElseThrow();
        String before = AppleAdapter.listHash(leader);

        List<JsonNode> edited = new ArrayList<>(leader);
        edited.set(0, ((tools.jackson.databind.node.ObjectNode) leader.getFirst().deepCopy()).put("jobSummary", "new text"));
        assertThat(AppleAdapter.listHash(edited)).isEqualTo(before);
        assertThat(AppleAdapter.listHash(leader.subList(0, 3))).isNotEqualTo(before);
    }

    private static List<JsonNode> entries() {
        List<JsonNode> entries = new ArrayList<>();
        ADAPTER.hydration(null, page("apple-search.html")).path("loaderData").path("search").path("searchResults")
                .forEach(entries::add);
        return entries;
    }

    static String page(String name) {
        try (InputStream in = AppleAdapterTest.class.getResourceAsStream("/crawl/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
