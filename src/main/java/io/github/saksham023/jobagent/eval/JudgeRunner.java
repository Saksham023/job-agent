package io.github.saksham023.jobagent.eval;

import io.github.saksham023.jobagent.eval.Judgment.Verdict;
import io.github.saksham023.jobagent.job.JobQueryRepository;
import io.github.saksham023.jobagent.job.JobQueryRepository.JobDetails;
import io.github.saksham023.jobagent.matching.MatchingProperties;
import io.github.saksham023.jobagent.requirements.JobFamily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Runs the judge over every open tech or sales-engineering job for one profile, in the background, writing one
 * line per job to eval/runs/&lt;run&gt;/judgments.jsonl as soon as it is judged. A run is named
 * &lt;date&gt;-&lt;model&gt;-&lt;profile&gt;; starting the same run again skips the jobs that already have a
 * verdict (failed ones are retried). Jobs are judged in a fixed shuffled order, so `limit=10` is a varied sample
 * of companies rather than the first ten ids. One run at a time.
 */
@Service
public class JudgeRunner {

    private static final Logger log = LoggerFactory.getLogger(JudgeRunner.class);

    static final int MAX_PARALLELISM = 4;

    /** If this many calls fail before any succeeds, the problem is the setup (login, CLI), not the jobs: stop. */
    static final int FAILURES_BEFORE_GIVING_UP = 3;

    private static final String SELECT_JOB_IDS = """
            SELECT j.id
            FROM jobs j
            JOIN job_requirements r ON r.job_id = j.id
            WHERE j.closed_at IS NULL
              AND j.country_codes @> ARRAY[CAST(:country AS text)]
              AND (r.family = ANY(:families) OR r.secondary_families && CAST(:families AS text[]))
            ORDER BY j.id
            """;

    /** A snapshot of a run, for the status endpoint. */
    public record RunStatus(String runId, String profile, String model, String rubric, int toJudge, int judged,
                            int failed, Map<Verdict, Integer> verdicts, long promptTokens, long completionTokens,
                            double costUsd, boolean running, Instant startedAt, Instant finishedAt, String lastError) {
    }

    private final JobJudge judge;
    private final JobQueryRepository jobs;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final EvalProperties eval;
    private final MatchingProperties matching;
    private final AtomicReference<Run> current = new AtomicReference<>();

    public JudgeRunner(JobJudge judge, JobQueryRepository jobs, JdbcClient jdbc, JsonMapper jsonMapper,
                       EvalProperties eval, MatchingProperties matching) {
        this.judge = judge;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.eval = eval;
        this.matching = matching;
    }

    /**
     * Starts a run in the background and returns at once.
     *
     * @param limit       judge at most this many jobs in total (counting earlier lines of the same run), or null for all
     * @param parallelism how many calls at the same time, 1 to MAX_PARALLELISM
     */
    public RunStatus start(String profileId, String model, Integer limit, int parallelism) {
        if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
            throw new IllegalArgumentException("parallelism must be between 1 and " + MAX_PARALLELISM);
        }
        Run running = current.get();
        if (running != null && running.running) {
            throw new IllegalStateException("Run " + running.runId + " is still going; wait for it to finish");
        }
        JudgeProfile profile = profile(profileId);
        Rubric rubric = Rubric.load(eval.path("judge-rubric.md"));
        String runId = LocalDate.now() + "-" + model.replaceAll("[^A-Za-z0-9.-]", "_") + "-" + profile.id();
        Path file = eval.path("runs", runId, "judgments.jsonl");

        List<JudgmentLine> earlier = readLines(file);
        if (earlier.stream().anyMatch(l -> !l.rubric().equals(rubric.version()))) {
            throw new IllegalStateException(runId + " was judged with another rubric version; move its folder away first");
        }
        Set<Long> done = earlier.stream().filter(JudgmentLine::succeeded).map(JudgmentLine::jobId).collect(Collectors.toSet());
        List<Long> order = jobOrder();
        if (limit != null) {
            order = order.subList(0, Math.min(limit, order.size()));
        }
        List<Long> todo = order.stream().filter(id -> !done.contains(id)).toList();

        Run run = new Run(runId, profile, model, rubric, file, todo.size());
        current.set(run);
        Thread.ofVirtual().name("judge-" + runId).start(() -> run.execute(todo, parallelism));
        log.info("Judge run {} started: {} jobs to judge ({} already done), parallelism {}",
                runId, todo.size(), done.size(), parallelism);
        return run.status();
    }

    public Optional<RunStatus> status() {
        return Optional.ofNullable(current.get()).map(Run::status);
    }

    /** All judged lines of a run (for the report); empty when the run does not exist. */
    public List<JudgmentLine> lines(String runId) {
        return readLines(eval.path("runs", runId, "judgments.jsonl"));
    }

    // ---------------------------------------------------------------- helpers

    private JudgeProfile profile(String id) {
        Path file = eval.path("judge-profiles.json");
        try {
            List<JudgeProfile> profiles = jsonMapper.readValue(Files.readString(file), new TypeReference<>() {});
            return profiles.stream().filter(p -> p.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No profile '" + id + "' in " + file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Open tech + sales-engineering jobs in the matching country, in a fixed shuffled order. */
    private List<Long> jobOrder() {
        String[] families = Arrays.stream(JobFamily.values())
                .filter(f -> f.isTech() || f == JobFamily.SALES_ENGINEERING)
                .map(Enum::name)
                .toArray(String[]::new);
        List<Long> ids = new ArrayList<>(jdbc.sql(SELECT_JOB_IDS)
                .param("country", matching.country())
                .param("families", families)
                .query(Long.class)
                .list());
        Collections.shuffle(ids, new Random(42));
        return ids;
    }

    private List<JudgmentLine> readLines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .map(line -> jsonMapper.readValue(line, JudgmentLine.class))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    // ---------------------------------------------------------------- one run

    /** The mutable state of a run; counters are atomic because several virtual threads update them. */
    private final class Run {

        final String runId;
        final JudgeProfile profile;
        final String model;
        final Rubric rubric;
        final Path file;
        final int toJudge;
        final Instant startedAt = Instant.now();
        final AtomicInteger judged = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final Map<Verdict, AtomicInteger> verdicts = new EnumMap<>(Verdict.class);
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();
        final DoubleAdder costUsd = new DoubleAdder();
        final ReentrantLock fileLock = new ReentrantLock();
        volatile boolean running = true;
        volatile Instant finishedAt;
        volatile String lastError;

        Run(String runId, JudgeProfile profile, String model, Rubric rubric, Path file, int toJudge) {
            this.runId = runId;
            this.profile = profile;
            this.model = model;
            this.rubric = rubric;
            this.file = file;
            this.toJudge = toJudge;
            for (Verdict v : Verdict.values()) {
                verdicts.put(v, new AtomicInteger());
            }
        }

        void execute(List<Long> todo, int parallelism) {
            Semaphore slots = new Semaphore(parallelism);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (long jobId : todo) {
                    if (failed.get() >= FAILURES_BEFORE_GIVING_UP && judged.get() == 0) {
                        lastError = "Stopped: the first " + failed.get() + " calls failed. Last error: " + lastError;
                        break;
                    }
                    slots.acquireUninterruptibly();
                    executor.submit(() -> {
                        try {
                            judgeOne(jobId);
                        } finally {
                            slots.release();
                        }
                    });
                }
            } finally {
                running = false;
                finishedAt = Instant.now();
                log.info("Judge run {} finished: {} judged, {} failed, verdicts {}, {} prompt + {} completion tokens, ${}",
                        runId, judged.get(), failed.get(), verdicts, promptTokens.get(), completionTokens.get(),
                        String.format("%.2f", costUsd.sum()));
            }
        }

        private void judgeOne(long jobId) {
            long start = System.currentTimeMillis();
            Optional<JobDetails> details = jobs.findDetails(jobId);
            if (details.isEmpty()) {
                return;                                                       // deleted since the run started
            }
            JobDetails job = details.get();
            JudgmentLine line;
            try {
                JobJudge.Result result = judge.judge(rubric, profile, job, model);
                Judgment judgment = result.judgment();
                line = JudgmentLine.of(jobId, job.company(), job.title(), profile.id(), model, rubric.version(),
                        result, System.currentTimeMillis() - start);
                verdicts.get(judgment.verdict()).incrementAndGet();
                promptTokens.addAndGet(result.promptTokens());
                completionTokens.addAndGet(result.completionTokens());
                costUsd.add(result.costUsd());
                int n = judged.incrementAndGet();
                log.info("[{}/{}] {} · {} -> {} (role {}, experience {}, stack {}) {} ms, {}+{} tokens, ${}", n, toJudge,
                        job.company(), job.title(), judgment.verdict(), judgment.roleFit(), judgment.experienceFit(),
                        judgment.stackFit(), line.millis(), result.promptTokens(), result.completionTokens(),
                        String.format("%.3f", result.costUsd()));
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                failed.incrementAndGet();
                line = JudgmentLine.failed(jobId, job.company(), job.title(), profile.id(), model, rubric.version(),
                        e.getMessage(), System.currentTimeMillis() - start);
                log.warn("Judge failed for job {} ({}): {}", jobId, job.title(), e.getMessage());
            }
            append(line);
        }

        private void append(JudgmentLine line) {
            fileLock.lock();
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, jsonMapper.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write " + file, e);
            } finally {
                fileLock.unlock();
            }
        }

        RunStatus status() {
            Map<Verdict, Integer> counts = new EnumMap<>(Verdict.class);
            verdicts.forEach((v, n) -> counts.put(v, n.get()));
            return new RunStatus(runId, profile.id(), model, rubric.version(), toJudge, judged.get(), failed.get(),
                    counts, promptTokens.get(), completionTokens.get(), Math.round(costUsd.sum() * 1000) / 1000.0,
                    running, startedAt, finishedAt, lastError);
        }
    }
}