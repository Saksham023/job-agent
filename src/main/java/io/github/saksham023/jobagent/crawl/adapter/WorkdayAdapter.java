package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import io.github.saksham023.jobagent.geo.LocationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.isoCountry;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;

/**
 * Workday career sites (https://&lt;tenant&gt;.wd&lt;N&gt;.myworkdayjobs.com/&lt;site&gt;), through the same JSON API their
 * public pages call: POST .../wday/cxs/&lt;tenant&gt;/&lt;site&gt;/jobs for the list (20 per page, no descriptions) and
 * GET .../wday/cxs/&lt;tenant&gt;/&lt;site&gt;&lt;externalPath&gt; for one job.
 *
 * The country filter is DISCOVERED, never configured: every tenant names it differently (locationCountry,
 * Location_Country, locationHierarchy1, a long custom name at Salesforce) and some only have city entries. So the
 * first, unfiltered call reads the facets: a facet with a value named exactly like the country ("India") wins;
 * otherwise every "locations" entry our LocationParser places in that country is used. Free-text search is never
 * used ("India" also matches "Indiana").
 *
 * The department comes from the job-category facet (default "jobFamilyGroup", e.g. "Engineering"): the list is read
 * once per category, which also tells each job's category. Jobs in no category are picked up by a plain listing.
 */
@Component
public class WorkdayAdapter implements JobBoardAdapter {

    private static final Logger log = LoggerFactory.getLogger(WorkdayAdapter.class);

    private static final int PAGE_SIZE = 20;                               // Workday rejects larger pages
    private static final int MAX_PAGES = 100;                              // safety stop: 2,000 postings per listing
    private static final Duration DELAY = Duration.ofMillis(250);          // politeness between requests
    private static final String LOCATIONS_FACET = "locations";

    /** One posting from a list page, with the category it was listed under (null when unknown). */
    record Summary(JsonNode posting, String department) {
    }

    private final RestClient http;
    private final LocationParser locationParser;

    public WorkdayAdapter(RestClient crawlRestClient, LocationParser locationParser) {
        this.http = crawlRestClient;
        this.locationParser = locationParser;
    }

    @Override
    public String platform() {
        return "workday";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        WorkdayConfig config = WorkdayConfig.from(company);

        JsonNode unfiltered = listPage(config, Map.of(), 0);
        Map<String, List<String>> countryFilter = countryFilter(unfiltered.path("facets"), config.country(), locationParser);
        if (countryFilter.isEmpty()) {
            throw new IllegalStateException(company.slug() + ": no Workday facet for country " + config.country());
        }
        log.info("{}: Workday country filter {}", company.slug(), countryFilter);

        List<RawJob> jobs = new ArrayList<>();
        for (Summary summary : listAll(config, countryFilter)) {
            String path = text(summary.posting(), "externalPath");
            JsonNode detail = path == null ? null : fetchDetail(company, config, path);
            jobs.add(toRawJob(config, summary, detail));
            pause();
        }
        return jobs;
    }

    // ---------------------------------------------------------------- listing

    /** Every posting matching the filter, each with its category when the tenant has a category facet. */
    private List<Summary> listAll(WorkdayConfig config, Map<String, List<String>> filter) {
        JsonNode first = listPage(config, filter, 0);
        int total = first.path("total").asInt();
        Map<String, Summary> byPath = new LinkedHashMap<>();

        JsonNode categories = findFacet(first.path("facets"), config.departmentFacet());
        if (categories != null) {
            for (JsonNode category : categories.path("values")) {
                String id = text(category, "id");
                if (id == null || category.path("count").asInt() == 0) {
                    continue;
                }
                Map<String, List<String>> byCategory = new HashMap<>(filter);
                byCategory.put(config.departmentFacet(), List.of(id));
                for (JsonNode posting : listPostings(config, byCategory)) {
                    byPath.putIfAbsent(text(posting, "externalPath"), new Summary(posting, text(category, "descriptor")));
                }
            }
        }
        if (byPath.size() < total) {                                       // no category facet, or uncategorized jobs
            for (JsonNode posting : listPostings(config, filter)) {
                byPath.putIfAbsent(text(posting, "externalPath"), new Summary(posting, null));
            }
        }
        return List.copyOf(byPath.values());
    }

    /** All pages of one listing. Only the first page carries the real total (later pages say 0). */
    private List<JsonNode> listPostings(WorkdayConfig config, Map<String, List<String>> filter) {
        List<JsonNode> postings = new ArrayList<>();
        int total = -1;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = listPage(config, filter, postings.size());
            JsonNode items = body.path("jobPostings");
            if (total < 0) {
                total = body.path("total").asInt();
            }
            items.forEach(postings::add);
            if (items.isEmpty() || postings.size() >= total) {
                return postings;
            }
            pause();
        }
        throw new IllegalStateException(config.host() + ": more than " + MAX_PAGES + " pages; stopping to be safe");
    }

    private JsonNode listPage(WorkdayConfig config, Map<String, List<String>> filter, int offset) {
        Map<String, Object> body = Map.of("appliedFacets", filter, "limit", PAGE_SIZE, "offset", offset, "searchText", "");
        JsonNode page = http.post()
                .uri(URI.create(config.apiBase() + "/jobs"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (page == null || !page.path("jobPostings").isArray()) {
            throw new IllegalStateException(config.host() + ": Workday response has no 'jobPostings' array");
        }
        return page;
    }

    /** The job's detail, or null when it cannot be fetched (e.g. closed since the list call). */
    private JsonNode fetchDetail(Company company, WorkdayConfig config, String externalPath) {
        try {
            return http.get().uri(URI.create(config.apiBase() + externalPath)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("{}: no detail for {} ({}); keeping it without a description", company.slug(), externalPath, e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- facets

    /**
     * The appliedFacets value that narrows a listing to one country, or an empty map when the tenant has none:
     * a facet value named exactly like the country (e.g. locationCountry = India), else the "locations" entries that
     * our parser places in the country ("Pune, India", "India - Bangalore"; never "Remote - Indiana").
     */
    static Map<String, List<String>> countryFilter(JsonNode facets, String iso2, LocationParser parser) {
        String countryName = Locale.of("", iso2).getDisplayCountry(Locale.ENGLISH);
        List<JsonNode> flat = flatten(facets);
        for (JsonNode facet : flat) {
            if (LOCATIONS_FACET.equals(text(facet, "facetParameter"))) {
                continue;
            }
            for (JsonNode value : facet.path("values")) {
                if (countryName.equalsIgnoreCase(text(value, "descriptor")) && text(value, "id") != null) {
                    return Map.of(text(facet, "facetParameter"), List.of(text(value, "id")));
                }
            }
        }
        for (JsonNode facet : flat) {
            if (!LOCATIONS_FACET.equals(text(facet, "facetParameter"))) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode value : facet.path("values")) {
                String descriptor = text(value, "descriptor");
                boolean inCountry = descriptor != null && parser.parse(descriptor, null, null).stream()
                        .anyMatch(place -> place.isInCountry(iso2));
                if (inCountry && text(value, "id") != null) {
                    ids.add(text(value, "id"));
                }
            }
            if (!ids.isEmpty()) {
                return Map.of(LOCATIONS_FACET, List.copyOf(ids));
            }
        }
        return Map.of();
    }

    /** Facets can be grouped ("locationMainGroup" holding locationCountry and locations); returns the leaves. */
    static List<JsonNode> flatten(JsonNode facets) {
        List<JsonNode> leaves = new ArrayList<>();
        for (JsonNode facet : facets) {
            JsonNode values = facet.path("values");
            if (!values.isEmpty() && values.get(0).has("facetParameter")) {
                leaves.addAll(flatten(values));
            } else {
                leaves.add(facet);
            }
        }
        return leaves;
    }

    private static JsonNode findFacet(JsonNode facets, String name) {
        return flatten(facets).stream().filter(f -> name.equals(text(f, "facetParameter"))).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- mapping

    /** @param detail the detail response, or null when it could not be fetched (then the list summary is used) */
    static RawJob toRawJob(WorkdayConfig config, Summary summary, JsonNode detail) {
        JsonNode posting = summary.posting();
        String path = text(posting, "externalPath");
        JsonNode info = detail == null ? null : detail.path("jobPostingInfo");
        Boolean remote = remote(text(posting, "remoteType"));
        String url = info == null ? null : text(info, "externalUrl");
        return new RawJob(
                path.substring(path.lastIndexOf('/') + 1),            // "Senior-Staff-Engineer_R120924": one per posting
                info == null ? text(posting, "title") : text(info, "title"),
                summary.department(),
                null,
                info == null ? summaryLocations(posting, remote) : locations(info, remote),
                info == null ? null : text(info, "timeType"),
                url != null ? url : config.siteUrl() + path,
                info == null ? null : text(info, "jobDescription"),
                info == null ? null : startOfDay(text(info, "startDate")),
                null,
                info == null ? posting : info
        );
    }

    /** The main location carries the requisition's country code; additional locations are text only. */
    private static List<RawLocation> locations(JsonNode info, Boolean remote) {
        List<RawLocation> locations = new ArrayList<>();
        String main = text(info, "location");
        String country = isoCountry(text(info.path("jobRequisitionLocation").path("country"), "alpha2Code"));
        if (main != null || country != null) {
            locations.add(new RawLocation(main, null, null, country, remote));
        }
        for (JsonNode additional : info.path("additionalLocations")) {
            String text = JsonFields.value(additional);
            if (text != null) {
                locations.add(new RawLocation(text, null, null, null, remote));
            }
        }
        return locations;
    }

    /** Without a detail only "Pune, India" style text is usable; "3 Locations" says nothing. */
    private static List<RawLocation> summaryLocations(JsonNode posting, Boolean remote) {
        String text = text(posting, "locationsText");
        return text == null || text.matches("\\d+ Locations") ? List.of() : List.of(new RawLocation(text, null, null, null, remote));
    }

    /** remoteType "Remote" / "Hybrid" / "On-site" (only some tenants send it). */
    private static Boolean remote(String remoteType) {
        return remoteType == null ? null : remoteType.toLowerCase(Locale.ROOT).contains("remote");
    }

    /** startDate is the posting date ("2026-09-24"). */
    private static Instant startOfDay(String date) {
        return date == null ? null : LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static void pause() {
        try {
            Thread.sleep(DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while crawling", e);
        }
    }

    /**
     * The typed view of companies.config for Workday rows:
     * {"host": "adobe.wd5.myworkdayjobs.com", "tenant": "adobe", "site": "external_experienced"}, plus optional
     * "country" (ISO code, default "IN") and "departmentFacet" (default "jobFamilyGroup").
     */
    record WorkdayConfig(String host, String tenant, String site, String country, String departmentFacet) {

        static WorkdayConfig from(Company company) {
            String host = required(company, "host");
            String tenant = required(company, "tenant");
            String site = required(company, "site");
            String country = text(company.config(), "country");
            String departmentFacet = text(company.config(), "departmentFacet");
            return new WorkdayConfig(host, tenant, site, country != null ? country.toUpperCase(Locale.ROOT) : "IN",
                    departmentFacet != null ? departmentFacet : "jobFamilyGroup");
        }

        /** Base of the JSON API: list = apiBase + "/jobs", detail = apiBase + externalPath. */
        String apiBase() {
            return "https://" + host + "/wday/cxs/" + tenant + "/" + site;
        }

        /** Base of the public pages, for apply links when a detail is missing. */
        String siteUrl() {
            return "https://" + host + "/" + site;
        }

        private static String required(Company company, String field) {
            String value = text(company.config(), field);
            if (value == null) {
                throw new IllegalArgumentException(company.slug() + ": config." + field + " is missing");
            }
            return value;
        }
    }
}