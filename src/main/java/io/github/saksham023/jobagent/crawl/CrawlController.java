package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.company.CompanyRepository;
import io.github.saksham023.jobagent.crawl.CrawlService.CrawlResult;
import io.github.saksham023.jobagent.job.NormalizedJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for crawling. Local use only (no authentication).
 * GET .../preview fetches live and saves nothing; POST endpoints crawl and save.
 */
@RestController
@RequestMapping("/admin/crawl")
public class CrawlController {

    private static final Logger log = LoggerFactory.getLogger(CrawlController.class);

    private final CompanyRepository companyRepository;
    private final CrawlService crawlService;

    public CrawlController(CompanyRepository companyRepository, CrawlService crawlService) {
        this.companyRepository = companyRepository;
        this.crawlService = crawlService;
    }

    /**
     * Fetches and normalizes a company's jobs live and reports what would be kept. Saves nothing.
     *
     * @param limit how many kept jobs to include in the sample
     * @param full  true: sample shows whole NormalizedJobs (description, raw); false: a compact summary
     */
    @GetMapping("/{slug}/preview")
    public CrawlReport preview(@PathVariable String slug,
                               @RequestParam(defaultValue = "5") int limit,
                               @RequestParam(defaultValue = "false") boolean full) {
        CrawlResult result = crawlService.preview(supportedCompany(slug));
        List<NormalizedJob> kept = result.keptJobs();
        List<NormalizedJob> sampleJobs = kept.subList(0, Math.min(Math.max(limit, 0), kept.size()));
        List<?> sample = full ? sampleJobs : sampleJobs.stream().map(JobSummary::of).toList();
        return CrawlReport.of(result, sample);
    }

    /** Crawls one company and saves its kept jobs. */
    @PostMapping("/{slug}")
    public CrawlReport crawl(@PathVariable String slug) {
        return CrawlReport.of(crawlService.crawl(supportedCompany(slug)), List.of());
    }

    /** Crawls every enabled company that has an adapter, one after another. A failure is reported, not fatal. */
    @PostMapping
    public List<CompanyCrawl> crawlAll() {
        List<CompanyCrawl> results = new ArrayList<>();
        for (Company company : companyRepository.findAll()) {
            if (!company.enabled()) {
                results.add(new CompanyCrawl(company.slug(), "SKIPPED", "disabled", null));
            } else if (!crawlService.isSupported(company)) {
                results.add(new CompanyCrawl(company.slug(), "SKIPPED", "no adapter for " + company.platform(), null));
            } else {
                results.add(crawlOne(company));
            }
        }
        return results;
    }

    private CompanyCrawl crawlOne(Company company) {
        try {
            return new CompanyCrawl(company.slug(), "OK", null, CrawlReport.of(crawlService.crawl(company), List.of()));
        } catch (RuntimeException e) {
            log.warn("{}: crawl failed: {}", company.slug(), e.toString());
            return new CompanyCrawl(company.slug(), "FAILED", e.toString(), null);
        }
    }

    /** slug -> Company, or 404 (unknown) / 501 (platform has no adapter yet). */
    private Company supportedCompany(String slug) {
        Company company = companyRepository.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown company: " + slug));
        if (!crawlService.isSupported(company)) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "No adapter yet for platform '" + company.platform() + "'");
        }
        return company;
    }

    // ---------------------------------------------------------------- response shapes

    /** The numbers of one crawl or preview, plus an optional sample of kept jobs. */
    record CrawlReport(
            String company,
            String platform,
            boolean saved,
            int fetched,
            int skipped,
            int kept,
            int otherCountries,
            int unresolved,
            int inserted,
            int updated,
            int unchanged,
            long elapsedMs,
            Map<String, Long> unresolvedLocations,
            List<?> sample
    ) {
        static CrawlReport of(CrawlResult r, List<?> sample) {
            return new CrawlReport(r.company(), r.platform(), r.saved(), r.fetched(), r.skipped(), r.kept(),
                    r.otherCountries(), r.unresolved(), r.inserted(), r.updated(), r.unchanged(), r.elapsedMs(),
                    r.unresolvedLocations(), sample);
        }
    }

    /** One line of a crawl-all: OK with a report, SKIPPED with a reason, or FAILED with the error. */
    record CompanyCrawl(String company, String status, String detail, CrawlReport report) {
    }

    /** The few fields you want when scanning results by eye. */
    record JobSummary(
            String externalId,
            String title,
            List<String> cities,
            List<String> countryCodes,
            boolean remote,
            List<String> locations,
            String url
    ) {
        static JobSummary of(NormalizedJob job) {
            return new JobSummary(job.externalId(), job.title(), job.cities(), job.countryCodes(), job.remote(),
                    job.locations(), job.url());
        }
    }
}