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
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.toInstant;

/**
 * Apple's careers site (jobs.apple.com; it has no robots.txt, so nothing is disallowed). Apple's own JSON search needs
 * a session token, so the adapter reads what the public pages already carry: every page embeds its data as
 * window.__staticRouterHydrationData = JSON.parse("...").
 * - The search page https://jobs.apple.com/en-in/search?location=india-INDC&page=N lists 20 entries per page with the
 *   title, team, location and exact posting time, but only a short summary.
 * - One role open in several cities is one entry PER CITY (positionId 200682703 as 200682703-0321 Bengaluru,
 *   200682703-1281 Gurugram...): they become ONE job (external id = positionId) with all its cities.
 * - The job page https://jobs.apple.com/en-in/details/{id}/{slug} has the full text: description, responsibilities,
 *   minimum qualifications (the years) and preferred qualifications. It is fetched once per role and reused like any
 *   other detail (DetailCache) while the list entry is unchanged and younger than the detail max age.
 * Apple Retail's always-open store roles ("IN-Genius", managedPipelineRole true) report the CURRENT time as their
 * posting time on every request: it is ignored (posted date empty, the site shows when we first saw the role), or they
 * would look new and re-posted on every crawl.
 * Every request waits config.delayMs (default 1 s); a role whose page fails is kept with the list's summary only.
 */
@Component
public class AppleAdapter implements JobBoardAdapter {

    private static final Logger log = LoggerFactory.getLogger(AppleAdapter.class);

    static final String HOST = "https://jobs.apple.com";
    private static final int MAX_PAGES = 100;                        // safety stop: 2,000 entries
    private static final Pattern HYDRATION =
            Pattern.compile("__staticRouterHydrationData\\s*=\\s*JSON\\.parse\\(\"(.*?)\"\\);", Pattern.DOTALL);

    private final RestClient http;
    private final DetailCache detailCache;
    private final ShutdownSignal shutdown;
    private final JsonMapper json;

    public AppleAdapter(RestClient crawlRestClient, DetailCache detailCache, ShutdownSignal shutdown, JsonMapper json) {
        this.http = crawlRestClient;
        this.detailCache = detailCache;
        this.shutdown = shutdown;
        this.json = json;
    }

    @Override
    public String platform() {
        return "apple";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        List<RawJob> all = new ArrayList<>();
        all.addAll(fetchJobs(company, all::addAll));
        return all;
    }

    /** Roles go to the sink in batches while their pages are fetched; a stop saves the batch in hand and ends the crawl. */
    @Override
    public List<RawJob> fetchJobs(Company company, Consumer<List<RawJob>> sink) {
        AppleConfig config = AppleConfig.from(company);
        DetailCache.Known known = detailCache.load(company.id());
        Batcher batcher = new Batcher(sink);
        try {
            for (List<JsonNode> role : roles(listEntries(company, config))) {
                Pauses.checkStopping(shutdown::isStopping);
                String id = text(role.getFirst(), "positionId");
                String listHash = listHash(role);
                JsonNode stored = known.reusable(id, listHash);
                if (stored != null) {
                    batcher.add(toRawJob(config, role, stored).withDetail(listHash, RawJob.DetailSource.REUSED));
                    continue;
                }
                JsonNode detail = fetchDetail(company, config, role.getFirst());
                batcher.add(toRawJob(config, role, detail).withDetail(listHash,
                        detail == null ? RawJob.DetailSource.NONE : RawJob.DetailSource.FETCHED));
            }
        } catch (CrawlStoppedException e) {
            batcher.flush();
            throw e;
        }
        batcher.flush();
        return List.of();
    }

    // ---------------------------------------------------------------- requests

    /** Every search page until totalRecords entries are read (or a page comes back empty). */
    private List<JsonNode> listEntries(Company company, AppleConfig config) {
        List<JsonNode> entries = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode search = hydration(company, getPage(config, "/" + config.locale() + "/search?location="
                    + config.location() + (page > 1 ? "&page=" + page : ""))).path("loaderData").path("search");
            JsonNode results = search.path("searchResults");
            if (!search.isObject() || !results.isArray()) {
                throw new IllegalStateException(company.slug() + ": Apple search page " + page + " has no search results");
            }
            results.forEach(entries::add);
            int total = search.path("totalRecords").asInt();
            log.info("{}: search page {} read, {} of {} entries", company.slug(), page, entries.size(), total);
            if (results.isEmpty() || entries.size() >= total) {
                return entries;
            }
        }
        throw new IllegalStateException(company.slug() + ": more than " + MAX_PAGES + " pages; stopping to be safe");
    }

    /** One role's job data from its page, or null when it cannot be read (the role is then kept with its summary). */
    private JsonNode fetchDetail(Company company, AppleConfig config, JsonNode entry) {
        try {
            JsonNode detail = hydration(company, getPage(config, detailPath(config, entry)))
                    .path("loaderData").path("jobDetails").path("jobsData");
            if (!detail.isObject()) {
                throw new IllegalStateException("the page has no job data");
            }
            return detail;
        } catch (CrawlStoppedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("{}: no detail for role {} ({}); keeping its summary", company.slug(), text(entry, "id"), e.getMessage());
            return null;
        }
    }

    /** One polite GET of an HTML page: waits delayMs first. */
    private String getPage(AppleConfig config, String path) {
        Pauses.pause(config.delay(), shutdown::isStopping);
        return http.get().uri(URI.create(HOST + path)).header(HttpHeaders.ACCEPT, "text/html").retrieve().body(String.class);
    }

    /** The JSON a page embeds as JSON.parse("..."): the argument is a JSON string literal holding the JSON text. */
    JsonNode hydration(Company company, String html) {
        String literal = html == null ? null : hydrationLiteral(html);
        if (literal == null) {
            throw new IllegalStateException(company.slug() + ": the page has no __staticRouterHydrationData");
        }
        return json.readTree(json.readValue("\"" + literal + "\"", String.class));
    }

    static String hydrationLiteral(String html) {
        Matcher matcher = HYDRATION.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ---------------------------------------------------------------- mapping

    /** The entries grouped by role (positionId), in list order: one role open in four cities is one group of four. */
    static List<List<JsonNode>> roles(List<JsonNode> entries) {
        Map<String, List<JsonNode>> byPosition = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            String position = text(entry, "positionId") != null ? text(entry, "positionId") : text(entry, "id");
            if (position == null || text(entry, "postingTitle") == null) {
                continue;
            }
            byPosition.computeIfAbsent(position, k -> new ArrayList<>()).add(entry);
        }
        return new ArrayList<>(byPosition.values());
    }

    /** The role's stable list fields: title, team, its entry ids and cities, posting time (not the summary text). */
    static String listHash(List<JsonNode> role) {
        List<Object> parts = new ArrayList<>();
        JsonNode first = role.getFirst();
        parts.add(text(first, "postingTitle"));
        parts.add(first.path("team").path("teamName"));
        for (JsonNode entry : role) {
            parts.add(text(entry, "id"));
            parts.add(entry.path("locations"));
            parts.add(realPostingTime(entry) ? text(entry, "postDateInGMT") : null);
        }
        return DetailCache.fingerprint(parts.toArray());
    }

    /** @param detail the role's job data, or null (then the list's summary is the description) */
    static RawJob toRawJob(AppleConfig config, List<JsonNode> role, JsonNode detail) {
        JsonNode first = role.getFirst();
        Instant posted = null;
        for (JsonNode entry : role) {                                   // the latest of its cities' posting times
            String time = realPostingTime(entry) ? text(entry, "postDateInGMT") : null;
            Instant at = time == null ? null : toInstant(time);
            if (at != null && (posted == null || at.isAfter(posted))) {
                posted = at;
            }
        }
        return new RawJob(
                text(first, "positionId") != null ? text(first, "positionId") : text(first, "id"),
                text(first, "postingTitle").strip(),
                text(first.path("team"), "teamName"),                       // "Software and Services", "Apple Retail"
                null,
                locations(role),
                null,
                HOST + detailPath(config, first),
                detail != null ? description(detail) : PlainText.toHtml(text(first, "jobSummary")),
                posted,
                null,
                detail != null ? detail : first);
    }

    /** False for the always-open store roles, whose "posting time" is just the time of the request. */
    static boolean realPostingTime(JsonNode entry) {
        return !entry.path("managedPipelineRole").asBoolean(false);
    }

    /** The job page: /details/200687867-1052/slug; pipeline roles ("PIPE-200314015") live under their position number. */
    static String detailPath(AppleConfig config, JsonNode entry) {
        String id = text(entry, "id");
        if (id == null || id.startsWith("PIPE-")) {
            id = text(entry, "positionId");
        }
        String slug = text(entry, "transformedPostingTitle");
        return "/" + config.locale() + "/details/" + id + (slug != null ? "/" + slug : "");
    }

    /** Summary, description, responsibilities and both qualification lists, under their own headings. */
    static String description(JsonNode detail) {
        StringBuilder html = new StringBuilder();
        append(html, null, text(detail, "jobSummary"));
        append(html, "Description", text(detail, "description"));
        append(html, "Responsibilities", text(detail, "responsibilities"));
        append(html, "Minimum Qualifications", text(detail, "minimumQualifications"));
        append(html, "Preferred Qualifications", text(detail, "preferredQualifications"));
        return html.isEmpty() ? null : html.toString();
    }

    private static void append(StringBuilder html, String heading, String body) {
        String content = PlainText.toHtml(body);
        if (content == null) {
            return;
        }
        if (heading != null) {
            html.append("<h3>").append(heading).append("</h3>");
        }
        html.append(content);
    }

    /** Each city of the role once ("Hyderabad, Telangana, India"); Apple Retail roles name only the country ("India"). */
    static List<RawLocation> locations(List<JsonNode> role) {
        Set<String> seen = new LinkedHashSet<>();
        List<RawLocation> locations = new ArrayList<>();
        for (JsonNode entry : role) {
            Boolean remote = entry.path("homeOffice").asBoolean(false) ? Boolean.TRUE : null;
            for (JsonNode location : entry.path("locations")) {
                String region = text(location, "stateProvince");
                String country = text(location, "countryName");
                String name = text(location, "name");                    // the search page leaves "city" empty
                String city = text(location, "city") != null ? text(location, "city")
                        : name != null && !name.equals(country) ? name : null;
                List<String> parts = new ArrayList<>();
                for (String part : new String[]{city != null ? city : name, region, country}) {
                    if (part != null && !part.isBlank() && !parts.contains(part)) {
                        parts.add(part);
                    }
                }
                String label = String.join(", ", parts);
                if (!label.isEmpty() && seen.add(label)) {
                    locations.add(new RawLocation(label, city, region, null, remote));
                }
            }
        }
        return locations;
    }

    /**
     * The typed view of companies.config for the Apple row, all optional: "locale" (default "en-in", the site's
     * language and country path), "location" (the search's location filter, default "india-INDC") and "delayMs"
     * (pause before every request, default 1000).
     */
    record AppleConfig(String locale, String location, Duration delay) {

        static AppleConfig from(Company company) {
            String locale = text(company.config(), "locale");
            String location = text(company.config(), "location");
            JsonNode delayMs = company.config().path("delayMs");
            return new AppleConfig(locale != null ? locale : "en-in", location != null ? location : "india-INDC",
                    Duration.ofMillis(delayMs.isNumber() ? delayMs.asLong() : 1000));
        }
    }
}
