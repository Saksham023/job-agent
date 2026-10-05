package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.matching.MatchingProperties;
import io.github.saksham023.jobagent.requirements.GapFillRepository.GapJob;
import io.github.saksham023.jobagent.requirements.GapFiller.Fill;
import io.github.saksham023.jobagent.requirements.GapFiller.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Fills extraction gaps with a model, in the background: every open tech or sales-engineering job (or
 * UNCLASSIFIED one) whose experience is NONE / LOW, whose family is UNCLASSIFIED, or that has no main language,
 * and that has no stored answer for its current content and the current prompt version. One call per job. Each
 * answer is stored and applied as soon as it arrives, so a stopped run loses nothing and the next run only asks
 * for the rest. Jobs go in a fixed shuffled order, so `limit=15` is a varied sample. One run at a time.
 */
@Service
public class GapFillRunner {

    private static final Logger log = LoggerFactory.getLogger(GapFillRunner.class);

    static final int MAX_PARALLELISM = 4;

    /** If this many calls fail before any succeeds, the problem is the setup (login, CLI), not the jobs: stop. */
    static final int FAILURES_BEFORE_GIVING_UP = 3;

    /** A snapshot of a run, for the status endpoint. Counts are fields filled, not jobs. */
    public record RunStatus(String model, String promptVersion, int toFill, int done, int failed, int yearsFilled,
                            int familiesFilled, int languagesFilled, int rejected, double costUsd, boolean running,
                            Instant startedAt, Instant finishedAt, String lastError) {
    }

    private final GapFiller filler;
    private final GapFillRepository repository;
    private final MatchingProperties matching;
    private final AtomicReference<Run> current = new AtomicReference<>();

    public GapFillRunner(GapFiller filler, GapFillRepository repository, MatchingProperties matching) {
        this.filler = filler;
        this.repository = repository;
        this.matching = matching;
    }

    /**
     * Starts a run in the background and returns at once.
     *
     * @param limit       ask about at most this many jobs, or null for all
     * @param parallelism how many calls at the same time, 1 to MAX_PARALLELISM
     */
    public RunStatus start(String model, Integer limit, int parallelism) {
        if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
            throw new IllegalArgumentException("parallelism must be between 1 and " + MAX_PARALLELISM);
        }
        Run running = current.get();
        if (running != null && running.running) {
            throw new IllegalStateException("A gap fill run is still going; wait for it to finish");
        }
        GapFillPrompt prompt = GapFillPrompt.load();
        List<GapJob> todo = new ArrayList<>(repository.findJobsWithGaps(prompt.version(), matching.country(), scope()));
        Collections.shuffle(todo, new Random(42));
        if (limit != null) {
            todo = todo.subList(0, Math.min(limit, todo.size()));
        }

        Run run = new Run(model, prompt, todo.size());
        current.set(run);
        List<GapJob> jobs = List.copyOf(todo);
        Thread.ofVirtual().name("gap-fill").start(() -> run.execute(jobs, parallelism));
        log.info("Gap fill started: {} jobs, model {}, prompt {}, parallelism {}", jobs.size(), model, prompt.version(), parallelism);
        return run.status();
    }

    public Optional<RunStatus> status() {
        return Optional.ofNullable(current.get()).map(Run::status);
    }

    /** Tech families and sales engineering, as in the judge; UNCLASSIFIED is added by the query itself. */
    private static String[] scope() {
        return Arrays.stream(JobFamily.values())
                .filter(f -> f.isTech() || f == JobFamily.SALES_ENGINEERING)
                .map(Enum::name)
                .toArray(String[]::new);
    }

    /** The mutable state of a run; counters are atomic because several virtual threads update them. */
    private final class Run {

        final String model;
        final GapFillPrompt prompt;
        final int toFill;
        final Instant startedAt = Instant.now();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger years = new AtomicInteger();
        final AtomicInteger families = new AtomicInteger();
        final AtomicInteger languages = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final DoubleAdder costUsd = new DoubleAdder();
        volatile boolean running = true;
        volatile Instant finishedAt;
        volatile String lastError;

        Run(String model, GapFillPrompt prompt, int toFill) {
            this.model = model;
            this.prompt = prompt;
            this.toFill = toFill;
        }

        void execute(List<GapJob> todo, int parallelism) {
            Semaphore slots = new Semaphore(parallelism);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (GapJob job : todo) {
                    if (failed.get() >= FAILURES_BEFORE_GIVING_UP && done.get() == 0) {
                        lastError = "Stopped: the first " + failed.get() + " calls failed. Last error: " + lastError;
                        break;
                    }
                    slots.acquireUninterruptibly();
                    executor.submit(() -> {
                        try {
                            fillOne(job);
                        } finally {
                            slots.release();
                        }
                    });
                }
            } finally {
                running = false;
                finishedAt = Instant.now();
                log.info("Gap fill finished: {} done, {} failed; filled years {}, families {}, languages {}; "
                                + "{} answers rejected; ${}", done.get(), failed.get(), years.get(), families.get(),
                        languages.get(), rejected.get(), String.format("%.2f", costUsd.sum()));
            }
        }

        private void fillOne(GapJob job) {
            long start = System.currentTimeMillis();
            try {
                Result result = filler.fill(prompt, job.job(), job.gaps(), model);
                long millis = System.currentTimeMillis() - start;
                repository.save(job, prompt.version(), model, result, millis);
                Fill fill = result.accepted();
                repository.apply(job.job().jobId(), fill, model);

                costUsd.add(result.costUsd());
                rejected.addAndGet(result.rejected().size());
                if (fill.hasYears()) {
                    years.incrementAndGet();
                }
                if (fill.hasFamily()) {
                    families.incrementAndGet();
                }
                if (fill.hasLanguages()) {
                    languages.incrementAndGet();
                }
                int n = done.incrementAndGet();
                log.info("[{}/{}] {} · {} | {}{} ({} ms)", n, toFill, job.job().companyName(), job.job().title(),
                        outcome(job.gaps(), fill), result.rejected().isEmpty() ? "" : " | rejected " + result.rejected(),
                        millis);
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                failed.incrementAndGet();
                log.warn("Gap fill failed for job {} ({}): {}", job.job().jobId(), job.job().title(), e.getMessage());
            }
        }

        /** Per gap asked: what came back, e.g. "years: not stated, languages: [Java]". Other fields were known. */
        private static String outcome(GapFiller.Gaps gaps, Fill fill) {
            List<String> parts = new ArrayList<>();
            if (gaps.years()) {
                parts.add("years: " + (fill.hasYears()
                        ? fill.minYears() + (fill.maxYears() == null ? "+" : "-" + fill.maxYears()) : "not stated"));
            }
            if (gaps.family()) {
                parts.add("family: " + (fill.hasFamily() ? fill.family() : "still unclear"));
            }
            if (gaps.languages()) {
                parts.add("languages: " + (fill.hasLanguages() ? fill.languages() : "none named"));
            }
            return String.join(", ", parts);
        }

        RunStatus status() {
            return new RunStatus(model, prompt.version(), toFill, done.get(), failed.get(), years.get(),
                    families.get(), languages.get(), rejected.get(), Math.round(costUsd.sum() * 100) / 100.0,
                    running, startedAt, finishedAt, lastError);
        }
    }
}
