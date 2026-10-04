package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.addIfPresent;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.toInstant;

/**
 * Greenhouse public Job Board API (https://developers.greenhouse.io/job-board.html).
 * One GET returns every job on the board: no pagination and no server-side location filter.
 * Locations are free text only (e.g. "Bangalore, IND; Mohali, IND"); the location parser interprets them.
 */
@Component
public class GreenhouseAdapter implements JobBoardAdapter {

    private static final String JOBS_URL =
            "https://boards-api.greenhouse.io/v1/boards/{boardToken}/jobs?content=true";

    private final RestClient http;

    public GreenhouseAdapter(RestClient crawlRestClient) {
        this.http = crawlRestClient;
    }

    @Override
    public String platform() {
        return "greenhouse";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        GreenhouseConfig config = GreenhouseConfig.from(company);

        JsonNode body = http.get()
                .uri(JOBS_URL, config.boardToken())
                .retrieve()
                .body(JsonNode.class);

        if (body == null || !body.path("jobs").isArray()) {
            throw new IllegalStateException(company.slug() + ": Greenhouse response has no 'jobs' array");
        }

        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode job : body.path("jobs")) {
            jobs.add(toRawJob(job));
        }
        return jobs;
    }

    private RawJob toRawJob(JsonNode job) {
        return new RawJob(
                text(job, "id"),
                text(job, "title"),
                firstDepartment(job),
                null,                                   // no structured job function
                locations(job),
                null,                                   // Greenhouse has no standard employment-type field
                text(job, "absolute_url"),
                text(job, "content"),                   // HTML-escaped HTML; the normalizer cleans it
                toInstant(text(job, "first_published")),
                toInstant(text(job, "updated_at")),
                job
        );
    }

    /**
     * location.name plus every office location, as free-text RawLocations, without duplicate texts.
     * Greenhouse has no structured city/country, so only `text` is set.
     */
    private static List<RawLocation> locations(JsonNode job) {
        Set<String> texts = new LinkedHashSet<>();
        addIfPresent(texts, text(job.path("location"), "name"));
        for (JsonNode office : job.path("offices")) {
            addIfPresent(texts, text(office, "location"));
        }
        return texts.stream()
                .map(RawLocation::ofText)
                .toList();
    }

    private static String firstDepartment(JsonNode job) {
        JsonNode departments = job.path("departments");
        return departments.isEmpty() ? null : text(departments.path(0), "name");
    }

    /** The typed view of companies.config for Greenhouse rows: {"boardToken": "..."}. */
    record GreenhouseConfig(String boardToken) {

        static GreenhouseConfig from(Company company) {
            String token = text(company.config(), "boardToken");
            if (token == null) {
                throw new IllegalArgumentException(company.slug() + ": config.boardToken is missing");
            }
            return new GreenhouseConfig(token);
        }
    }
}