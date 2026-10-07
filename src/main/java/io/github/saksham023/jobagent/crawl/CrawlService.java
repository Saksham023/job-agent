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
import java.util.HashMap;
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
 * A long crawl (a streaming adapter) saves in batches as it goes; if it then fails, what was saved stays and the
 * failure is reported as a PartialCrawlException.
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
        return run(company, false, false);
    }

    /** Fetch, normalize, filter, then upsert the kept jobs. */
    public CrawlResult crawl(Company company) {
        return run(company, true, false);
    }

    /** The quick check for new jobs: only new or changed jobs are fetched and upserted (see JobBoardAdapter). */
    public CrawlResult crawlNewest(Company company) {
        return run(company, true, true);
    }

    /** True when the company's adapter has the quick check for new jobs. */
    public boolean supportsNewestCheck(Company company) {
        return isSupported(company) && adapterFor(company).supportsNewestCheck(company);
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

    private CrawlResult run(Company company, boolean save, boolean newestOnly) {
        JobBoardAdapter adapter = adapterFor(company);
        Instant seenAt = Instant.now().truncatedTo(ChronoUnit.MICROS);   // Postgres stores microseconds
        long startNanos = System.nanoTime();

        Progress progress = new Progress(company, save, seenAt);
        try {
            // network: outside any transaction. A streaming adapter hands over finished jobs in batches (each batch is
            // saved at once); the rest, usually everything for a quick adapter, comes back at the end.
            List<RawJob> rest = newestOnly ? adapter.fetchNewestJobs(company, progress::accept)
                    : adapter.fetchJobs(company, progress::accept);
            progress.accept(rest);
        } catch (RuntimeException e) {
            if (progress.savedAnything()) {
                throw new PartialCrawlException(progress.result(adapter.platform(), elapsedMs(startNanos)), e);
            }
            throw e;
        }

        CrawlResult result = progress.result(adapter.platform(), elapsedMs(startNanos));
        log.info("{}: {} fetched {} via {}, kept {} in {}, other {}, unresolved {}, skipped {}{}{} ({} ms)",
                company.slug(), !save ? "PREVIEW" : newestOnly ? "NEWEST" : "CRAWL", result.fetched(), adapter.platform(), result.kept(),
                wantedCountries, result.otherCountries(), result.unresolved(), result.skipped(),
                save ? ", inserted " + result.inserted() + ", updated " + result.updated() + ", unchanged "
                        + result.unchanged() + ", extracted " + result.extracted() : "",
                result.detailsFetched() + result.detailsReused() > 0
                        ? ", details fetched " + result.detailsFetched() + " / reused " + result.detailsReused() : "",
                result.elapsedMs());
        return result;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** A crawl that failed after saving part of its jobs: what was saved stays, and the partial numbers are kept. */
    public static class PartialCrawlException extends RuntimeException {
        private final transient CrawlResult partial;

        PartialCrawlException(CrawlResult partial, RuntimeException cause) {
            super("stopped after saving " + (partial.inserted() + partial.updated() + partial.unchanged()) + " jobs: "
                    + cause.getMessage(), cause);
            this.partial = partial;
        }

        public CrawlResult partial() {
            return partial;
        }
    }

    /**
     * What one crawl has seen and saved so far. A quick adapter hands everything over once; a streaming one hands over
     * batches, and each batch is normalized, filtered, saved (one short transaction) and its requirements extracted
     * before the next arrives.
     */
    private final class Progress {

        private final Company company;
        private final boolean save;
        private final Instant seenAt;
        private final List<NormalizedJob> kept = new ArrayList<>();
        private final Map<String, Long> unresolvedLocations = new HashMap<>();
        private int fetched, skipped, otherCountries, unresolved, inserted, updated, unchanged, extracted;
        private int detailsFetched, detailsReused;

        Progress(Company company, boolean save, Instant seenAt) {
            this.company = company;
            this.save = save;
            this.seenAt = seenAt;
        }

        void accept(List<RawJob> raws) {
            if (raws.isEmpty()) {
                return;
            }
            fetched += raws.size();
            detailsFetched += (int) raws.stream().filter(j -> j.detail() == RawJob.DetailSource.FETCHED).count();
            detailsReused += (int) raws.stream().filter(j -> j.detail() == RawJob.DetailSource.REUSED).count();

            List<NormalizedJob> normalized = normalizeAll(company, raws);
            List<NormalizedJob> keptNow = normalized.stream().filter(job -> job.isInAnyCountry(wantedCountries)).toList();
            int unresolvedNow = (int) normalized.stream().filter(NormalizedJob::hasNoResolvedCountry).count();
            skipped += raws.size() - normalized.size();
            unresolved += unresolvedNow;
            otherCountries += normalized.size() - keptNow.size() - unresolvedNow;
            unresolvedLocationCounts(normalized).forEach((place, n) -> unresolvedLocations.merge(place, n, Long::sum));
            kept.addAll(keptNow);

            if (save) {
                Map<UpsertOutcome, Integer> outcomes = saveAll(keptNow, seenAt);
                int insertedNow = outcomes.getOrDefault(UpsertOutcome.INSERTED, 0);
                int updatedNow = outcomes.getOrDefault(UpsertOutcome.UPDATED, 0);
                inserted += insertedNow;
                updated += updatedNow;
                unchanged += outcomes.getOrDefault(UpsertOutcome.UNCHANGED, 0);
                if (insertedNow + updatedNow > 0) {
                    extracted += extractRequirements(company);
                }
            }
        }

        boolean savedAnything() {
            return save && inserted + updated + unchanged > 0;
        }

        CrawlResult result(String platform, long elapsedMs) {
            return new CrawlResult(company.slug(), platform, save, fetched, skipped, kept.size(), otherCountries,
                    unresolved, inserted, updated, unchanged, extracted, sortedByCount(unresolvedLocations),
                    List.copyOf(kept), elapsedMs, seenAt, detailsFetched, detailsReused);
        }
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

        return sortedByCount(counts);
    }

    /** The same counts, most frequent first. */
    private static Map<String, Long> sortedByCount(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}