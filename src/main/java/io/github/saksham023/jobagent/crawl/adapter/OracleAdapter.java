package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.DetailCache;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.isoCountry;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.toInstant;

/**
 * Oracle Recruiting Cloud career sites (JPMorgan, Goldman Sachs, Texas Instruments, Amex) through the REST API their
 * own "Candidate Experience" pages call:
 * - the country's location id differs per tenant, so it is DISCOVERED from the LOCATIONS facet (the entry named
 *   exactly like config.country, "India"); the keyword "India" would undercount (TI: 16 instead of 133);
 * - GET recruitingCEJobRequisitions?finder=findReqs;siteNumber=..,locationId=..,limit=100,offset=N lists the jobs
 *   (no description);
 * - GET recruitingCEJobRequisitionDetails?finder=ById;Id="..",siteNumber=.. gives one job's HTML texts.
 * Every request waits config.delayMs (default 1 s); a job whose detail fails is kept without a description.
 */
@Component
public class OracleAdapter implements JobBoardAdapter {

    private static final Logger log = LoggerFactory.getLogger(OracleAdapter.class);

    private static final String API = "/hcmRestApi/resources/latest/";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;                          // safety stop: 5,000 postings

    private final RestClient http;
    private final DetailCache detailCache;

    public OracleAdapter(RestClient crawlRestClient, DetailCache detailCache) {
        this.http = crawlRestClient;
        this.detailCache = detailCache;
    }

    @Override
    public String platform() {
        return "oracle";
    }

    /** Each tenant has its own host (jpmc.fa.oraclecloud.com, edbz.fa.us2.oraclecloud.com). */
    @Override
    public String serverKey(Company company) {
        return OracleConfig.from(company).host();
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        OracleConfig config = OracleConfig.from(company);
        String locationId = config.locationId() != null ? config.locationId() : discoverLocationId(company, config);
        DetailCache.Known known = detailCache.load(company.id());
        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode summary : listRequisitions(company, config, locationId)) {
            String listHash = listHash(summary);
            JsonNode stored = known.reusable(text(summary, "Id"), listHash);
            if (stored != null) {
                jobs.add(toRawJob(config, summary, stored).withDetail(listHash, RawJob.DetailSource.REUSED));
                continue;
            }
            JsonNode detail = fetchDetail(company, config, text(summary, "Id"));
            jobs.add(toRawJob(config, summary, detail).withDetail(listHash,
                    detail == null ? RawJob.DetailSource.NONE : RawJob.DetailSource.FETCHED));
        }
        return jobs;
    }

    // ---------------------------------------------------------------- requests

    /** The id of the LOCATIONS facet entry named exactly like the country ("India", not "Karnataka, India"). */
    private String discoverLocationId(Company company, OracleConfig config) {
        JsonNode search = firstItem(company, get(config, "recruitingCEJobRequisitions?onlyData=true&expand=locationsFacet"
                + "&finder=findReqs;siteNumber=" + config.siteNumber() + ",facetsList=LOCATIONS,limit=1,offset=0"));
        String id = locationId(search.path("locationsFacet"), config.country());
        if (id == null) {
            throw new IllegalStateException(company.slug() + ": no '" + config.country() + "' entry in the location "
                    + "facet; set config.locationId");
        }
        log.info("{}: location '{}' is id {}", company.slug(), config.country(), id);
        return id;
    }

    static String locationId(JsonNode facet, String country) {
        for (JsonNode entry : facet) {
            if (country.equalsIgnoreCase(text(entry, "Name"))) {
                return text(entry, "Id");
            }
        }
        return null;
    }

    /** Every page of the country's requisitions, until TotalJobsCount; duplicates (by Id) dropped. */
    private List<JsonNode> listRequisitions(Company company, OracleConfig config, String locationId) {
        Map<String, JsonNode> requisitions = new LinkedHashMap<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * PAGE_SIZE;
            JsonNode search = firstItem(company, get(config, "recruitingCEJobRequisitions?onlyData=true"
                    + "&expand=requisitionList.secondaryLocations&finder=findReqs;siteNumber=" + config.siteNumber()
                    + ",locationId=" + locationId + ",limit=" + PAGE_SIZE + ",offset=" + offset + ",sortBy=POSTING_DATES_DESC"));
            JsonNode list = search.path("requisitionList");
            list.forEach(r -> requisitions.putIfAbsent(text(r, "Id"), r));
            if (list.isEmpty() || offset + PAGE_SIZE >= search.path("TotalJobsCount").asInt()) {
                return new ArrayList<>(requisitions.values());
            }
        }
        throw new IllegalStateException(company.slug() + ": more than " + MAX_PAGES + " pages; stopping to be safe");
    }

    /** One job's detail, or null when it cannot be fetched (the job is then kept without a description). */
    private JsonNode fetchDetail(Company company, OracleConfig config, String id) {
        try {
            return firstItem(company, get(config, "recruitingCEJobRequisitionDetails?expand=all&onlyData=true"
                    + "&finder=ById;Id=%22" + id + "%22,siteNumber=" + config.siteNumber()));
        } catch (RuntimeException e) {
            log.warn("{}: no detail for requisition {} ({}); keeping it without a description", company.slug(), id, e.getMessage());
            return null;
        }
    }

    /** One polite GET: waits delayMs first. The URI is built by hand: the finder's ';' and ',' must stay as they are. */
    private JsonNode get(OracleConfig config, String pathAndQuery) {
        sleep(config.delay());
        return http.get().uri(URI.create("https://" + config.host() + API + pathAndQuery)).retrieve().body(JsonNode.class);
    }

    private static JsonNode firstItem(Company company, JsonNode body) {
        JsonNode item = body == null ? null : body.path("items").path(0);
        if (item == null || !item.isObject()) {
            throw new IllegalStateException(company.slug() + ": unexpected Oracle response (no items)");
        }
        return item;
    }

    // ---------------------------------------------------------------- mapping

    /** The list entry's stable fields (not Relevancy, Distance or the hot/trending flags). */
    static String listHash(JsonNode summary) {
        return DetailCache.fingerprint(summary.path("Title"), summary.path("PostedDate"), summary.path("PrimaryLocation"),
                summary.path("secondaryLocations"), summary.path("JobFamily"), summary.path("JobFunction"),
                summary.path("WorkplaceTypeCode"), summary.path("WorkplaceType"));
    }

    /** @param detail the requisition detail, or null (then only the list's facts, no description) */
    static RawJob toRawJob(OracleConfig config, JsonNode summary, JsonNode detail) {
        String id = text(summary, "Id");
        JsonNode job = detail != null ? detail : summary;
        String posted = text(job, "ExternalPostedStartDate");
        String postedDate = text(summary, "PostedDate");
        return new RawJob(
                id,
                text(summary, "Title"),
                text(job, detail != null ? "Category" : "JobFamily"),        // "Software Engineering"
                text(job, "JobFunction"),                                      // "Technology"
                locations(summary),
                text(job, "JobSchedule"),                                      // "Full time"
                "https://" + config.host() + "/hcmUI/CandidateExperience/en/sites/" + config.siteNumber() + "/job/" + id,
                detail == null ? null : description(detail),
                posted != null ? toInstant(posted)
                        : postedDate != null ? LocalDate.parse(postedDate).atStartOfDay(ZoneOffset.UTC).toInstant() : null,
                null,
                job);
    }

    /**
     * The job's own texts: description, then responsibilities and qualifications under their own headings (TI keeps
     * them apart). The company and organization blurbs are left out: they are the same in every posting.
     */
    static String description(JsonNode detail) {
        StringBuilder html = new StringBuilder();
        append(html, null, text(detail, "ExternalDescriptionStr"));
        append(html, "Responsibilities", text(detail, "ExternalResponsibilitiesStr"));
        append(html, "Qualifications", text(detail, "ExternalQualificationsStr"));
        return html.isEmpty() ? null : html.toString();
    }

    private static void append(StringBuilder html, String heading, String body) {
        if (body == null) {
            return;
        }
        if (heading != null) {
            html.append("<h3>").append(heading).append("</h3>");
        }
        html.append(body);
    }

    /** The primary location (with its ISO country) and every secondary one ("Hyderabad, Telangana, India", "IN"). */
    static List<RawLocation> locations(JsonNode summary) {
        String workplace = text(summary, "WorkplaceTypeCode") != null ? text(summary, "WorkplaceTypeCode") : text(summary, "WorkplaceType");
        Boolean remote = workplace == null ? null : workplace.toUpperCase(Locale.ROOT).contains("REMOTE");
        List<RawLocation> locations = new ArrayList<>();
        String primary = text(summary, "PrimaryLocation");
        if (primary != null) {
            locations.add(new RawLocation(primary, null, null, isoCountry(text(summary, "PrimaryLocationCountry")), remote));
        }
        for (JsonNode secondary : summary.path("secondaryLocations")) {
            String name = text(secondary, "Name");
            if (name != null && !name.equals(primary)) {
                locations.add(new RawLocation(name, null, null, isoCountry(text(secondary, "CountryCode")), remote));
            }
        }
        return locations;
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
     * The typed view of companies.config for Oracle rows: {"host": "jpmc.fa.oraclecloud.com", "siteNumber": "CX_1001"},
     * plus optional "country" (the location facet name to look for, default "India"), "locationId" (skips the
     * discovery) and "delayMs" (pause before every request, default 1000).
     */
    record OracleConfig(String host, String siteNumber, String country, String locationId, Duration delay) {

        static OracleConfig from(Company company) {
            String host = text(company.config(), "host");
            String site = text(company.config(), "siteNumber");
            if (host == null || site == null) {
                throw new IllegalArgumentException(company.slug() + ": config.host and config.siteNumber are required");
            }
            String country = text(company.config(), "country");
            JsonNode delayMs = company.config().path("delayMs");
            return new OracleConfig(host, site, country != null ? country : "India", text(company.config(), "locationId"),
                    Duration.ofMillis(delayMs.isNumber() ? delayMs.asLong() : 1000));
        }
    }
}