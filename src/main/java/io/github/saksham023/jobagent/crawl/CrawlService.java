package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.geo.ParsedLocation;
import io.github.saksham023.jobagent.geo.ParsedLocation.Status;
import io.github.saksham023.jobagent.job.JobRepository;
import io.github.saksham023.jobagent.job.JobRepository.UpsertOutcome;
import io.github.saksham023.jobagent.job.NormalizedJob;
import io.github.saksham023.jobagent.requirements.RequirementsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs crawls: picks the adapter for a company's platform, fetches, normalizes, keeps only jobs in the
 * configured countries, and (for a real crawl) upserts them in one short transaction, then extracts the
 * requirements of the jobs that are new or changed.
 * The HTTP fetch happens outside the transaction so a slow job board never holds a DB connection.
 */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);

    private final Map<String, JobBoardAdapter> adaptersByPlatform;
    private final JobNormalizer normalizer;
    private final JobRepository jobRepository;
    private final TransactionTemplate transactionTemplate;
    private final RequirementsService requirementsService;
    private final Set<String> wantedCountries;

    public CrawlService(List<JobBoardAdapter> adapters,
                        JobNormalizer normalizer,
                        JobRepository jobRepository,
                        PlatformTransactionManager transactionManager,
                        RequirementsService requirementsService,
                        @Value("${jobagent.crawl.countries:IN}") List<String> wantedCountries) {
        this.adaptersByPlatform = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(JobBoardAdapter::platform, Function.identity()));
        this.normalizer = normalizer;
        this.jobRepository = jobRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requirementsService = requirementsService;
        this.wantedCountries = wantedCountries.stream()
                .map(code -> code.strip().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        log.info("Job-board adapters registered: {}; keeping jobs in {}", adaptersByPlatform.keySet(), this.wantedCountries);
    }

    /**
     * What one crawl (or preview) of one company produced.
     *
     * @param fetched             jobs returned by the platform (all countries)
     * @param skipped             jobs that failed normalization (logged and left out)
     * @param kept                jobs with at least one place in a wanted country
     * @param otherCountries      jobs placed only in other countries (dropped)
     * @param unresolved          jobs with no place resolved to any country (dropped, but reported)
     * @param inserted            new jobs saved (0 for a preview)
     * @param updated             known jobs whose content changed (0 for a preview)
     * @param unchanged           known jobs seen again with the same content (0 for a preview)
     * @param extracted           jobs whose requirements were (re)extracted after saving (0 for a preview)
     * @param unresolvedLocations location segments we could not resolve, most frequent first, across ALL jobs
     * @param seenAt              the crawl's start: last_seen_at of every job it saw (crawl_runs.started_at)
     * @param detailsFetched      detail requests made (detail platforms)
     * @param detailsReused       stored details reused instead of a request
     */
    public record CrawlResult(
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
            Map<String, Long> unresolvedLocations,
            List<NormalizedJob> keptJobs,
            long elapsedMs,
            Instant seenAt,
            int detailsFetched,
            int detailsReused
    ) {
    }

    /** Fetch, normalize, filter. Saves nothing. */
    public CrawlResult preview(Company company) {
        return run(company, false);
    }

    /** Fetch, normalize, filter, then upsert the kept jobs. */
    public CrawlResult crawl(Company company) {
        return run(company, true);
    }

    /** The server the company's crawl talks to (see JobBoardAdapter.serverKey). */
    public String serverKey(Company company) {
        return adapterFor(company).serverKey(company);
    }

    /** True when an adapter exists for this company's platform. */
    public boolean isSupported(Company company) {
        return adaptersByPlatform.containsKey(company.platform());
    }

    // ---------------------------------------------------------------- the pipeline

    private CrawlResult run(Company company, boolean save) {
        JobBoardAdapter adapter = adapterFor(company);
        Instant seenAt = Instant.now().truncatedTo(ChronoUnit.MICROS);   // Postgres stores microseconds
        long startNanos = System.nanoTime();

        List<RawJob> rawJobs = adapter.fetchJobs(company);                // network: outside any transaction
        List<NormalizedJob> normalized = normalizeAll(company, rawJobs);

        List<NormalizedJob> kept = normalized.stream()
                .filter(job -> job.isInAnyCountry(wantedCountries))
                .toList();
        int unresolved = (int) normalized.stream().filter(NormalizedJob::hasNoResolvedCountry).count();
        int otherCountries = normalized.size() - kept.size() - unresolved;
        int skipped = rawJobs.size() - normalized.size();

        Map<UpsertOutcome, Integer> outcomes = save ? saveAll(kept, seenAt) : Map.of();
        int inserted = outcomes.getOrDefault(UpsertOutcome.INSERTED, 0);
        int updated = outcomes.getOrDefault(UpsertOutcome.UPDATED, 0);
        int unchanged = outcomes.getOrDefault(UpsertOutcome.UNCHANGED, 0);
        int extracted = inserted + updated > 0 ? extractRequirements(company) : 0;

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        int detailsFetched = (int) rawJobs.stream().filter(j -> j.detail() == RawJob.DetailSource.FETCHED).count();
        int detailsReused = (int) rawJobs.stream().filter(j -> j.detail() == RawJob.DetailSource.REUSED).count();
        log.info("{}: {} fetched {} via {}, kept {} in {}, other {}, unresolved {}, skipped {}{}{} ({} ms)",
                company.slug(), save ? "CRAWL" : "PREVIEW", rawJobs.size(), adapter.platform(), kept.size(),
                wantedCountries, otherCountries, unresolved, skipped,
                save ? ", inserted " + inserted + ", updated " + updated + ", unchanged " + unchanged
                        + ", extracted " + extracted : "",
                detailsFetched + detailsReused > 0
                        ? ", details fetched " + detailsFetched + " / reused " + detailsReused : "",
                elapsedMs);

        return new CrawlResult(company.slug(), adapter.platform(), save, rawJobs.size(), skipped, kept.size(),
                otherCountries, unresolved, inserted, updated, unchanged, extracted,
                unresolvedLocationCounts(normalized), kept, elapsedMs, seenAt, detailsFetched, detailsReused);
    }

    /**
     * Runs requirement extraction for jobs that are new or changed (the jobs table is already committed).
     * A failure here is logged and does not fail the crawl: the next rebuild picks the jobs up again.
     */
    private int extractRequirements(Company company) {
        try {
            return requirementsService.rebuild(false).extracted();
        } catch (RuntimeException e) {
            log.warn("{}: requirement extraction after crawl failed: {}", company.slug(), e.toString());
            return 0;
        }
    }

    /** Normalizes each job on its own, so one malformed posting is skipped instead of failing the company. */
    private List<NormalizedJob> normalizeAll(Company company, List<RawJob> rawJobs) {
        List<NormalizedJob> result = new ArrayList<>(rawJobs.size());
        for (RawJob raw : rawJobs) {
            try {
                result.add(normalizer.normalize(company, raw));
            } catch (RuntimeException e) {
                log.warn("{}: skipping job {} '{}': {}", company.slug(), raw.externalId(), raw.title(), e.toString());
            }
        }
        return result;
    }

    /** All upserts of one crawl in one transaction: either every kept job is refreshed, or none is. */
    private Map<UpsertOutcome, Integer> saveAll(List<NormalizedJob> jobs, Instant seenAt) {
        return transactionTemplate.execute(status -> {
            Map<UpsertOutcome, Integer> counts = new EnumMap<>(UpsertOutcome.class);
            for (NormalizedJob job : jobs) {
                counts.merge(jobRepository.upsert(job, seenAt), 1, Integer::sum);
            }
            return counts;
        });
    }

    private JobBoardAdapter adapterFor(Company company) {
        JobBoardAdapter adapter = adaptersByPlatform.get(company.platform());
        if (adapter == null) {
            throw new IllegalStateException(
                    "No adapter for platform '" + company.platform() + "' (company " + company.slug() + ")");
        }
        return adapter;
    }

    /** Every non-RESOLVED location segment, counted, most frequent first. This is the "add an alias" to-do list. */
    private static Map<String, Long> unresolvedLocationCounts(List<NormalizedJob> jobs) {
        Map<String, Long> counts = jobs.stream()
                .flatMap(job -> job.places().stream())
                .filter(place -> place.status() != Status.RESOLVED)
                .collect(Collectors.groupingBy(ParsedLocation::raw, Collectors.counting()));

        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}