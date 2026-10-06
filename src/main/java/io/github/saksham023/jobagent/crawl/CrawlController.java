package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.company.CompanyRepository;
import io.github.saksham023.jobagent.crawl.CrawlService.CrawlResult;
import io.github.saksham023.jobagent.job.NormalizedJob;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for crawling. Local use only (no authentication).
 * GET /admin/crawl/{slug}/preview fetches live and saves nothing; POST /admin/crawl[/{slug}] crawl, save, judge the
 * crawl's health and close jobs that disappeared (CrawlRunService); GET /admin/health shows every company's last crawl.
 */
@RestController
@RequestMapping("/admin")
public class CrawlController {

    private final CompanyRepository companyRepository;
    private final CrawlService crawlService;
    private final CrawlRunService crawlRunService;
    private final CrawlRunRepository crawlRuns;

    public CrawlController(CompanyRepository companyRepository, CrawlService crawlService,
                           CrawlRunService crawlRunService, CrawlRunRepository crawlRuns) {
        this.companyRepository = companyRepository;
        this.crawlService = crawlService;
        this.crawlRunService = crawlRunService;
        this.crawlRuns = crawlRuns;
    }

    /**
     * Fetches and normalizes a company's jobs live and reports what would be kept. Saves nothing.
     *
     * @param limit how many kept jobs to include in the sample
     * @param full  true: sample shows whole NormalizedJobs (description, raw); false: a compact summary
     */
    @GetMapping("/crawl/{slug}/preview")
    public CrawlReport preview(@PathVariable String slug,
                               @RequestParam(defaultValue = "5") int limit,
                               @RequestParam(defaultValue = "false") boolean full) {
        CrawlResult result = crawlService.preview(supportedCompany(slug));
        List<NormalizedJob> kept = result.keptJobs();
        List<NormalizedJob> sampleJobs = kept.subList(0, Math.min(Math.max(limit, 0), kept.size()));
        List<?> sample = full ? sampleJobs : sampleJobs.stream().map(JobSummary::of).toList();
        return CrawlReport.of(result, null, sample);
    }

    /** Crawls one company, saves its kept jobs, records the run and closes jobs it no longer lists. */
    @PostMapping("/crawl/{slug}")
    public CompanyCrawl crawl(@PathVariable String slug) {
        return CompanyCrawl.of(crawlRunService.crawlOne(supportedCompany(slug), "manual"));
    }

    /** Crawls every enabled company that has an adapter, one thread per server (the scheduled run, by hand). */
    @PostMapping("/crawl")
    public List<CompanyCrawl> crawlAll() {
        return crawlRunService.runAll("manual").stream().map(CompanyCrawl::of).toList();
    }

    /** Every company's open jobs and latest crawl; the ones that need a look (not OK, or alerts) first. */
    @GetMapping("/health")
    public List<CrawlRunRepository.CompanyHealth> health() {
        return crawlRuns.health();
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
            int extracted,
            int detailsFetched,
            int detailsReused,
            Integer closed,
            long elapsedMs,
            Map<String, Long> unresolvedLocations,
            List<?> sample
    ) {
        /** @param closed jobs closed by this crawl, or null for a preview */
        static CrawlReport of(CrawlResult r, Integer closed, List<?> sample) {
            return new CrawlReport(r.company(), r.platform(), r.saved(), r.fetched(), r.skipped(), r.kept(),
                    r.otherCountries(), r.unresolved(), r.inserted(), r.updated(), r.unchanged(), r.extracted(),
                    r.detailsFetched(), r.detailsReused(), closed,
                    r.elapsedMs(), r.unresolvedLocations(), sample);
        }
    }

    /** One company's crawl: OK, SUSPECT (saved, nothing closed) or FAILED, with the health alerts and the numbers. */
    record CompanyCrawl(String company, String server, String status, List<String> alerts, String error,
                        CrawlReport report) {
        static CompanyCrawl of(CrawlRunService.RunOutcome o) {
            return new CompanyCrawl(o.company(), o.server(), o.status().name(), o.alerts(), o.error(),
                    o.result() == null ? null : CrawlReport.of(o.result(), o.closed(), List.of()));
        }
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