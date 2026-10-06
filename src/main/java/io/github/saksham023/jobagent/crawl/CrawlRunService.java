package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.common.ShutdownSignal;
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
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    /**
     * One whole crawl job (what the scheduler runs, and POST /admin/crawl): every company, then the gap fill.
     *
     * @param gapFill the gap fill's final numbers, or null when it is switched off or could not start
     * @param note    why the gap fill did not run, when it did not
     */
    public record CrawlJobReport(List<RunOutcome> companies, List<String> skipped, long ok, long suspect, long failed,
                                 long partial, int newJobs,
                                 int updatedJobs, int closedJobs, int jobsExtracted, int detailsFetched,
                                 int detailsReused, GapFillRunner.RunStatus gapFill, String note, long seconds) {
    }

    /**
     * One full run: the companies crawled, the ones skipped because they were crawled too recently, and the gap fill of
     * each company that had new or changed jobs (run right after that company's crawl).
     */
    public record Run(List<RunOutcome> outcomes, List<String> skipped, List<GapFillRunner.RunStatus> gapFills) {
    }

    /** What one company crawl ended with; result is null when it failed. */
    public record RunOutcome(String company, String server, CrawlResult result, Status status, List<String> alerts,
                             int closed, String error) {
    }

    private final CompanyRepository companies;
    private final CrawlService crawlService;
    private final CrawlRunRepository runs;
    private final GapFillRunner gapFillRunner;
    private final CrawlScheduleProperties properties;
    private final ShutdownSignal shutdown;
    private final AtomicBoolean fullRunGoing = new AtomicBoolean();

    public CrawlRunService(CompanyRepository companies, CrawlService crawlService, CrawlRunRepository runs,
                           GapFillRunner gapFillRunner, CrawlScheduleProperties properties, ShutdownSignal shutdown) {
        this.companies = companies;
        this.crawlService = crawlService;
        this.runs = runs;
        this.gapFillRunner = gapFillRunner;
        this.properties = properties;
        this.shutdown = shutdown;
        log.info("Crawl schedule: {}", properties.enabled()
                ? "ON (cron " + properties.cron() + ", gap fill " + (properties.gapFill() ? "on" : "off") + ")"
                : "OFF (companies are crawled only by POST /admin/crawl)");
    }

    /** The scheduled run (when enabled): the whole crawl job. */
    @Scheduled(cron = "${jobagent.crawl.schedule.cron:0 0 */6 * * *}")
    void scheduledRun() {
        if (!properties.enabled() || shutdown.isStopping()) {
            return;
        }
        try {
            crawlJob("schedule");
        } catch (RuntimeException e) {
            log.warn("Scheduled crawl skipped: {}", e.getMessage());
        }
    }

    /**
     * The whole crawl job, end to end: crawl every enabled company (each crawl saves its jobs, extracts their
     * requirements with the rules, checks its health and closes jobs that disappeared), then let the model fill the
     * gaps the rules left in jobs never asked before, and wait for that to finish.
     */
    public CrawlJobReport crawlJob(String trigger) {
        long start = System.nanoTime();
        Run run = runAll(trigger);
        List<RunOutcome> outcomes = run.outcomes();
        List<GapFillRunner.RunStatus> fills = new ArrayList<>(run.gapFills());
        String note = null;
        if (!properties.gapFill()) {
            note = "gap fill switched off (jobagent.crawl.schedule.gap-fill)";
        } else if (!shutdown.isStopping()) {
            // each company was filled right after its own crawl; this catch-all asks about whatever is still unasked
            // (a company whose fill failed, jobs from earlier runs)
            try {
                fills.add(gapFillRunner.runAndWait(properties.gapFillModel(), null, properties.gapFillParallelism()));
            } catch (RuntimeException e) {
                note = "gap fill not run: " + e.getMessage();
                log.warn("Gap fill after the crawl: {}", e.getMessage());
            }
        }
        GapFillRunner.RunStatus gapFill = fills.isEmpty() ? null : GapFillRunner.RunStatus.combine(fills);
        List<CrawlResult> results = outcomes.stream().map(RunOutcome::result).filter(Objects::nonNull).toList();
        CrawlJobReport report = new CrawlJobReport(outcomes, run.skipped(), count(outcomes, Status.OK), count(outcomes, Status.SUSPECT),
                count(outcomes, Status.FAILED), count(outcomes, Status.PARTIAL),
                results.stream().mapToInt(CrawlResult::inserted).sum(),
                results.stream().mapToInt(CrawlResult::updated).sum(), outcomes.stream().mapToInt(RunOutcome::closed).sum(),
                results.stream().mapToInt(CrawlResult::extracted).sum(),
                results.stream().mapToInt(CrawlResult::detailsFetched).sum(),
                results.stream().mapToInt(CrawlResult::detailsReused).sum(), gapFill, note,
                (System.nanoTime() - start) / 1_000_000_000);
        log.info("Crawl job ({}) finished in {} s: {} OK, {} suspect, {} failed, {} partial; {} new, {} updated, {} closed; gap fill {}",
                trigger, report.seconds(), report.ok(), report.suspect(), report.failed(), report.partial(), report.newJobs(),
                report.updatedJobs(), report.closedJobs(), gapFill == null ? note
                        : gapFill.done() + " jobs, $" + String.format(Locale.ROOT, "%.2f", gapFill.costUsd()));
        return report;
    }

    /** Crawls every enabled company that has an adapter, one virtual thread per server, and waits for all of them. */
    public Run runAll(String trigger) {
        if (!fullRunGoing.compareAndSet(false, true)) {
            throw new IllegalStateException("A crawl of all companies is already running");
        }
        try {
            long start = System.nanoTime();
            Instant now = Instant.now();
            Map<Long, Instant> lastStarts = runs.lastCrawlStarts();
            List<Company> toCrawl = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (Company company : companies.findAll()) {
                if (!company.enabled() || !crawlService.isSupported(company)) {
                    continue;
                }
                String wait = tooSoon(company, lastStarts.get(company.id()), now);
                if (wait == null) {
                    toCrawl.add(company);
                } else {
                    skipped.add(company.slug() + ": " + wait);
                    log.info("{}: not crawled this run ({})", company.slug(), wait);
                }
            }
            Map<String, List<Company>> byServer = groupByServer(toCrawl, crawlService::serverKey);
            log.info("Crawl run ({}): {} companies on {} servers {}", trigger, toCrawl.size(), byServer.size(),
                    byServer.keySet());

            Map<String, RunOutcome> outcomes = new ConcurrentHashMap<>();
            List<GapFillRunner.RunStatus> gapFills = Collections.synchronizedList(new ArrayList<>());
            // Resources close in reverse order: first the crawl threads (one per server) finish, then the gap fill queue
            // drains. A company's gap fill is queued the moment its crawl ends, so a slow company never delays it.
            try (ExecutorService gapFillQueue = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("gap-fill-queue-", 1).factory());
                 ExecutorService servers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("crawl-", 1).factory())) {
                byServer.forEach((server, group) -> servers.submit(() -> group.forEach(company -> {
                    if (shutdown.isStopping()) {
                        return;                                      // the app is shutting down: start nothing new
                    }
                    RunOutcome outcome = crawlOne(company, trigger);
                    outcomes.put(company.slug(), outcome);
                    queueGapFill(gapFillQueue, company, outcome, gapFills);
                })));
            }

            List<RunOutcome> ordered = toCrawl.stream().map(c -> outcomes.get(c.slug())).filter(Objects::nonNull).toList();
            log.info("Crawl run ({}) finished in {} s: {} OK, {} suspect, {} failed, {} partial; {} new jobs, {} closed", trigger,
                    (System.nanoTime() - start) / 1_000_000_000,
                    count(ordered, Status.OK), count(ordered, Status.SUSPECT), count(ordered, Status.FAILED),
                    count(ordered, Status.PARTIAL),
                    ordered.stream().filter(o -> o.result() != null).mapToInt(o -> o.result().inserted()).sum(),
                    ordered.stream().mapToInt(RunOutcome::closed).sum());
            return new Run(ordered, List.copyOf(skipped), List.copyOf(gapFills));
        } finally {
            fullRunGoing.set(false);
        }
    }

    /**
     * Queues the gap fill of one company's jobs. One queue, one worker: the fills run one after another, so the model is
     * never asked more than gapFillParallelism questions at once, whatever number of companies finish together.
     */
    private void queueGapFill(ExecutorService queue, Company company, RunOutcome outcome,
                              List<GapFillRunner.RunStatus> gapFills) {
        CrawlResult result = outcome.result();
        if (!properties.gapFill() || result == null || result.inserted() + result.updated() == 0) {
            return;
        }
        ShutdownSignal.Activity activity = shutdown.track();       // a shutdown waits for a fill that has been queued
        queue.submit(() -> {
            try (activity) {
                if (!shutdown.isStopping()) {
                    gapFills.add(gapFillRunner.fillCompany(company.id(), properties.gapFillModel(),
                            properties.gapFillParallelism()));
                }
            } catch (RuntimeException e) {
                log.warn("{}: gap fill failed: {}", company.slug(), e.getMessage());
            }
        });
    }

    /**
     * Why the company must not be crawled yet, or null when it may: its config.minCrawlHours (none = every run) has
     * not passed since its last crawl started (successful or not, so a failing site is not hit every run either).
     */
    static String tooSoon(Company company, Instant lastStart, Instant now) {
        JsonNode min = company.config() == null ? null : company.config().path("minCrawlHours");
        if (min == null || !min.isNumber() || lastStart == null) {
            return null;
        }
        Duration since = Duration.between(lastStart, now);
        if (since.toMinutes() >= min.asLong() * 60) {
            return null;
        }
        return "last crawl " + since.toHours() + " h ago, minimum " + min.asLong() + " h";
    }

    /** Crawls one company and records the run; a failure is recorded and returned, not thrown. */
    public RunOutcome crawlOne(Company company, String trigger) {
        try (ShutdownSignal.Activity activity = shutdown.track()) {      // a shutdown waits until the run is recorded
            return crawlTracked(company, trigger);
        }
    }

    private RunOutcome crawlTracked(Company company, String trigger) {
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
        } catch (CrawlService.PartialCrawlException e) {
            // some jobs were saved before the crawl stopped: they stay, the run is PARTIAL and closes nothing
            CrawlResult partial = e.partial();
            int noDescription = (int) partial.keptJobs().stream().filter(job -> job.description() == null).count();
            List<String> alerts = List.of("partial: " + e.getMessage());
            runs.insert(new CrawlRun(company.id(), trigger, partial.seenAt(), Instant.now(), Status.PARTIAL, partial.fetched(),
                    partial.kept(), partial.inserted(), partial.updated(), partial.unchanged(), partial.unresolved(),
                    noDescription, partial.detailsFetched(), partial.detailsReused(), alerts, e.getCause().toString(),
                    partial.elapsedMs()));
            log.warn("{}: HEALTH crawl PARTIAL: {}", company.slug(), e.getMessage());
            return new RunOutcome(company.slug(), server, partial, Status.PARTIAL, alerts, 0, e.getCause().toString());
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