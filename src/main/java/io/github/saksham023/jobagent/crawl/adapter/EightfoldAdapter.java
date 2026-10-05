package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.isoCountry;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.value;

/**
 * Eightfold career sites (Microsoft, Qualcomm...) through the "PCSX" API their public pages call:
 * GET /api/pcsx/search?domain=&lt;company domain&gt;&amp;location=India&amp;start=N lists 10 jobs per page (the
 * location filter runs on the server), GET /api/pcsx/position_details?position_id=... gives one job with its
 * description. Both are allowed by the sites' robots.txt ("Allow: /api/pcsx").
 *
 * Politeness: the pace ADAPTS during each crawl (Pace). It starts at one request per config.delayMs (default 1 s);
 * every 429 Too Many Requests, or a refused / timed-out connection (what Microsoft does after a few 429s), slows it
 * by one more second for the rest of the crawl, and the same request is retried after a cool-down, so no job is
 * skipped. Only after MAX_THROTTLED_TRIES signals in a row for one request does it give up (a real block must not be
 * hammered): a failed list page fails the crawl, a failed detail keeps the job without a description.
 * Optional config.detailDepartments (a regex, case-insensitive) limits the detail requests to jobs whose department
 * matches; every other job is still saved from the list (title, department, location, date), just without a
 * description. It is data, not code: change it per company in companies.config.
 * Tenants that need a session cookie and CSRF token (Morgan Stanley, UKG) are not supported yet.
 */
@Component
public class EightfoldAdapter implements JobBoardAdapter {

    private static final Logger log = LoggerFactory.getLogger(EightfoldAdapter.class);

    private static final int MAX_PAGES = 300;                              // safety stop: 3,000 postings
    static final Duration SLOW_DOWN_STEP = Duration.ofSeconds(1);          // each throttle signal adds this per request
    static final Duration MAX_DELAY = Duration.ofSeconds(15);
    static final Duration COOL_DOWN = Duration.ofSeconds(10);              // x the signals in a row, before the retry
    static final int MAX_THROTTLED_TRIES = 5;

    /**
     * The pause between requests for ONE crawl: starts at the config delay and only grows (by SLOW_DOWN_STEP per
     * throttle signal, up to MAX_DELAY). Not shared between crawls: each crawl starts fast again.
     */
    static final class Pace {

        private Duration delay;

        Pace(Duration start) {
            this.delay = start;
        }

        Duration delay() {
            return delay;
        }

        /** @return the new delay */
        Duration slowDown() {
            Duration slower = delay.plus(SLOW_DOWN_STEP);
            delay = slower.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : slower;
            return delay;
        }
    }

    private final RestClient http;

    public EightfoldAdapter(RestClient crawlRestClient) {
        this.http = crawlRestClient;
    }

    @Override
    public String platform() {
        return "eightfold";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        EightfoldConfig config = EightfoldConfig.from(company);
        Pace pace = new Pace(config.delay());
        List<RawJob> jobs = new ArrayList<>();
        int listOnly = 0;
        for (JsonNode summary : listPositions(company, config, pace)) {
            boolean wanted = config.wantsDetail(text(summary, "department"));
            JsonNode detail = wanted ? fetchDetail(company, config, pace, text(summary, "id")) : null;
            listOnly += wanted ? 0 : 1;
            jobs.add(toRawJob(config, summary, detail));
        }
        log.info("{}: {} jobs, {} saved without a detail (detailDepartments); crawl finished at one request every {} s",
                company.slug(), jobs.size(), listOnly, pace.delay().toMillis() / 1000.0);
        return jobs;
    }

    // ---------------------------------------------------------------- requests

    /** Every page of the search, until start reaches data.count. */
    private List<JsonNode> listPositions(Company company, EightfoldConfig config, Pace pace) {
        List<JsonNode> positions = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            URI uri = URI.create(config.base() + "/api/pcsx/search?domain=" + encode(config.domain()) + "&query=&location="
                    + encode(config.location()) + "&start=" + positions.size());
            JsonNode data = data(company, get(company, pace, uri));
            JsonNode items = data.path("positions");
            items.forEach(positions::add);
            if (items.isEmpty() || positions.size() >= data.path("count").asInt()) {
                return positions;
            }
        }
        throw new IllegalStateException(company.slug() + ": more than " + MAX_PAGES + " pages; stopping to be safe");
    }

    /** The job's detail ("data" object), or null when it cannot be fetched; the job is then kept without a description. */
    private JsonNode fetchDetail(Company company, EightfoldConfig config, Pace pace, String positionId) {
        URI uri = URI.create(config.base() + "/api/pcsx/position_details?position_id=" + encode(positionId)
                + "&domain=" + encode(config.domain()) + "&hl=en");
        try {
            return data(company, get(company, pace, uri));
        } catch (RuntimeException e) {
            log.warn("{}: no detail for position {} ({}); keeping it without a description", company.slug(), positionId, e.getMessage());
            return null;
        }
    }

    /** One polite GET at the crawl's current pace. */
    private JsonNode get(Company company, Pace pace, URI uri) {
        return politely(company.slug(), pace, COOL_DOWN, () -> http.get().uri(uri).retrieve().body(JsonNode.class));
    }

    /**
     * Waits the pace, then runs the call. A throttle signal (429, or a refused / timed-out connection) slows the pace,
     * waits coolDown x the signals so far, and retries the same call; after MAX_THROTTLED_TRIES signals in a row the
     * last one is thrown. Other errors (403, 404, bad JSON) are never retried.
     */
    static <T> T politely(String slug, Pace pace, Duration coolDown, Supplier<T> call) {
        for (int signals = 1; ; signals++) {
            sleep(pace.delay());
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests | ResourceAccessException e) {
                if (signals >= MAX_THROTTLED_TRIES) {
                    throw e;
                }
                Duration slower = pace.slowDown();
                Duration wait = coolDown.multipliedBy(signals);
                log.info("{}: throttled ({}); now one request every {} s, retrying in {} s", slug,
                        e instanceof ResourceAccessException ? "connection failed" : "429", slower.toMillis() / 1000.0,
                        wait.toSeconds());
                sleep(wait);
            }
        }
    }

    /** The "data" object; PCSX also puts a status code in the body. */
    private static JsonNode data(Company company, JsonNode body) {
        if (body == null || !body.path("data").isObject() || body.path("status").asInt(200) != 200) {
            throw new IllegalStateException(company.slug() + ": unexpected Eightfold response "
                    + (body == null ? "(empty)" : body.path("error").toString()));
        }
        return body.path("data");
    }

    // ---------------------------------------------------------------- mapping

    /** @param detail the position_details data, or null (then the search summary is used, without a description) */
    static RawJob toRawJob(EightfoldConfig config, JsonNode summary, JsonNode detail) {
        JsonNode job = detail != null ? detail : summary;
        String url = text(job, "publicUrl");
        String path = text(job, "positionUrl");
        JsonNode posted = job.path("postedTs");
        return new RawJob(
                text(job, "id"),
                text(job, "name"),
                text(job, "department"),
                null,
                locations(job),
                firstText(job.path("efcustomTextEmploymentType")),      // Microsoft only: ["Full-Time"]
                url != null ? url : config.base() + (path != null ? path : "/careers/job/" + text(job, "id")),
                detail == null ? null : text(detail, "jobDescription"),
                posted.isNumber() ? Instant.ofEpochSecond(posted.asLong()) : null,
                null,
                job
        );
    }

    /**
     * standardizedLocations ("Hyderabad, TS, IN", or just "IN") end with the ISO country code; the display texts
     * in "locations" ("Hyderabad, Telangāna, India") are used when there is one per standardized location.
     */
    static List<RawLocation> locations(JsonNode job) {
        JsonNode standardized = job.path("standardizedLocations");
        JsonNode display = job.path("locations");
        String option = text(job, "workLocationOption");                  // onsite / hybrid / remote
        Boolean remote = option == null ? null : option.toLowerCase(Locale.ROOT).contains("remote");
        List<RawLocation> locations = new ArrayList<>();
        for (int i = 0; i < standardized.size(); i++) {
            String code = value(standardized.get(i));
            if (code == null) {
                continue;
            }
            String country = isoCountry(code.substring(code.lastIndexOf(',') + 1).strip());
            String text = display.size() == standardized.size() ? value(display.get(i)) : code;
            locations.add(new RawLocation(text != null ? text : code, null, null, country, remote));
        }
        if (locations.isEmpty()) {
            for (JsonNode text : display) {
                if (value(text) != null) {
                    locations.add(new RawLocation(value(text), null, null, null, remote));
                }
            }
        }
        return locations;
    }

    /** A text field, or the first entry when the tenant sends a list. */
    static String firstText(JsonNode node) {
        return value(node.isArray() ? node.path(0) : node);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
     * The typed view of companies.config for Eightfold rows:
     * {"host": "apply.careers.microsoft.com", "domain": "microsoft.com"}, plus optional "location" (the search's
     * location text, default "India"), "delayMs" (the STARTING pause between requests, default 1000; it grows on
     * its own when the site throttles) and "detailDepartments" (regex; only jobs whose department matches get a
     * detail request; absent = all).
     */
    record EightfoldConfig(String host, String domain, String location, Duration delay, Pattern detailDepartments) {

        static EightfoldConfig from(Company company) {
            String host = text(company.config(), "host");
            String domain = text(company.config(), "domain");
            if (host == null || domain == null) {
                throw new IllegalArgumentException(company.slug() + ": config.host and config.domain are required");
            }
            String location = text(company.config(), "location");
            JsonNode delayMs = company.config().path("delayMs");
            String detailDepartments = text(company.config(), "detailDepartments");
            return new EightfoldConfig(host, domain, location != null ? location : "India",
                    Duration.ofMillis(delayMs.isNumber() ? delayMs.asLong() : 1000),
                    detailDepartments == null ? null : Pattern.compile(detailDepartments, Pattern.CASE_INSENSITIVE));
        }

        /** True when every job gets a detail, or the department matches detailDepartments. */
        boolean wantsDetail(String department) {
            return detailDepartments == null || (department != null && detailDepartments.matcher(department).find());
        }

        String base() {
            return "https://" + host;
        }
    }
}