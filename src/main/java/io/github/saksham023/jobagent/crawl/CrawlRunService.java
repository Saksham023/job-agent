package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.company.CompanyRepository;
import io.github.saksham023.jobagent.crawl.CrawlHealth.Status;
import io.github.saksham023.jobagent.crawl.CrawlHealth.Verdict;
import io.github.saksham023.jobagent.crawl.CrawlRunRepository.CrawlRun;
import io.github.saksham023.jobagent.crawl.CrawlService.CrawlResult;
import io.github.saksham023.jobagent.requirements.GapFillRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Crawls with bookkeeping: every crawl is judged (CrawlHealth), recorded in crawl_runs, and, when it looks normal,
 * closes the jobs the company no longer lists. A full run crawls every enabled company with ONE VIRTUAL THREAD PER
 * SERVER: companies on the same server (a shared API like api.lever.co, or one Workday cluster) are crawled one after
 * another, so our IP never sends parallel streams to one server; different servers run at the same time, so a slow or
 * throttling company (Microsoft at 15 s per request) only delays the companies on its own server.
 * Every 6 hours (jobagent.crawl.schedule.cron) the scheduler runs this, then lets the model fill the new jobs' gaps.
 */
@Service
public class CrawlRunService {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunService.class);

    /** How many recent good crawls the health check compares with. */
    static final int RECENT_RUNS = 5;

    /** What one company crawl ended with; result is null when it failed. */
    public record RunOutcome(String company, String server, CrawlResult result, Status status, List<String> alerts,
                             int closed, String error) {
    }

    private final CompanyRepository companies;
    private final CrawlService crawlService;
    private final CrawlRunRepository runs;
    private final GapFillRunner gapFillRunner;
    private final CrawlScheduleProperties properties;
    private final AtomicBoolean fullRunGoing = new AtomicBoolean();

    public CrawlRunService(CompanyRepository companies, CrawlService crawlService, CrawlRunRepository runs,
                           GapFillRunner gapFillRunner, CrawlScheduleProperties properties) {
        this.companies = companies;
        this.crawlService = crawlService;
        this.runs = runs;
        this.gapFillRunner = gapFillRunner;
        this.properties = properties;
    }

    /** The scheduled run: every enabled company, then the gap fill for the new jobs. */
    @Scheduled(cron = "${jobagent.crawl.schedule.cron:0 0 */6 * * *}")
    void scheduledRun() {
        if (!properties.enabled()) {
            return;
        }
        try {
            runAll("schedule");
        } catch (IllegalStateException e) {
            log.warn("Scheduled crawl skipped: {}", e.getMessage());
            return;
        }
        if (properties.gapFill()) {
            try {
                gapFillRunner.start(properties.gapFillModel(), null, properties.gapFillParallelism());
            } catch (RuntimeException e) {
                log.warn("Gap fill after the scheduled crawl not started: {}", e.getMessage());
            }
        }
    }

    /** Crawls every enabled company that has an adapter, one virtual thread per server, and waits for all of them. */
    public List<RunOutcome> runAll(String trigger) {
        if (!fullRunGoing.compareAndSet(false, true)) {
            throw new IllegalStateException("A crawl of all companies is already running");
        }
        try {
            long start = System.nanoTime();
            List<Company> toCrawl = companies.findAll().stream()
                    .filter(Company::enabled).filter(crawlService::isSupported).toList();
            Map<String, List<Company>> byServer = groupByServer(toCrawl, crawlService::serverKey);
            log.info("Crawl run ({}): {} companies on {} servers {}", trigger, toCrawl.size(), byServer.size(),
                    byServer.keySet());

            Map<String, RunOutcome> outcomes = new ConcurrentHashMap<>();
            try (ExecutorService servers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("crawl-", 1).factory())) {
                byServer.forEach((server, group) -> servers.submit(() ->
                        group.forEach(company -> outcomes.put(company.slug(), crawlOne(company, trigger)))));
            }                                                       // close() waits for every server's thread

            List<RunOutcome> ordered = toCrawl.stream().map(c -> outcomes.get(c.slug())).toList();
            log.info("Crawl run ({}) finished in {} s: {} OK, {} suspect, {} failed; {} new jobs, {} closed", trigger,
                    (System.nanoTime() - start) / 1_000_000_000,
                    count(ordered, Status.OK), count(ordered, Status.SUSPECT), count(ordered, Status.FAILED),
                    ordered.stream().filter(o -> o.result() != null).mapToInt(o -> o.result().inserted()).sum(),
                    ordered.stream().mapToInt(RunOutcome::closed).sum());
            return ordered;
        } finally {
            fullRunGoing.set(false);
        }
    }

    /** Crawls one company and records the run; a failure is recorded and returned, not thrown. */
    public RunOutcome crawlOne(Company company, String trigger) {
        String server = crawlService.serverKey(company);
        Instant started = Instant.now();
        long startNanos = System.nanoTime();
        try {
            List<Integer> recent = runs.recentOkKept(company.id(), RECENT_RUNS);    // before this run is recorded
            CrawlResult result = crawlService.crawl(company);
            int noDescription = (int) result.keptJobs().stream().filter(job -> job.description() == null).count();
            Verdict verdict = CrawlHealth.judge(result.kept(), result.unresolved(), noDescription, recent);
            long runId = runs.insert(new CrawlRun(company.id(), trigger, result.seenAt(), Instant.now(), verdict.status(),
                    result.fetched(), result.kept(), result.inserted(), result.updated(), result.unchanged(),
                    result.unresolved(), noDescription, result.detailsFetched(), result.detailsReused(),
                    verdict.alerts(), null, result.elapsedMs()));
            int closed = verdict.status() == Status.OK
                    ? runs.closeMissing(company.id(), properties.closeAfterMisses(), runId) : 0;
            verdict.alerts().forEach(alert -> log.warn("{}: HEALTH {}", company.slug(), alert));
            if (closed > 0) {
                log.info("{}: closed {} jobs no longer listed", company.slug(), closed);
            }
            return new RunOutcome(company.slug(), server, result, verdict.status(), verdict.alerts(), closed, null);
        } catch (RuntimeException e) {
            List<String> alerts = List.of("crawl failed: " + e.getMessage());
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            runs.insert(new CrawlRun(company.id(), trigger, started, Instant.now(), Status.FAILED, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, alerts, e.toString(), elapsedMs));
            log.warn("{}: HEALTH crawl failed: {}", company.slug(), e.toString());
            return new RunOutcome(company.slug(), server, null, Status.FAILED, alerts, 0, e.toString());
        }
    }

    /** Companies by server, each group in the given order; groups in order of their first company. */
    static Map<String, List<Company>> groupByServer(List<Company> companies, Function<Company, String> serverOf) {
        Map<String, List<Company>> groups = new LinkedHashMap<>();
        for (Company company : companies) {
            groups.computeIfAbsent(serverOf.apply(company), k -> new ArrayList<>()).add(company);
        }
        return groups;
    }

    private static long count(List<RunOutcome> outcomes, Status status) {
        return outcomes.stream().filter(o -> o.status() == status).count();
    }
}