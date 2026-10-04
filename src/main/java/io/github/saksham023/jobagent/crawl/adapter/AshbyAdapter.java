package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.toInstant;

/**
 * Ashby public Job Posting API (https://developers.ashbyhq.com/docs/public-job-posting-api).
 * One GET returns every posting with its HTML description. Each posting has a primary location plus
 * secondaryLocations, each with a postal address whose country is a NAME ("India"), not an ISO code,
 * so addresses are passed to the location parser as text.
 */
@Component
public class AshbyAdapter implements JobBoardAdapter {

    private static final String BOARD_URL = "https://api.ashbyhq.com/posting-api/job-board/{boardName}";

    private final RestClient http;

    public AshbyAdapter(RestClient crawlRestClient) {
        this.http = crawlRestClient;
    }

    @Override
    public String platform() {
        return "ashby";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        AshbyConfig config = AshbyConfig.from(company);

        JsonNode body = http.get()
                .uri(BOARD_URL, config.boardName())
                .retrieve()
                .body(JsonNode.class);

        if (body == null || !body.path("jobs").isArray()) {
            throw new IllegalStateException(company.slug() + ": Ashby response has no 'jobs' array");
        }

        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode posting : body.path("jobs")) {
            if (posting.path("isListed").asBoolean(true)) {          // unlisted postings are not public
                jobs.add(toRawJob(posting));
            }
        }
        return jobs;
    }

    private RawJob toRawJob(JsonNode posting) {
        String department = text(posting, "department");
        return new RawJob(
                text(posting, "id"),
                text(posting, "title"),
                department != null ? department : text(posting, "team"),
                locations(posting),
                text(posting, "employmentType"),                    // "FullTime", "Intern", ...
                text(posting, "jobUrl"),
                text(posting, "descriptionHtml"),
                toInstant(text(posting, "publishedAt")),
                null,
                posting
        );
    }

    /** The primary location plus every secondary one, all with the job's remote flag. */
    private static List<RawLocation> locations(JsonNode posting) {
        JsonNode remoteNode = posting.path("isRemote");
        Boolean remote = remoteNode.isBoolean() ? remoteNode.asBoolean() : null;

        List<RawLocation> locations = new ArrayList<>();
        addLocation(locations, posting, remote);
        for (JsonNode secondary : posting.path("secondaryLocations")) {
            addLocation(locations, secondary, remote);
        }
        return locations;
    }

    /**
     * Prefers the postal address as "Bengaluru, Karnataka, India" (it carries the country);
     * falls back to the plain location label when there is no address.
     */
    private static void addLocation(List<RawLocation> target, JsonNode node, Boolean remote) {
        JsonNode address = node.path("address").path("postalAddress");
        String fromAddress = Stream.of(
                        text(address, "addressLocality"),
                        text(address, "addressRegion"),
                        text(address, "addressCountry"))
                .filter(Objects::nonNull)
                .distinct()                                         // "Delhi, Delhi, India" -> "Delhi, India"
                .collect(Collectors.joining(", "));
        String text = fromAddress.isEmpty() ? text(node, "location") : fromAddress;
        if (text != null) {
            target.add(new RawLocation(text, null, null, null, remote));
        }
    }

    /** The typed view of companies.config for Ashby rows: {"boardName": "..."}. */
    record AshbyConfig(String boardName) {

        static AshbyConfig from(Company company) {
            String boardName = text(company.config(), "boardName");
            if (boardName == null) {
                throw new IllegalArgumentException(company.slug() + ": config.boardName is missing");
            }
            return new AshbyConfig(boardName);
        }
    }
}