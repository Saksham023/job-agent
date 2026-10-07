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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Function;

/**
 * Crawls with bookkeeping: every crawl is judged (CrawlHealth), recorded in crawl_runs, and, when it looks normal,
 * closes the jobs the company no longer lists. A full run crawls every enabled company with ONE VIRTUAL THREAD PER
 * SERVER: companies on the same server (a shared API like api.lever.co, or one Workday cluster) are crawled one after
 * another, so our IP never sends parallel streams to one server; different servers run at the same time, so a slow or
 * throttling company (Microsoft at 15 s per request) only delays the companies on its own server.
 *
 * Two scheduled loops (jobagent.crawl.schedule.*). The REGULAR loop crawls every company without its own timing, then lets
 * the model fill the new jobs' gaps; it runs with a FIXED DELAY (the next run starts `delay` after the previous one
 * finished), so runs never overlap. The SLOW loop belongs to the companies that have config.minCrawlHours (Microsoft,
 * Qualcomm: throttling sites that take minutes to hours): every `slow-check-delay` it starts each due company on its own
 * thread, so a slow or throttled company never holds up the regular loop. A slow company is due when its minCrawlHours
 * have passed since its last crawl, or, after a PARTIAL or FAILED crawl, `retry-after` after it ended (at most
 * `max-retries` times in a row). A slow company with config.fullCrawlFromHour does its FULL crawl once a day, from that
 * hour in India time (03:00: least throttling), and with config.peekEveryMinutes also gets a quick check for NEW jobs
 * (a PEEK: the newest part of its list only) between the full crawls. Per company only one crawl runs at a time (a FULL
 * and a PEEK, or a retry, never overlap), and a PEEK never closes jobs: only a FULL walk sees the whole list.
 */
@Service
public class CrawlRunService {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunService.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

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
    private final Set<String> slowRunning = ConcurrentHashMap.newKeySet();       // slow companies whose own thread is alive
    private final ReentrantLock gapFillLock = new ReentrantLock();                 // one company's gap fill at a time
    private final Set<String> crawling = ConcurrentHashMap.newKeySet();       // companies being crawled right now

    public CrawlRunService(CompanyRepository companies, CrawlService crawlService, CrawlRunRepository runs,
                           GapFillRunner gapFillRunner, CrawlScheduleProperties properties, ShutdownSignal shutdown) {
        this.companies = companies;
        this.crawlService = crawlService;
        this.runs = runs;
        this.gapFillRunner = gapFillRunner;
        this.properties = properties;
        this.shutdown = shutdown;
        log.info("Crawl schedule: {}", properties.enabled()
                ? "ON (regular companies every " + properties.delay() + " after the previous run ended; slow companies checked every "
                + properties.slowCheckDelay() + ", retry after " + properties.retryAfter() + " x" + properties.maxRetries()
                + "; gap fill " + (properties.gapFill() ? "on" : "off") + ")"
                : "OFF (companies are crawled only by POST /admin/crawl)");
    }

    /** The regular loop (when enabled): the whole crawl job for the companies without their own timing. */
    @Scheduled(fixedDelayString = "${jobagent.crawl.schedule.delay:PT30M}",
            initialDelayString = "${jobagent.crawl.schedule.initial-delay:PT1M}")
    void scheduledRun() {
        if (!properties.enabled() || shutdown.isStopping()) {
            return;
        }
        try {
            crawlJob("schedule", company -> !hasOwnLoop(company));
        } catch (RuntimeException e) {
            log.warn("Scheduled crawl skipped: {}", e.getMessage());
        }
    }

    /** The slow loop (when enabled): starts every due slow company on its own thread and returns at once. */
    @Scheduled(fixedDelayString = "${jobagent.crawl.schedule.slow-check-delay:PT5M}",
            initialDelayString = "${jobagent.crawl.schedule.initial-delay:PT1M}")
    void slowCompaniesTick() {
        if (!properties.enabled() || shutdown.isStopping()) {
            return;
        }
        try {
            startDueSlowCompanies();
        } catch (RuntimeException e) {
            log.warn("Slow companies check failed: {}", e.getMessage());
        }
    }

    /** True for a company with config.minCrawlHours: it has its own timing and is not part of the regular loop. */
    static boolean hasOwnLoop(Company company) {
        return company.config() != null && company.config().path("minCrawlHours").isNumber();
    }

    void startDueSlowCompanies() {
        Instant now = Instant.now();
        Map<Long, Instant> lastStarts = runs.lastCrawlStarts();
        Map<Long, Instant> lastPeeks = runs.lastPeekStarts();
        Map<Long, List<CrawlRunRepository.RunState>> states = runs.recentRunStates(properties.maxRetries() + 2);
        for (Company company : companies.findAll()) {
            if (!company.enabled() || !crawlService.isSupported(company) || !hasOwnLoop(company)
                    || crawling.contains(company.slug()) || slowRunning.contains(company.slug())) {
                continue;
            }
            List<CrawlRunRepository.RunState> recent = states.getOrDefault(company.id(), List.of());
            String kind = CrawlRun.FULL;
            String reason = dueReason(company, lastStarts.get(company.id()), recent, properties.retryAfter(),
                    properties.maxRetries(), now);
            if (reason == null && crawlService.supportsNewestCheck(company)) {
                reason = peekReason(company, lastStarts.get(company.id()), lastPeeks.get(company.id()), recent, now);
                kind = CrawlRun.PEEK;
            }
            if (reason != null && slowRunning.add(company.slug())) {
                log.info("{}: {} starts on its own thread ({})", company.slug(),
                        CrawlRun.PEEK.equals(kind) ? "quick check for new jobs" : "full crawl", reason);
                String runKind = kind;
                Thread.ofVirtual().name("slow-crawl-" + company.slug()).start(() -> runSlowCompany(company, runKind));
            }
        }
    }

    private void runSlowCompany(Company company, String kind) {
        try {
            RunOutcome outcome = crawlOne(company, "schedule", kind);
            CrawlResult result = outcome.result();
            if (properties.gapFill() && result != null && result.inserted() + result.updated() > 0 && !shutdown.isStopping()) {
                try (ShutdownSignal.Activity activity = shutdown.track()) {
                    gapFillLock.lock();
                    try {
                        gapFillRunner.fillCompany(company.id(), properties.gapFillModel(), properties.gapFillParallelism());
                    } finally {
                        gapFillLock.unlock();
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("{}: slow crawl failed: {}", company.slug(), e.toString());
        } finally {
            slowRunning.remove(company.slug());
        }
    }

    /** config.fullCrawlFromHour: the India-time hour from which the daily FULL crawl may start (null: not set). */
    static Integer fullCrawlFromHour(Company company) {
        JsonNode hour = company.config() == null ? null : company.config().path("fullCrawlFromHour");
        return hour != null && hour.isNumber() && hour.asInt() >= 0 && hour.asInt() <= 23 ? hour.asInt() : null;
    }

    /** The most recent moment at or before now that was hour:00 in India. */
    static Instant latestWindowStart(int hour, Instant now) {
        ZonedDateTime candidate = now.atZone(INDIA).toLocalDate().atTime(hour, 0).atZone(INDIA);
        if (candidate.toInstant().isAfter(now)) {
            candidate = candidate.minusDays(1);
        }
        return candidate.toInstant();
    }

    /** How many of the newest FULL crawls in a row ended PARTIAL or FAILED (recent = newest first). */
    static int failedInARow(List<CrawlRunRepository.RunState> recent) {
        int failed = 0;
        for (CrawlRunRepository.RunState state : recent) {
            if (state.status() != Status.PARTIAL && state.status() != Status.FAILED) {
                break;
            }
            failed++;
        }
        return failed;
    }

    /**
     * Why a slow company's FULL crawl is due now, or null when it is not. Due when it was never crawled; or its
     * minCrawlHours have passed since its last full crawl started and, if config.fullCrawlFromHour is set, today's window
     * (that hour in India) has opened and no full crawl has started since it opened (one per day); or its last crawls
     * ended PARTIAL / FAILED (throttled) and the newest ended at least retryAfter ago with fewer than maxRetries retries
     * so far (a retry ignores the window). recent = the company's newest FULL runs first; interrupted ones are not in it.
     */
    static String dueReason(Company company, Instant lastStart, List<CrawlRunRepository.RunState> recent,
                            Duration retryAfter, int maxRetries, Instant now) {
        if (lastStart == null) {
            return "never crawled";
        }
        if (tooSoon(company, lastStart, now) == null) {
            Integer hour = fullCrawlFromHour(company);
            if (hour == null) {
                return "minimum interval passed";
            }
            if (lastStart.isBefore(latestWindowStart(hour, now))) {
                return "daily window from " + String.format("%02d:00", hour) + " India time is open";
            }
        }
        int failed = failedInARow(recent);
        if (failed > 0 && failed <= maxRetries && !now.isBefore(recent.get(0).finishedAt().plus(retryAfter))) {
            return "retry " + failed + " of " + maxRetries + " after a " + recent.get(0).status();
        }
        return null;
    }

    /**
     * Why the quick check for new jobs is due now, or null: the company has config.peekEveryMinutes, a full crawl has
     * run (there is a base to compare with), the last full crawls did not fail in a row (the site is throttling us: leave
     * it alone), and the last quick check started at least that long ago.
     */
    static String peekReason(Company company, Instant lastFullStart, Instant lastPeekStart,
                             List<CrawlRunRepository.RunState> recentFull, Instant now) {
        JsonNode every = company.config() == null ? null : company.config().path("peekEveryMinutes");
        if (every == null || !every.isNumber() || every.asLong() <= 0 || lastFullStart == null
                || failedInARow(recentFull) > 0) {
            return null;
        }
        if (lastPeekStart != null && Duration.between(lastPeekStart, now).toMinutes() < every.asLong()) {
            return null;
        }
        return lastPeekStart == null ? "no quick check yet" : "every " + every.asLong() + " minutes";
    }

    /**
     * The whole crawl job, end to end: crawl every enabled company (each crawl saves its jobs, extracts their
     * requirements with the rules, checks its health and closes jobs that disappeared), then let the model fill the
     * gaps the rules left in jobs never asked before, and wait for that to finish.
     */
    public CrawlJobReport crawlJob(String trigger) {
        return crawlJob(trigger, company -> true);
    }

    /** As above, for the companies the filter accepts (the regular loop leaves out the slow ones). */
    public CrawlJobReport crawlJob(String trigger, Predicate<Company> only) {
        long start = System.nanoTime();
        Run run = runAll(trigger, only);
        List<RunOutcome> outcomes = run.outcomes();
        List<GapFillRunner.RunStatus> fills = new ArrayList<>(run.gapFills());
        String note = null;
        if (!properties.gapFill()) {
            note = "gap fill switched off (jobagent.crawl.schedule.gap-fill)";
        } else if (!shutdown.isStopping()) {
            // each company was filled right after its own crawl; this catch-all asks about whatever is still unasked
            // (a company whose fill failed, jobs from earlier runs)
            gapFillLock.lock();                              // never at the same time as a slow company's own fill
            try {
                fills.add(gapFillRunner.runAndWait(properties.gapFillModel(), null, properties.gapFillParallelism()));
            } catch (RuntimeException e) {
                note = "gap fill not run: " + e.getMessage();
                log.warn("Gap fill after the crawl: {}", e.getMessage());
            } finally {
                gapFillLock.unlock();
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
        return runAll(trigger, company -> true);
    }

    /** As above, for the companies the filter accepts. */
    public Run runAll(String trigger, Predicate<Company> only) {
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
                if (!company.enabled() || !crawlService.isSupported(company) || !only.test(company)) {
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
                    gapFillLock.lock();                                // not at the same time as a slow company's fill
                    try {
                        gapFills.add(gapFillRunner.fillCompany(company.id(), properties.gapFillModel(),
                                properties.gapFillParallelism()));
                    } finally {
                        gapFillLock.unlock();
                    }
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
        return crawlOne(company, trigger, CrawlRun.FULL);
    }

    /**
     * As above; kind PEEK is the quick check for new jobs (recorded as such, never closes jobs). A company has only ONE
     * crawl at a time, of either kind: the second is refused, so a full crawl and a quick check never run together.
     */
    public RunOutcome crawlOne(Company company, String trigger, String kind) {
        if (!crawling.add(company.slug())) {
            // a run is recorded only when it ends, so without this a long crawl (Microsoft: hours) would be started a second
            // time by the next schedule slot, double-hitting a site that already throttles us
            log.info("{}: already being crawled, not started again", company.slug());
            return new RunOutcome(company.slug(), crawlService.serverKey(company), null, Status.FAILED,
                    List.of("already being crawled"), 0, "already being crawled");
        }
        try (ShutdownSignal.Activity activity = shutdown.track()) {      // a shutdown waits until the run is recorded
            return CrawlRun.PEEK.equals(kind) ? peekTracked(company, trigger) : crawlTracked(company, trigger);
        } finally {
            crawling.remove(company.slug());
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
                    verdict.alerts(), null, result.elapsedMs(), CrawlRun.FULL));
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
                    partial.elapsedMs(), CrawlRun.FULL));
            log.warn("{}: HEALTH crawl PARTIAL: {}", company.slug(), e.getMessage());
            return new RunOutcome(company.slug(), server, partial, Status.PARTIAL, alerts, 0, e.getCause().toString());
        } catch (RuntimeException e) {
            List<String> alerts = List.of("crawl failed: " + e.getMessage());
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            runs.insert(new CrawlRun(company.id(), trigger, started, Instant.now(), Status.FAILED, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, alerts, e.toString(), elapsedMs, CrawlRun.FULL));
            log.warn("{}: HEALTH crawl failed: {}", company.slug(), e.toString());
            return new RunOutcome(company.slug(), server, null, Status.FAILED, alerts, 0, e.toString());
        }
    }

    /**
     * The quick check for new jobs of one company: fetches the newest part of its list, saves what is new or changed and
     * records a PEEK run. No health judgement and no closing: it does not see the older jobs.
     */
    private RunOutcome peekTracked(Company company, String trigger) {
        String server = crawlService.serverKey(company);
        Instant started = Instant.now();
        long startNanos = System.nanoTime();
        try {
            CrawlResult result = crawlService.crawlNewest(company);
            runs.insert(new CrawlRun(company.id(), trigger, result.seenAt(), Instant.now(), Status.OK, result.fetched(),
                    result.kept(), result.inserted(), result.updated(), result.unchanged(), result.unresolved(), 0,
                    result.detailsFetched(), result.detailsReused(), List.of(), null, result.elapsedMs(), CrawlRun.PEEK));
            return new RunOutcome(company.slug(), server, result, Status.OK, List.of(), 0, null);
        } catch (CrawlService.PartialCrawlException e) {
            CrawlResult partial = e.partial();
            List<String> alerts = List.of("partial: " + e.getMessage());
            runs.insert(new CrawlRun(company.id(), trigger, partial.seenAt(), Instant.now(), Status.PARTIAL, partial.fetched(),
                    partial.kept(), partial.inserted(), partial.updated(), partial.unchanged(), partial.unresolved(), 0,
                    partial.detailsFetched(), partial.detailsReused(), alerts, e.getCause().toString(), partial.elapsedMs(),
                    CrawlRun.PEEK));
            log.warn("{}: quick check PARTIAL: {}", company.slug(), e.getMessage());
            return new RunOutcome(company.slug(), server, partial, Status.PARTIAL, alerts, 0, e.getCause().toString());
        } catch (RuntimeException e) {
            List<String> alerts = List.of("quick check failed: " + e.getMessage());
            runs.insert(new CrawlRun(company.id(), trigger, started, Instant.now(), Status.FAILED, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    alerts, e.toString(), (System.nanoTime() - startNanos) / 1_000_000, CrawlRun.PEEK));
            log.warn("{}: quick check failed: {}", company.slug(), e.toString());
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