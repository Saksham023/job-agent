package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.DetailCache;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.jsoup.nodes.Entities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.isoCountry;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.toInstant;

/**
 * SmartRecruiters public Posting API (https://developers.smartrecruiters.com/docs/posting-api).
 * The list endpoint filters by country on the server and pages with limit/offset/totalFound, but has no
 * descriptions: each posting needs one detail request (jobAd.sections), sent with a polite pause in between.
 * Locations are fully structured (city, region code, ISO country, remote).
 */
@Component
public class SmartRecruitersAdapter implements JobBoardAdapter {

    private static final Logger log = LoggerFactory.getLogger(SmartRecruitersAdapter.class);

    private static final String LIST_URL =
            "https://api.smartrecruiters.com/v1/companies/{companyId}/postings?country={country}&limit={limit}&offset={offset}";
    private static final String DETAIL_URL =
            "https://api.smartrecruiters.com/v1/companies/{companyId}/postings/{postingId}";

    private static final int PAGE_SIZE = 100;                              // the API maximum
    private static final int MAX_PAGES = 50;                               // safety stop: 5,000 postings
    private static final Duration DETAIL_DELAY = Duration.ofMillis(250);   // politeness between detail calls

    /** Job-ad sections in reading order. companyDescription is left out: the same boilerplate on every job. */
    private static final List<String> SECTIONS = List.of("jobDescription", "qualifications", "additionalInformation");

    private final RestClient http;

    private final DetailCache detailCache;

    public SmartRecruitersAdapter(RestClient crawlRestClient, DetailCache detailCache) {
        this.http = crawlRestClient;
        this.detailCache = detailCache;
    }

    @Override
    public String platform() {
        return "smartrecruiters";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        SmartRecruitersConfig config = SmartRecruitersConfig.from(company);

        DetailCache.Known known = detailCache.load(company.id());
        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode summary : listPostings(company, config)) {
            String listHash = listHash(summary);
            JsonNode stored = known.reusable(text(summary, "id"), listHash);      // stored raw = the detail
            if (stored != null) {
                jobs.add(toRawJob(config, stored, stored).withDetail(listHash, RawJob.DetailSource.REUSED));
                continue;
            }
            JsonNode detail = fetchDetail(company, config, text(summary, "id"));
            jobs.add(toRawJob(config, detail != null ? detail : summary, detail).withDetail(listHash,
                    detail == null ? RawJob.DetailSource.NONE : RawJob.DetailSource.FETCHED));
            pause();
        }
        return jobs;
    }

    /** Every page of the list endpoint, until offset reaches totalFound. */
    private List<JsonNode> listPostings(Company company, SmartRecruitersConfig config) {
        List<JsonNode> postings = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = http.get()
                    .uri(LIST_URL, config.companyId(), config.country(), PAGE_SIZE, offset)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !body.path("content").isArray()) {
                throw new IllegalStateException(company.slug() + ": SmartRecruiters response has no 'content' array");
            }

            JsonNode content = body.path("content");
            content.forEach(postings::add);
            offset += content.size();

            if (content.isEmpty() || offset >= body.path("totalFound").asInt()) {
                return postings;
            }
        }
        throw new IllegalStateException(company.slug() + ": more than " + MAX_PAGES + " pages; stopping to be safe");
    }

    /** The full posting with its job ad, or null if it cannot be fetched (e.g. closed since the list call). */
    private JsonNode fetchDetail(Company company, SmartRecruitersConfig config, String postingId) {
        try {
            return http.get()
                    .uri(DETAIL_URL, config.companyId(), postingId)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("{}: no detail for posting {} ({}); keeping it without a description",
                    company.slug(), postingId, e.getMessage());
            return null;
        }
    }

    /** @param posting the detail when available, else the list summary; @param detail may be null */
    /** The list entry's stable fields. */
    static String listHash(JsonNode summary) {
        return DetailCache.fingerprint(summary.path("name"), summary.path("location"), summary.path("releasedDate"),
                summary.path("department"), summary.path("function"), summary.path("typeOfEmployment"));
    }

    private RawJob toRawJob(SmartRecruitersConfig config, JsonNode posting, JsonNode detail) {
        String id = text(posting, "id");
        String department = text(posting.path("department"), "label");
        String url = text(posting, "postingUrl");
        return new RawJob(
                id,
                text(posting, "name"),
                department != null ? department : text(posting.path("function"), "label"),
                text(posting.path("function"), "label"),          // "Engineering", "Human Resources"...
                locations(posting.path("location")),
                text(posting.path("typeOfEmployment"), "label"),
                url != null ? url : "https://jobs.smartrecruiters.com/" + config.companyId() + "/" + id,
                detail == null ? null : descriptionHtml(detail),
                toInstant(text(posting, "releasedDate")),
                null,
                posting
        );
    }

    /** One structured location: "Hyderabad, TS, India" + city + region code + ISO country + remote. */
    private static List<RawLocation> locations(JsonNode location) {
        String text = text(location, "fullLocation");
        String city = text(location, "city");
        String region = text(location, "region");
        String country = isoCountry(text(location, "country"));
        JsonNode remoteNode = location.path("remote");
        Boolean remote = remoteNode.isBoolean() ? remoteNode.asBoolean() : null;

        if (text == null && city == null && region == null && country == null) {
            return List.of();
        }
        return List.of(new RawLocation(text, city, region, country, remote));
    }

    /** The job-ad sections as one HTML string: a heading per section, then its HTML. */
    private static String descriptionHtml(JsonNode detail) {
        JsonNode sections = detail.path("jobAd").path("sections");
        StringBuilder html = new StringBuilder();
        for (String name : SECTIONS) {
            JsonNode section = sections.path(name);
            String body = text(section, "text");
            if (body == null) {
                continue;
            }
            String title = text(section, "title");
            if (title != null) {
                html.append("<h3>").append(Entities.escape(title)).append("</h3>");
            }
            html.append(body);
        }
        return html.isEmpty() ? null : html.toString();
    }

    private static void pause() {
        try {
            Thread.sleep(DETAIL_DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while crawling", e);
        }
    }

    /**
     * The typed view of companies.config for SmartRecruiters rows: {"companyId": "...", "country": "in"}.
     * country is optional (default "in") and is the server-side filter.
     */
    record SmartRecruitersConfig(String companyId, String country) {

        static SmartRecruitersConfig from(Company company) {
            String companyId = text(company.config(), "companyId");
            if (companyId == null) {
                throw new IllegalArgumentException(company.slug() + ": config.companyId is missing");
            }
            String country = text(company.config(), "country");
            return new SmartRecruitersConfig(companyId, country != null ? country : "in");
        }
    }
}