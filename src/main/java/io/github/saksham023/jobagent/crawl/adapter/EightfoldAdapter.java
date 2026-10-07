package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.common.ShutdownSignal;
import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.CrawlStoppedException;
import io.github.saksham023.jobagent.crawl.DetailCache;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
 * skipped. Only after config.maxThrottledTries signals in a row (default 5) for one request does it give up (a real
 * block must not be hammered): a failed list page fails the crawl, a failed detail keeps the job without a description.
 * The wait before each retry is config.coolDownSeconds (default 10) x the signals in a row, at most
 * config.maxCoolDownSeconds (default 300). A site that blocks for minutes (Microsoft) gets more patience this way:
 * e.g. 30 s, 8 tries = it waits 30, 60 ... 210 s before it gives up.
 * Optional config.detailDepartments (a regex, case-insensitive) limits the detail requests to jobs whose department
 * matches; every other job is still saved from the list (title, department, location, date), just without a
 * description. It is data, not code: change it per company in companies.config.
 * Optional config.saveEvery (default 10): the crawl saves its jobs and logs its progress after this many jobs.
 * Optional config.maxDetailsPerCrawl caps the detail requests of one crawl (for a first load of a company that
 * throttles): the remaining jobs are saved from the list (or with their older stored detail) and, having no
 * description, are fetched by the next crawl. A detail that cannot be fetched also keeps an older stored one.
 * Optional config.newestSortBy (the PCSX sort value, "timestamp" = newest first) switches on the QUICK CHECK FOR NEW JOBS
 * ({@link #fetchNewestJobs}): the list is read newest first, jobs already stored with an unchanged list entry are skipped,
 * and it stops after the first page with nothing new, or after config.peekMaxPages (default 5) pages.
 * Tenants that need a session cookie and CSRF token (Morgan Stanley, UKG) are not supported yet.
 */
@Component
public class EightfoldAdapter implements JobBoardAdapter {

    private static final Logger log = LoggerFactory.getLogger(EightfoldAdapter.class);

    private static final int MAX_PAGES = 300;                              // safety stop: 3,000 postings
    static final Duration SLOW_DOWN_STEP = Duration.ofSeconds(1);          // each throttle signal adds this per request
    static final Duration MAX_DELAY = Duration.ofSeconds(15);
    static final Duration COOL_DOWN = Duration.ofSeconds(10);              // x the signals in a row, before the retry
    static final Duration MAX_COOL_DOWN = Duration.ofSeconds(300);         // no single wait is longer than this
    static final int MAX_THROTTLED_TRIES = 5;
    static final int MAX_FAILED_DETAILS_IN_A_ROW = 3;                      // then the crawl stops: the site is blocking us

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

    /** How patient a crawl is with a throttling site: the retry wait is coolDown x the signals in a row, capped at maxWait. */
    record Throttle(Duration coolDown, int maxTries, Duration maxWait) {

        static final Throttle DEFAULT = new Throttle(COOL_DOWN, MAX_THROTTLED_TRIES, MAX_COOL_DOWN);

        Duration waitAfter(int signals) {
            Duration wait = coolDown.multipliedBy(signals);
            return wait.compareTo(maxWait) > 0 ? maxWait : wait;
        }
    }

    private final RestClient http;

    private final DetailCache detailCache;

    private final ShutdownSignal shutdown;

    public EightfoldAdapter(RestClient crawlRestClient, DetailCache detailCache, ShutdownSignal shutdown) {
        this.http = crawlRestClient;
        this.detailCache = detailCache;
        this.shutdown = shutdown;
    }

    @Override
    public String platform() {
        return "eightfold";
    }

    /** Each company has its own host (careers.qualcomm.com, apply.careers.microsoft.com). */
    @Override
    public String serverKey(Company company) {
        return EightfoldConfig.from(company).host();
    }

    /** Everything at once (preview, tests): the batches of the streaming crawl collected into one list. */
    @Override
    public List<RawJob> fetchJobs(Company company) {
        List<RawJob> all = new ArrayList<>();
        all.addAll(fetchJobs(company, all::addAll));
        return all;
    }

    /**
     * The crawl. One list page at a time: the page's jobs get their descriptions straight away, so jobs are saved from
     * the very first page on (a crawl that is blocked at page 20 still keeps 190 jobs). Every config.saveEvery jobs the
     * finished jobs go to the sink (the crawl service saves them at once) and a progress line is logged; a debug line per
     * job gives each job's own timestamp. Returns nothing at the end: every job has been passed to the sink. If
     * MAX_FAILED_DETAILS_IN_A_ROW description requests in a row fail, the jobs so far are passed on and the crawl stops
     * with an error (the site is blocking us; the next crawl continues from what was saved).
     */
    @Override
    public List<RawJob> fetchJobs(Company company, Consumer<List<RawJob>> sink) {
        EightfoldConfig config = EightfoldConfig.from(company);
        log.info("{}: crawl started; one request every {} s at first, saving and logging every {} jobs{}", company.slug(),
                config.delay().toMillis() / 1000.0, config.saveEvery(), config.maxDetailsPerCrawl() == null ? ""
                        : ", at most " + config.maxDetailsPerCrawl() + " descriptions this crawl");
        new Crawl(company, config, sink, false).run();
        return List.of();
    }

    @Override
    public boolean supportsNewestCheck(Company company) {
        return EightfoldConfig.from(company).newestSortBy() != null;
    }

    /**
     * The quick check: the newest part of the list only. New jobs and jobs whose list entry changed (a repost changes the
     * posting time) get their description and go to the sink; known unchanged jobs are skipped. The list is read page by
     * page and the check stops after the first page that brought nothing new, or after peekMaxPages pages (the next check
     * continues, an hour later). It never learns which jobs have gone, so it is no basis for closing jobs.
     */
    @Override
    public List<RawJob> fetchNewestJobs(Company company, Consumer<List<RawJob>> sink) {
        EightfoldConfig config = EightfoldConfig.from(company);
        if (config.newestSortBy() == null) {
            throw new UnsupportedOperationException(company.slug() + ": config.newestSortBy is not set");
        }
        log.info("{}: quick check for new jobs started (newest first, at most {} list pages)", company.slug(),
                config.peekMaxPages());
        new Crawl(company, config, sink, true).run();
        return List.of();
    }

    /** The state of one crawl: its pace, what it has seen and done so far, and the batch waiting to be handed over. */
    private final class Crawl {

        private final Company company;
        private final EightfoldConfig config;
        private final Consumer<List<RawJob>> sink;
        private final Pace pace;
        private final DetailCache.Known known;
        private final Set<String> seen = new HashSet<>();
        private final List<RawJob> batch = new ArrayList<>();
        private final long start = System.nanoTime();
        private long batchStart = System.nanoTime();
        private final boolean newestOnly;                           // the quick check: only new / changed jobs
        private int total, listed, done, listOnly, reused, fetched, deferred, failedInARow, alreadyKnown;

        Crawl(Company company, EightfoldConfig config, Consumer<List<RawJob>> sink, boolean newestOnly) {
            this.newestOnly = newestOnly;
            this.company = company;
            this.config = config;
            this.sink = sink;
            this.pace = new Pace(config.delay());
            this.known = detailCache.load(company.id());
        }

        void run() {
            try {
                for (int page = 0; page < MAX_PAGES; page++) {
                    JsonNode items = listPage(page);
                    int newOnThisPage = 0;
                    for (JsonNode summary : items) {
                        String id = text(summary, "id");
                        if (id != null && !seen.add(id)) {
                            continue;                           // the list shifted and showed this job on two pages
                        }
                        if (newestOnly && known.unchanged(id, listHash(summary))) {
                            alreadyKnown++;                     // stored with the same list entry: nothing to do
                            continue;
                        }
                        newOnThisPage++;
                        process(summary);
                    }
                    if (items.isEmpty() || listed >= total) {
                        finish();
                        return;
                    }
                    if (newestOnly && (newOnThisPage == 0 || page + 1 >= config.peekMaxPages())) {
                        finish();                               // nothing new on this page (or enough pages): stop
                        return;
                    }
                }
                throw new IllegalStateException(company.slug() + ": more than " + MAX_PAGES + " pages; stopping to be safe");
            } catch (CrawlStoppedException e) {
                // the app is shutting down: hand over the jobs finished since the last batch, then stop
                flush();
                log.info("{}: crawl interrupted, the app is shutting down; {} of {} jobs were saved", company.slug(), done,
                        total);
                throw e;
            } catch (RuntimeException e) {
                flush();                                        // whatever failed (a list page, say): keep the finished jobs
                throw e;
            }
        }

        /** The next page of the search: its positions. The offset is how many positions the list has shown so far. */
        private JsonNode listPage(int page) {
            URI uri = URI.create(config.base() + "/api/pcsx/search?domain=" + encode(config.domain()) + "&query=&location="
                    + encode(config.location()) + "&start=" + listed
                    + (newestOnly ? "&sort_by=" + encode(config.newestSortBy()) : ""));
            JsonNode data = data(company, get(company, config, pace, uri));
            JsonNode items = data.path("positions");
            total = data.path("count").asInt();
            listed += items.size();
            log.info("{}: list page {} of {}: {} of {} jobs listed", company.slug(), page + 1, Math.max(1, (total + 9) / 10),
                    listed, total);
            return items;
        }

        /** One job: its description (fetched, reused or left for later), then into the batch. */
        private void process(JsonNode summary) {
            if (shutdown.isStopping()) {
                throw new CrawlStoppedException();
            }
            String id = text(summary, "id");
            String listHash = listHash(summary);
            RawJob job;
            boolean attempted = false;                              // a description request was made for this job
            boolean detailFailed = false;
            if (!config.wantsDetail(text(summary, "department"))) {
                listOnly++;
                job = toRawJob(config, summary, null).withDetail(listHash, RawJob.DetailSource.NONE);
            } else {
                JsonNode stored = known.reusable(id, listHash);
                if (stored != null) {
                    reused++;
                    job = toRawJob(config, summary, stored).withDetail(listHash, RawJob.DetailSource.REUSED);
                } else {
                    JsonNode detail = null;
                    if (config.maxDetailsPerCrawl() == null || fetched < config.maxDetailsPerCrawl()) {
                        fetched++;
                        attempted = true;
                        long requestStart = System.nanoTime();
                        detail = fetchDetail(company, config, pace, id);
                        detailFailed = detail == null;
                        log.debug("{}: job {} ({}/{}) {} in {} s", company.slug(), id, done + 1, total,
                                detail == null ? "NO DESCRIPTION" : "description fetched",
                                (System.nanoTime() - requestStart) / 1_000_000_000.0);
                    } else {
                        deferred++;                                 // the next crawl fetches it
                    }
                    if (detail != null) {
                        job = toRawJob(config, summary, detail).withDetail(listHash, RawJob.DetailSource.FETCHED);
                    } else {                                        // keep an older detail rather than none
                        JsonNode older = known.anyAge(id);
                        job = toRawJob(config, summary, older).withDetail(listHash,
                                older == null ? RawJob.DetailSource.NONE : RawJob.DetailSource.REUSED);
                    }
                }
            }
            batch.add(job);
            done++;
            if (attempted) {
                failedInARow = detailFailed ? failedInARow + 1 : 0;
            }

            boolean blocked = failedInARow >= MAX_FAILED_DETAILS_IN_A_ROW;
            if (batch.size() >= config.saveEvery() || blocked) {
                int size = batch.size();
                flush();
                log.info("{}: {}/{} jobs saved ({} descriptions fetched, {} reused, {} list only, {} left for the next crawl); "
                                + "last {} took {}; {} since the crawl started; one request every {} s",
                        company.slug(), done, total, fetched, reused, listOnly, deferred, size, since(batchStart),
                        since(start), pace.delay().toMillis() / 1000.0);
                batchStart = System.nanoTime();
            }
            if (blocked) {
                throw new IllegalStateException(company.slug() + ": " + failedInARow + " description requests in a row "
                        + "failed, the site seems to be blocking us; stopping, the next crawl continues (" + done + " of "
                        + total + " jobs were saved)");
            }
        }

        private void flush() {
            if (!batch.isEmpty()) {
                sink.accept(List.copyOf(batch));
                batch.clear();
            }
        }

        private void finish() {
            flush();
            log.info("{}: {} finished: {} jobs in {} ({} descriptions fetched, {} reused, {} list only, {} left for the "
                            + "next crawl{}); one request every {} s at the end", company.slug(),
                    newestOnly ? "quick check" : "crawl", done, since(start), fetched, reused, listOnly, deferred,
                    newestOnly ? ", " + alreadyKnown + " already known" : "", pace.delay().toMillis() / 1000.0);
        }
    }

    /** "5 min 12 s" for the time since the given System.nanoTime(). */
    private static String since(long startNanos) {
        long seconds = (System.nanoTime() - startNanos) / 1_000_000_000;
        return seconds >= 60 ? seconds / 60 + " min " + seconds % 60 + " s" : seconds + " s";
    }

    // ---------------------------------------------------------------- requests

    /** The job's detail ("data" object), or null when it cannot be fetched; the job is then kept without a description. */
    private JsonNode fetchDetail(Company company, EightfoldConfig config, Pace pace, String positionId) {
        URI uri = URI.create(config.base() + "/api/pcsx/position_details?position_id=" + encode(positionId)
                + "&domain=" + encode(config.domain()) + "&hl=en");
        try {
            return data(company, get(company, config, pace, uri));
        } catch (CrawlStoppedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("{}: no detail for position {} ({}); keeping it without a description", company.slug(), positionId, e.getMessage());
            return null;
        }
    }

    /** One polite GET at the crawl's current pace. */
    private JsonNode get(Company company, EightfoldConfig config, Pace pace, URI uri) {
        return politely(company.slug(), pace, config.throttle(), () -> http.get().uri(uri).retrieve().body(JsonNode.class),
                shutdown::isStopping);
    }

    /**
     * Waits the pace, then runs the call. A throttle signal (429, or a refused / timed-out connection) slows the pace,
     * waits coolDown x the signals so far, and retries the same call; after MAX_THROTTLED_TRIES signals in a row the
     * last one is thrown. Other errors (403, 404, bad JSON) are never retried.
     */
    static <T> T politely(String slug, Pace pace, Duration coolDown, Supplier<T> call) {
        return politely(slug, pace, new Throttle(coolDown, MAX_THROTTLED_TRIES, MAX_COOL_DOWN), call, () -> false);
    }

    /** As above; the waits end early with a CrawlStoppedException as soon as stopping says the app is shutting down. */
    static <T> T politely(String slug, Pace pace, Duration coolDown, Supplier<T> call, BooleanSupplier stopping) {
        return politely(slug, pace, new Throttle(coolDown, MAX_THROTTLED_TRIES, MAX_COOL_DOWN), call, stopping);
    }

    static <T> T politely(String slug, Pace pace, Throttle throttle, Supplier<T> call, BooleanSupplier stopping) {
        for (int signals = 1; ; signals++) {
            sleep(pace.delay(), stopping);
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests | ResourceAccessException e) {
                if (signals >= throttle.maxTries()) {
                    throw e;
                }
                Duration slower = pace.slowDown();
                Duration wait = throttle.waitAfter(signals);
                log.info("{}: throttled ({}); now one request every {} s, retrying in {} s (signal {} of {})", slug,
                        e instanceof ResourceAccessException ? "connection failed" : "429", slower.toMillis() / 1000.0,
                        wait.toSeconds(), signals, throttle.maxTries());
                sleep(wait, stopping);
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

    /** The search entry's stable fields: name, department, locations, work option, posting time. */
    static String listHash(JsonNode summary) {
        return DetailCache.fingerprint(summary.path("name"), summary.path("department"), summary.path("locations"),
                summary.path("standardizedLocations"), summary.path("workLocationOption"), summary.path("postedTs"));
    }

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

    private static void sleep(Duration duration, BooleanSupplier stopping) {
        Pauses.pause(duration, stopping);
    }

    /**
     * The typed view of companies.config for Eightfold rows:
     * {"host": "apply.careers.microsoft.com", "domain": "microsoft.com"}, plus optional "location" (the search's
     * location text, default "India"), "delayMs" (the STARTING pause between requests, default 1000; it grows on
     * its own when the site throttles) and "detailDepartments" (regex; only jobs whose department matches get a
     * detail request; absent = all). "newestSortBy" / "peekMaxPages": the quick check for new jobs. Patience with throttling: "coolDownSeconds" (default 10), "maxThrottledTries"
     * (default 5) and "maxCoolDownSeconds" (default 300).
     */
    record EightfoldConfig(String host, String domain, String location, Duration delay, Pattern detailDepartments,
                           Integer maxDetailsPerCrawl, int saveEvery, Throttle throttle, String newestSortBy,
                           int peekMaxPages) {

        static EightfoldConfig from(Company company) {
            String host = text(company.config(), "host");
            String domain = text(company.config(), "domain");
            if (host == null || domain == null) {
                throw new IllegalArgumentException(company.slug() + ": config.host and config.domain are required");
            }
            String location = text(company.config(), "location");
            JsonNode delayMs = company.config().path("delayMs");
            String detailDepartments = text(company.config(), "detailDepartments");
            JsonNode maxDetails = company.config().path("maxDetailsPerCrawl");
            JsonNode saveEvery = company.config().path("saveEvery");
            JsonNode coolDown = company.config().path("coolDownSeconds");
            JsonNode tries = company.config().path("maxThrottledTries");
            JsonNode maxCoolDown = company.config().path("maxCoolDownSeconds");
            String newestSortBy = text(company.config(), "newestSortBy");
            JsonNode peekPages = company.config().path("peekMaxPages");
            Throttle throttle = new Throttle(
                    coolDown.isNumber() && coolDown.asLong() >= 0 ? Duration.ofSeconds(coolDown.asLong()) : COOL_DOWN,
                    tries.isNumber() && tries.asInt() >= 1 ? tries.asInt() : MAX_THROTTLED_TRIES,
                    maxCoolDown.isNumber() && maxCoolDown.asLong() >= 0 ? Duration.ofSeconds(maxCoolDown.asLong()) : MAX_COOL_DOWN);
            return new EightfoldConfig(host, domain, location != null ? location : "India",
                    Duration.ofMillis(delayMs.isNumber() ? delayMs.asLong() : 1000),
                    detailDepartments == null ? null : Pattern.compile(detailDepartments, Pattern.CASE_INSENSITIVE),
                    maxDetails.isNumber() ? maxDetails.asInt() : null,
                    saveEvery.isNumber() && saveEvery.asInt() >= 1 ? saveEvery.asInt() : 10, throttle, newestSortBy,
                    peekPages.isNumber() && peekPages.asInt() >= 1 ? peekPages.asInt() : 5);
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