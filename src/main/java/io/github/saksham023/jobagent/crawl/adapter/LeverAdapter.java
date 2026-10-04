package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.JobBoardAdapter;
import io.github.saksham023.jobagent.crawl.RawJob;
import io.github.saksham023.jobagent.crawl.RawLocation;
import org.jsoup.nodes.Entities;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.addIfPresent;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.fromEpochMillis;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.isoCountry;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.text;
import static io.github.saksham023.jobagent.crawl.adapter.JsonFields.value;

/**
 * Lever public Postings API (https://github.com/lever/postings-api).
 * One GET returns every posting as a JSON array. Each posting has a structured ISO country and a
 * workplaceType (onsite/remote/hybrid); the description is split across description, lists[] and additional.
 */
@Component
public class LeverAdapter implements JobBoardAdapter {

    private static final String POSTINGS_URL = "https://api.lever.co/v0/postings/{site}?mode=json";

    private final RestClient http;

    public LeverAdapter(RestClient crawlRestClient) {
        this.http = crawlRestClient;
    }

    @Override
    public String platform() {
        return "lever";
    }

    @Override
    public List<RawJob> fetchJobs(Company company) {
        LeverConfig config = LeverConfig.from(company);

        JsonNode body = http.get()
                .uri(POSTINGS_URL, config.site())
                .retrieve()
                .body(JsonNode.class);

        if (body == null || !body.isArray()) {
            throw new IllegalStateException(company.slug() + ": Lever response is not a JSON array");
        }

        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode posting : body) {
            jobs.add(toRawJob(posting));
        }
        return jobs;
    }

    private RawJob toRawJob(JsonNode posting) {
        JsonNode categories = posting.path("categories");
        String department = text(categories, "department");
        return new RawJob(
                text(posting, "id"),
                text(posting, "text"),
                department != null ? department : text(categories, "team"),
                null,                                           // no structured job function
                locations(posting),
                text(categories, "commitment"),                 // "full time", "Intern", ...
                text(posting, "hostedUrl"),
                descriptionHtml(posting),
                fromEpochMillis(posting.path("createdAt")),
                null,                                           // Lever's public API has no updated-at
                posting
        );
    }

    /**
     * categories.allLocations (plus categories.location), each with the structured remote flag.
     * Lever gives ONE country per posting, so it is passed as a hint only when there is a single location:
     * for ["Noida", "Dubai"] with country "IN", forcing IN onto Dubai would be wrong.
     */
    private static List<RawLocation> locations(JsonNode posting) {
        JsonNode categories = posting.path("categories");
        Set<String> texts = new LinkedHashSet<>();
        for (JsonNode location : categories.path("allLocations")) {
            addIfPresent(texts, value(location));
        }
        addIfPresent(texts, text(categories, "location"));

        String country = texts.size() <= 1 ? isoCountry(text(posting, "country")) : null;
        Boolean remote = remoteFlag(text(posting, "workplaceType"));

        if (texts.isEmpty()) {
            return country == null ? List.of() : List.of(new RawLocation(null, null, null, country, remote));
        }
        return texts.stream()
                .map(text -> new RawLocation(text, null, null, country, remote))
                .toList();
    }

    /** description + each list as a heading with its items + additional, as one HTML string. */
    private static String descriptionHtml(JsonNode posting) {
        StringBuilder html = new StringBuilder();
        appendIfPresent(html, text(posting, "description"));
        for (JsonNode list : posting.path("lists")) {
            String heading = text(list, "text");
            if (heading != null) {
                html.append("<h3>").append(Entities.escape(heading)).append("</h3>");
            }
            String items = text(list, "content");
            if (items != null) {
                html.append("<ul>").append(items).append("</ul>");
            }
        }
        appendIfPresent(html, text(posting, "additional"));
        return html.isEmpty() ? null : html.toString();
    }

    /** "remote" -> true; "onsite"/"hybrid" -> false; anything else -> unknown (null). */
    private static Boolean remoteFlag(String workplaceType) {
        if (workplaceType == null) {
            return null;
        }
        return switch (workplaceType.toLowerCase()) {
            case "remote" -> Boolean.TRUE;
            case "onsite", "hybrid" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static void appendIfPresent(StringBuilder target, String value) {
        if (value != null) {
            target.append(value);
        }
    }

    /** The typed view of companies.config for Lever rows: {"site": "..."}. */
    record LeverConfig(String site) {

        static LeverConfig from(Company company) {
            String site = text(company.config(), "site");
            if (site == null) {
                throw new IllegalArgumentException(company.slug() + ": config.site is missing");
            }
            return new LeverConfig(site);
        }
    }
}