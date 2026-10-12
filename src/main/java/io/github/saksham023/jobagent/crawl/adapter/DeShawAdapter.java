package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;

/**
 * The D. E. Shaw group's India careers site (www.deshawindia.com/careers; robots.txt disallows only a few policy
 * pages). The page is built with Next.js and embeds its data in <script id="__NEXT_DATA__">: props.pageProps.regularJobs
 * holds EVERY open India position (~75, 2 MB page) with its full text, so ONE request per crawl, no paging, no detail
 * requests. The site is India-only, so every office is in config.country. "All positions in ..." entries
 * (isExploratory) are general applications; they are kept like any posting (user's choice, 2026-10-12). There is NO
 * posting date: posted date stays empty and the site falls back to when we first saw the job. internalJobs (old and
 * internal postings, none listed) is ignored.
 */
@Component
public class DeShawAdapter implements JobBoardAdapter {

    static final String CAREERS = "https://www.deshawindia.com/careers";

    private final RestClient http;
    private final JsonMapper json;

    public DeShawAdapter(RestClient crawlRestClient, JsonMapper json) {
        this.http = crawlRestClient;
        this.json = json;
    }

    @Override
    public String platform() {
        return "deshaw";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        DeShawConfig config = DeShawConfig.from(company);
        String html = http.get().uri(URI.create(config.url())).header(HttpHeaders.ACCEPT, "text/html").retrieve().body(String.class);
        JsonNode jobs = nextData(company, html).path("props").path("pageProps").path("regularJobs");
        if (!jobs.isArray()) {
            throw new IllegalStateException(company.slug() + ": the careers page has no regularJobs list");
        }
        return positions(jobs, config);
    }

    /** The page's Next.js data. */
    JsonNode nextData(Company company, String html) {
        Element script = html == null ? null : Jsoup.parse(html).getElementById("__NEXT_DATA__");
        if (script == null || script.data().isBlank()) {
            throw new IllegalStateException(company.slug() + ": the careers page has no __NEXT_DATA__");
        }
        return json.readTree(script.data());
    }

    /** The listed positions, each id once, in page order. */
    static List<RawJob> positions(JsonNode regularJobs, DeShawConfig config) {
        Set<String> seen = new HashSet<>();
        List<RawJob> positions = new ArrayList<>();
        for (JsonNode entry : regularJobs) {
            JsonNode job = entry.path("data");
            String id = text(job, "id");
            if (id != null && text(job, "displayName") != null && text(job, "jobUrl") != null
                    && job.path("activeOnJobsListing").asBoolean(true) && seen.add(id)) {
                positions.add(toRawJob(job, config));
            }
        }
        return positions;
    }

    static RawJob toRawJob(JsonNode job, DeShawConfig config) {
        String department = text(job.path("department"), "name");
        if (department == null) {
            department = text(job.path("jobCategory").path(0), "name");       // "Core Tech", "Financial Operations"
        }
        return new RawJob(
                text(job, "id"),
                text(job, "displayName").replaceAll("\\s+", " ").strip(),
                department,
                element(job.path("jobHeaders"), 0),                             // "INFORMATION TECHNOLOGY", "FINANCE"
                locations(job, config),
                text(job, "status"),                                         // "Regular FT", "Industrial Trainee"
                config.url() + "/" + text(job, "jobUrl").toLowerCase(Locale.ROOT),   // the site redirects to lower case
                description(job.path("jobDescription")),
                null,                                                        // no posting date (see the class comment)
                null,
                job);
    }

    private static String element(JsonNode array, int index) {
        JsonNode value = array.path(index);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    /** The description, responsibilities and who they look for (each in HTML when the site has it, else its plain text). */
    static String description(JsonNode texts) {
        StringBuilder html = new StringBuilder();
        append(html, null, content(texts, "websiteDescription"));
        append(html, null, content(texts, "hhDescription"));
        append(html, "Responsibilities", first(content(texts, "responsibilitiesHtml"), content(texts, "responsibilities")));
        append(html, "Who we are looking for", first(content(texts, "peopleWeAreLookingForHtml"),
                content(texts, "peopleWeAreLookingFor"), content(texts, "peopleWeAreLookingForStr")));
        return html.isEmpty() ? null : html.toString();
    }

    /** A text field, which the site sends as a string or as a list of strings (joined line by line); else null. */
    static String content(JsonNode texts, String field) {
        JsonNode value = texts.path(field);
        if (value.isString()) {
            return value.asString().isBlank() ? null : value.asString();
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(part -> {
                if (part.isString() && !part.asString().isBlank()) {
                    parts.add(part.asString());
                }
            });
            return parts.isEmpty() ? null : String.join("\n", parts);
        }
        return null;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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

    /** The position's offices ("Hyderabad", "Bengaluru", "Gurugram"), all in the site's country. */
    static List<RawLocation> locations(JsonNode job, DeShawConfig config) {
        List<RawLocation> locations = new ArrayList<>();
        for (JsonNode office : job.path("jobMetadata").path("jobLocations")) {
            String name = text(office, "name");
            if (name != null) {
                locations.add(new RawLocation(name, name, null, config.country(), null));
            }
        }
        return locations;
    }

    /**
     * The typed view of companies.config for the D. E. Shaw row, all optional: "url" (the careers page, default
     * https://www.deshawindia.com/careers; job links are url + "/" + the job's slug) and "country" (ISO code of the
     * site's offices, default "IN").
     */
    record DeShawConfig(String url, String country) {

        static DeShawConfig from(Company company) {
            String url = text(company.config(), "url");
            String country = text(company.config(), "country");
            return new DeShawConfig(url != null ? url.replaceAll("/+$", "") : CAREERS,
                    country != null ? country.toUpperCase(Locale.ROOT) : "IN");
        }
    }
}
