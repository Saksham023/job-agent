package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;

/**
 * Atlassian's careers site, through the JSON its own page loads: GET https://www.atlassian.com/endpoint/careers/listings
 * (allowed by robots.txt; only the job pages under /company/careers/detail/ are disallowed, and they are never fetched).
 * ONE request returns every posting worldwide (~350, 0.5 MB) with its full text, so there is no paging and no detail
 * request. There is no country field: a job is kept when one of its locations names the country ("Bengaluru - India -
 * Bengaluru, 560071 India", "Remote - India - Remote"). The list repeats some postings (same id, identical rows): kept
 * once. There is NO posting date, only a last-updated time that changes on every edit, so posted date stays empty and
 * the site falls back to when we first saw the job.
 */
@Component
public class AtlassianAdapter implements JobBoardAdapter {

    static final String LISTINGS = "https://www.atlassian.com/endpoint/careers/listings";

    private final RestClient http;

    public AtlassianAdapter(RestClient crawlRestClient) {
        this.http = crawlRestClient;
    }

    @Override
    public String platform() {
        return "atlassian";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        AtlassianConfig config = AtlassianConfig.from(company);
        JsonNode body = http.get().uri(URI.create(config.url())).retrieve().body(JsonNode.class);
        if (body == null || !body.isArray()) {
            throw new IllegalStateException(company.slug() + ": Atlassian listings are not a JSON array");
        }
        return inCountry(body, config.countryName());
    }

    /** The postings with a location in the country, each id once, in list order. */
    static List<RawJob> inCountry(JsonNode listings, String countryName) {
        Pattern country = Pattern.compile("(?i)\\b" + Pattern.quote(countryName) + "\\b");   // "India", never "Indiana"
        Set<String> seen = new HashSet<>();
        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode job : listings) {
            boolean here = false;
            for (JsonNode location : job.path("locations")) {
                here |= location.isString() && country.matcher(location.asString()).find();
            }
            String id = text(job, "id");
            if (here && id != null && seen.add(id)) {
                jobs.add(toRawJob(job));
            }
        }
        return jobs;
    }

    static RawJob toRawJob(JsonNode job) {
        String id = text(job, "id");
        String portalUrl = text(job.path("portalJobPost"), "portalUrl");   // the public job page (iCIMS)
        List<RawLocation> locations = new ArrayList<>();
        for (JsonNode location : job.path("locations")) {
            if (location.isString() && !location.asString().isBlank()) {
                locations.add(RawLocation.ofText(location.asString()));
            }
        }
        return new RawJob(
                id,
                text(job, "title"),
                text(job, "category"),                                   // "Engineering", "Sales", "Security"
                null,
                locations,
                text(job, "type"),                                       // "Full-Time", often missing
                portalUrl != null ? portalUrl : text(job, "applyUrl"),
                description(job),
                null,                                                    // no posting date (see the class comment)
                null,
                job);
    }

    /** The overview, then the responsibilities and qualifications under their own headings (HTML). */
    static String description(JsonNode job) {
        StringBuilder html = new StringBuilder();
        append(html, null, text(job, "overview"));
        append(html, "Responsibilities", text(job, "responsibilities"));
        append(html, "Qualifications", text(job, "qualifications"));
        return html.isEmpty() ? null : html.toString();
    }

    private static void append(StringBuilder html, String heading, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        if (heading != null) {
            html.append("<h3>").append(heading).append("</h3>");
        }
        html.append(body);
    }

    /**
     * The typed view of companies.config for the Atlassian row: optional "country" (ISO code, default "IN"; its English
     * name is what the locations say) and "url" (default: the public listings endpoint).
     */
    record AtlassianConfig(String url, String countryName) {

        static AtlassianConfig from(Company company) {
            String country = text(company.config(), "country");
            String url = text(company.config(), "url");
            return new AtlassianConfig(url != null ? url : LISTINGS,
                    Locale.of("", country != null ? country.toUpperCase(Locale.ROOT) : "IN").getDisplayCountry(Locale.ENGLISH));
        }
    }
}
