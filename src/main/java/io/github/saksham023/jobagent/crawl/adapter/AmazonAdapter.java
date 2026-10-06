package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.isoCountry;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;

/**
 * amazon.jobs, through the JSON its own search page calls:
 * GET /en/search.json?normalized_country_code[]=IND&result_limit=100&offset=N&sort=recent
 * (allowed by robots.txt). The country filter runs on the server, and every job in the list already carries its
 * full text (description, basic and preferred qualifications), so there is no detail request: ~24 pages for ~2,300
 * India jobs. The qualifications are appended under their own headings, so the extractors see "Preferred
 * Qualifications" as a preferred section.
 */
@Component
public class AmazonAdapter implements JobBoardAdapter {

    private static final String BASE = "https://www.amazon.jobs";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 60;                          // safety stop: 6,000 postings
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter POSTED = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private final RestClient http;
    private final JsonMapper jsonMapper;

    public AmazonAdapter(RestClient crawlRestClient, JsonMapper jsonMapper) {
        this.http = crawlRestClient;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String platform() {
        return "amazon";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        AmazonConfig config = AmazonConfig.from(company);
        Map<String, RawJob> jobs = new LinkedHashMap<>();          // by id: a new posting can shift a job to the next page
        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * PAGE_SIZE;
            sleep(page == 0 ? Duration.ZERO : config.delay());
            JsonNode body = http.get().uri(URI.create(BASE + "/en/search.json?normalized_country_code[]="
                            + config.countryCode() + "&result_limit=" + PAGE_SIZE + "&offset=" + offset + "&sort=recent"))
                    .retrieve().body(JsonNode.class);
            if (body == null || !body.path("jobs").isArray()) {
                throw new IllegalStateException(company.slug() + ": Amazon response has no 'jobs' array");
            }
            for (JsonNode job : body.path("jobs")) {
                RawJob raw = toRawJob(job, jsonMapper);
                jobs.putIfAbsent(raw.externalId(), raw);
            }
            if (body.path("jobs").isEmpty() || offset + PAGE_SIZE >= body.path("hits").asInt()) {
                return new ArrayList<>(jobs.values());
            }
        }
        throw new IllegalStateException(company.slug() + ": more than " + MAX_PAGES + " pages; stopping to be safe");
    }

    static RawJob toRawJob(JsonNode job, JsonMapper jsonMapper) {
        String id = text(job, "id_icims") != null ? text(job, "id_icims") : text(job, "id");
        String path = text(job, "job_path");
        return new RawJob(
                id,
                text(job, "title"),
                text(job, "job_category"),                               // "Software Development"
                text(job, "job_family"),                                 // finer: "Software Development", "Data Center Operations"
                locations(job, jsonMapper),
                text(job, "job_schedule_type"),                          // "full-time"
                path != null ? BASE + path : BASE + "/en/jobs/" + id,
                description(job),
                postedAt(text(job, "posted_date")),
                null,
                job);
    }

    /** The description, then the basic and preferred qualifications under their own headings (HTML, with <br/>). */
    static String description(JsonNode job) {
        StringBuilder html = new StringBuilder();
        append(html, null, text(job, "description"));
        append(html, "Basic Qualifications", text(job, "basic_qualifications"));
        append(html, "Preferred Qualifications", text(job, "preferred_qualifications"));
        return html.isEmpty() ? null : html.toString();
    }

    private static void append(StringBuilder html, String heading, String body) {
        if (body == null) {
            return;
        }
        if (heading != null) {
            html.append("<h3>").append(heading).append("</h3>");
        }
        html.append("<p>").append(body).append("</p>");
    }

    /**
     * "locations" holds one JSON STRING per location with structured parts: city, state, ISO country and type
     * (ONSITE / VIRTUAL). Virtual jobs read "Bangalore - Virtual" or just "Virtual" with a state.
     */
    static List<RawLocation> locations(JsonNode job, JsonMapper jsonMapper) {
        List<RawLocation> locations = new ArrayList<>();
        for (JsonNode entry : job.path("locations")) {
            JsonNode location = entry.isString() ? jsonMapper.readTree(entry.asString()) : entry;
            String display = text(location, "normalizedLocation");
            String region = text(location, "normalizedStateName");
            String country = isoCountry(text(location, "countryIso2a"));
            if (display == null && region == null && country == null) {
                continue;
            }
            locations.add(new RawLocation(display, text(location, "normalizedCityName"), region, country,
                    "VIRTUAL".equalsIgnoreCase(text(location, "type"))));
        }
        if (locations.isEmpty() && text(job, "normalized_location") != null) {
            locations.add(RawLocation.ofText(text(job, "normalized_location")));
        }
        return locations;
    }

    /** "October  6, 2026" (Amazon pads the day with a space) = that day in India; anything else = null. */
    static Instant postedAt(String date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip().replaceAll("\\s+", " "), POSTED).atStartOfDay(INDIA).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while crawling", e);
        }
    }

    /**
     * The typed view of companies.config for the Amazon row: {"countryCode": "IND"} (amazon.jobs uses ISO alpha-3),
     * plus optional "delayMs" (pause between pages, default 1000).
     */
    record AmazonConfig(String countryCode, Duration delay) {

        static AmazonConfig from(Company company) {
            String country = text(company.config(), "countryCode");
            JsonNode delayMs = company.config().path("delayMs");
            return new AmazonConfig(country != null ? country : "IND",
                    Duration.ofMillis(delayMs.isNumber() ? delayMs.asLong() : 1000));
        }
    }
}