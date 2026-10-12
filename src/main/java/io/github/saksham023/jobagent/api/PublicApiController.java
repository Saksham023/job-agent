package io.github.saksham023.jobagent.api;

import io.github.saksham023.jobagent.api.PublicJobRepository.CompanyRow;
import io.github.saksham023.jobagent.api.PublicJobRepository.Count;
import io.github.saksham023.jobagent.api.PublicJobRepository.JobCard;
import io.github.saksham023.jobagent.api.PublicJobRepository.JobDetail;
import io.github.saksham023.jobagent.api.PublicJobRepository.Totals;
import io.github.saksham023.jobagent.matching.MatchingProperties;
import io.github.saksham023.jobagent.requirements.JobFamily;
import io.github.saksham023.jobagent.common.ApiKeyFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The public, read-only job board API behind the web UI (no AI, no admin actions): the lists to filter by and the
 * search over open jobs.
 *
 * GET /api/v1/meta        companies, job families, cities, skills (each with open job counts) and totals
 * GET /api/v1/jobs        search: company, family, minYears, maxYears, includeUnstated, city, skill, q,
 *                         postedWithinDays, status (the signed-in user's: SAVED, APPLIED, REFERRAL_ASKED, NOT_INTERESTED;
 *                         none = everything except NOT_INTERESTED), hide (statuses also hidden for now, e.g.
 *                         APPLIED; ignored while status is given), sort (newest|company|experience), page, size; repeat
 *                         a parameter for several values (?family=SOFTWARE_ENGINEERING&family=DATA_ML)
 * GET /api/v1/facets      live counts for every filter list under the current filters (same parameters)
 * GET /api/v1/jobs/{id}   one open job in full
 */
@RestController
@RequestMapping("/api/v1")
public class PublicApiController {

    /** A family as the UI lists it. */
    public record FamilyOption(String id, String label, String group, int jobs) {
    }

    public record Meta(Totals totals, List<CompanyRow> companies, List<FamilyOption> families, List<Count> cities,
                       List<Count> skills) {
    }

    public record JobPage(int total, int page, int size, boolean hasMore, List<JobCard> jobs) {
    }

    private static final Map<JobFamily, String> LABELS = Map.ofEntries(
            Map.entry(JobFamily.SOFTWARE_ENGINEERING, "Software Engineering"),
            Map.entry(JobFamily.DATA_ML, "Data & ML"),
            Map.entry(JobFamily.INFRA_DEVOPS, "Infra & DevOps"),
            Map.entry(JobFamily.SECURITY, "Security"),
            Map.entry(JobFamily.QA, "QA & Testing"),
            Map.entry(JobFamily.ENG_MANAGEMENT, "Engineering Management"),
            Map.entry(JobFamily.HARDWARE_ENGINEERING, "Hardware & Chips"),
            Map.entry(JobFamily.SALES_ENGINEERING, "Solutions & Sales Engineering"),
            Map.entry(JobFamily.PRODUCT, "Product"),
            Map.entry(JobFamily.DESIGN, "Design"),
            Map.entry(JobFamily.PROGRAM_MANAGEMENT, "Program Management"),
            Map.entry(JobFamily.ANALYTICS, "Analytics"),
            Map.entry(JobFamily.QUANT, "Quant Research & Trading"),
            Map.entry(JobFamily.SALES, "Sales"),
            Map.entry(JobFamily.MARKETING, "Marketing"),
            Map.entry(JobFamily.FINANCE, "Finance"),
            Map.entry(JobFamily.RISK_COMPLIANCE, "Risk & Compliance"),
            Map.entry(JobFamily.HR, "HR & Recruiting"),
            Map.entry(JobFamily.LEGAL, "Legal"),
            Map.entry(JobFamily.SUPPORT, "Customer Support"),
            Map.entry(JobFamily.OPERATIONS, "Operations"),
            Map.entry(JobFamily.UNCLASSIFIED, "Other"));

    private final PublicJobRepository repository;
    private final String country;

    public PublicApiController(PublicJobRepository repository, MatchingProperties matching) {
        this.repository = repository;
        this.country = matching.country();
    }

    @GetMapping("/meta")
    public Meta meta() {
        Map<String, Integer> byFamily = repository.families(country).stream()
                .collect(java.util.stream.Collectors.toMap(Count::name, Count::jobs));
        List<FamilyOption> families = Arrays.stream(JobFamily.values())
                .map(f -> new FamilyOption(f.name(), LABELS.getOrDefault(f, f.name()), f.group().name(),
                        byFamily.getOrDefault(f.name(), 0)))
                .filter(f -> f.jobs() > 0)
                .toList();
        return new Meta(repository.totals(country), repository.companies(country), families,
                repository.cities(country, 30), repository.skills(country, 80));
    }

    @GetMapping("/jobs")
    public JobPage jobs(@RequestParam(required = false) List<String> company,
                        @RequestParam(required = false) List<String> family,
                        @RequestParam(required = false) Integer minYears,
                        @RequestParam(required = false) Integer maxYears,
                        @RequestParam(defaultValue = "true") boolean includeUnstated,
                        @RequestParam(required = false) List<String> city,
                        @RequestParam(required = false) List<String> skill,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false) Integer postedWithinDays,
                        @RequestParam(required = false) List<String> status,
                        @RequestParam(required = false) List<String> hide,
                        @RequestParam(defaultValue = "newest") String sort,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "24") int size,
                        @AuthenticationPrincipal Jwt jwt,
                        HttpServletRequest request) {
        JobSearch search = search(company, family, minYears, maxYears, includeUnstated, city, skill, q, postedWithinDays,
                status, hide, sort, page, size, jwt, request);
        int total = repository.count(search, country);
        List<JobCard> jobs = repository.search(search, country);
        return new JobPage(total, search.page(), search.size(),
                (long) (search.page() + 1) * search.size() < total, jobs);
    }

    /** Live counts for every filter list under the current filters (same parameters as /jobs). */
    @GetMapping("/facets")
    public PublicJobRepository.Facets facets(@RequestParam(required = false) List<String> company,
                                             @RequestParam(required = false) List<String> family,
                                             @RequestParam(required = false) Integer minYears,
                                             @RequestParam(required = false) Integer maxYears,
                                             @RequestParam(defaultValue = "true") boolean includeUnstated,
                                             @RequestParam(required = false) List<String> city,
                                             @RequestParam(required = false) List<String> skill,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) Integer postedWithinDays,
                                             @RequestParam(required = false) List<String> status,
                                             @RequestParam(required = false) List<String> hide,
                                             @AuthenticationPrincipal Jwt jwt,
                                             HttpServletRequest request) {
        return repository.facets(search(company, family, minYears, maxYears, includeUnstated, city, skill, q,
                postedWithinDays, status, hide, "newest", 0, 1, jwt, request), country, 80);
    }

    @GetMapping("/jobs/{id}")
    public JobDetail job(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return repository.detail(id, country, userId(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No open job " + id));
    }

    private static JobSearch search(List<String> company, List<String> family, Integer minYears, Integer maxYears,
                                    boolean includeUnstated, List<String> city, List<String> skill, String q,
                                    Integer postedWithinDays, List<String> status, List<String> hide, String sort,
                                    int page, int size, Jwt jwt, HttpServletRequest request) {
        JobSearch search = new JobSearch(company, JobSearch.families(family), minYears, maxYears, includeUnstated, city,
                skill, q, postedWithinDays, sort(sort), page, size, JobStatus.parse(status), JobStatus.parse(hide),
                userId(jwt));
        return ApiKeyFilter.isTrusted(request) ? search : search.checkedForVisitor();       // the API key lifts the limits
    }

    /** The signed-in user; null for a caller with the shared admin key (no account, so no statuses). */
    private static Long userId(Jwt jwt) {
        return jwt == null ? null : Long.parseLong(jwt.getSubject());
    }

    private static JobSearch.Sort sort(String value) {
        try {
            return JobSearch.Sort.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sort must be newest, company or experience");
        }
    }
}
