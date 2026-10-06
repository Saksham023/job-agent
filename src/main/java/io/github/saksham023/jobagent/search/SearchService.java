package io.github.saksham023.jobagent.search;

import io.github.saksham023.jobagent.eval.EvalProperties;
import io.github.saksham023.jobagent.eval.JobJudge;
import io.github.saksham023.jobagent.eval.JudgeProfile;
import io.github.saksham023.jobagent.eval.Judgment.Verdict;
import io.github.saksham023.jobagent.eval.Rubric;
import io.github.saksham023.jobagent.job.JobQueryRepository;
import io.github.saksham023.jobagent.job.JobQueryRepository.JobDetails;
import io.github.saksham023.jobagent.matching.ExperienceWindow;
import io.github.saksham023.jobagent.matching.MatchScorer.Match;
import io.github.saksham023.jobagent.matching.MatchService;
import io.github.saksham023.jobagent.matching.MatchService.MatchResponse;
import io.github.saksham023.jobagent.matching.Profile;
import io.github.saksham023.jobagent.profile.ProfileService;
import io.github.saksham023.jobagent.profile.ProfileService.Resolved;
import io.github.saksham023.jobagent.search.SearchRepository.JobTraits;
import io.github.saksham023.jobagent.search.SearchRepository.Ranked;
import io.github.saksham023.jobagent.search.SearchRepository.SearchRow;
import io.github.saksham023.jobagent.search.SearchRepository.Status;
import io.github.saksham023.jobagent.search.SearchRepository.StoredJudgment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Search with the model judge (Milestone 8). The rules shortlist and rank every eligible job (MatchService); the
 * judge then reads the jobs in that order and says APPLY / MAYBE / NO for this candidate:
 * - start: judges the top `firstBatch` candidates in parallel (only those without a stored verdict: a repeated
 *   search answers at once), answers with the APPLY ones, and starts a background worker;
 * - the worker keeps judging down the list until `readyTarget` APPLY jobs are ready (judged, not shown yet), then
 *   pauses; it stops for good when the list ends or after `stopAfterNos` NO verdicts in a row;
 * - more: hands out ready jobs at once (APPLY first; MAYBE only when no APPLY can come any more) and wakes the
 *   worker to refill.
 * Likely NO jobs are not dropped but moved to a LOW-PRIORITY tier at the end of the list (lowPriorityReason): they
 * are judged only after the main tier is done, so a normal session never pays for them.
 * Every search is for a saved profile (ProfileService): the same profile id gives the same candidate, so its stored
 * verdicts are reused; a profile created by this search is announced in the first answer's note.
 * The state lives in the database (searches, judgments): the only memory here is which searches have a worker
 * running and which calls failed, so after a restart the next `more` simply resumes. Verdicts are stored per
 * (profile, job, rubric, job content) and reused by any later search with the same profile.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** A worker stops after this many failed judge calls in a row (setup problem: login, CLI, quota). */
    static final int FAILURES_BEFORE_PAUSING = 3;

    /**
     * Level words in a title, with the years they suggest; used ONLY for jobs whose posting states no years, and
     * only to decide the tier (never to drop a job). Checked in this order, the first match counts.
     */
    private record SeniorityWord(Pattern words, int years) {
    }

    private static final List<SeniorityWord> SENIORITY_WORDS = List.of(
            new SeniorityWord(Pattern.compile("(?i)\\b(?:director|head|vp|vice president|chief)\\b"), 12),
            new SeniorityWord(Pattern.compile("(?i)\\b(?:staff|principal|architect|manager|distinguished)\\b"), 8),
            new SeniorityWord(Pattern.compile("(?i)\\blead\\b"), 5));

    /** One job in an answer: the posting's key facts, its rule score and the judge's verdict. */
    public record JobResult(long jobId, String company, String title, String url, List<String> cities, boolean remote,
                            Instant postedAt, Integer minYears, Integer maxYears, int ruleScore, Verdict verdict,
                            String reason, String roleFit, String experienceFit, String stackFit) {
    }

    /**
     * One answer.
     *
     * @param readyApply  APPLY jobs judged and waiting (more can be returned at once)
     * @param notJudgedYet candidates without a verdict; the worker judges only until readyTarget APPLY are ready,
     *                     so most of them may never be judged
     * @param judging     a background worker is judging right now
     * @param finished    nothing more will be judged (list done or the NO streak was reached)
     * @param note        a sentence for the user about what happens next
     * @param eligible    first answer only: jobs that passed the filters (null later)
     * @param profileId   the saved profile this search is for: pass it to match_jobs next time instead of the resume
     */
    public record SearchPage(UUID searchId, String profileId, List<JobResult> jobs, int readyApply, int readyMaybe, int notJudgedYet,
                             boolean judging, boolean finished, String note, Integer eligible,
                             ExperienceWindow experienceWindow, List<String> unknownSkills,
                             List<String> unknownLocations) {
    }

    /** A plain list for applying: company, title, link. */
    public record ExportRow(String company, String title, String url, Verdict verdict) {
    }

    /** What is stored in searches.profile: the request, and the candidate exactly as the judge reads it. */
    record StoredProfile(Profile request, String postedSince, JudgeProfile judge) {
    }

    private final MatchService matchService;
    private final SearchRepository repository;
    private final JobQueryRepository jobs;
    private final JobJudge judge;
    private final EvalProperties eval;
    private final SearchProperties settings;
    private final ProfileService profiles;
    private final JsonMapper jsonMapper;

    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<Long>> failed = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> failuresInARow = new ConcurrentHashMap<>();

    public SearchService(MatchService matchService, SearchRepository repository, JobQueryRepository jobs,
                         JobJudge judge, EvalProperties eval, SearchProperties settings, ProfileService profiles,
                         JsonMapper jsonMapper) {
        this.matchService = matchService;
        this.repository = repository;
        this.jobs = jobs;
        this.judge = judge;
        this.eval = eval;
        this.settings = settings;
        this.profiles = profiles;
        this.jsonMapper = jsonMapper;
    }

    // ---------------------------------------------------------------- the three operations

    /**
     * @param resolved    the saved profile and this search's preferences (ProfileService.resolve)
     * @param postedSince only jobs posted since then, or null
     * @param pageSize    jobs in the answer, or null for the default
     */
    public SearchPage start(Resolved resolved, Instant postedSince, Integer pageSize) {
        Profile profile = resolved.search();
        String wants = resolved.wants();
        String profileId = resolved.profile().id();
        Rubric rubric = rubric();
        MatchResponse match = matchService.match(profile, Integer.MAX_VALUE, postedSince);
        JudgeProfile candidate = judgeProfile(profile, match, wants);
        String profileHash = profileHash(candidate);
        String stored = jsonMapper.writeValueAsString(
                new StoredProfile(profile, postedSince == null ? null : postedSince.toString(), candidate));

        // main tier first, then the likely-NO jobs; each part keeps the rule order
        Map<Long, JobTraits> traits = repository.traits(match.matches().stream().map(Match::jobId).toList());
        List<Match> main = new ArrayList<>();
        List<Match> low = new ArrayList<>();
        for (Match job : match.matches()) {
            String why = lowPriorityReason(job, traits.get(job.jobId()), match.experienceWindow(), match.profile().skills());
            (why == null ? main : low).add(job);
        }
        List<Match> ordered = new ArrayList<>(main);
        ordered.addAll(low);
        UUID id = repository.create(stored, profileHash, rubric.version(),
                ordered.stream().map(Match::jobId).toList(), ordered.stream().map(Match::score).toList(),
                low.isEmpty() ? null : main.size() + 1, profileId);
        profiles.recordSearch(profileId, resolved.preferences(), profileHash);
        SearchRow search = repository.find(id).orElseThrow();
        log.info("Search {} for profile {}{}: {} eligible jobs ({} low priority), judging the first {}", id, profileId,
                resolved.created() ? " (new)" : "", match.eligible(), low.size(), settings.firstBatch());

        judgeTop(search, candidate, rubric, settings.firstBatch());
        SearchPage page = page(search, settings.pageSize(pageSize));
        String note = resolved.created() ? newProfileNote(profileId) + " " + page.note() : page.note();
        return new SearchPage(page.searchId(), page.profileId(), page.jobs(), page.readyApply(), page.readyMaybe(),
                page.notJudgedYet(), page.judging(), page.finished(), note, match.eligible(), match.experienceWindow(),
                match.unknownSkills(), match.unknownLocations());
    }

    /** The next jobs of a search; count null = the default page size. */
    public SearchPage more(UUID searchId, Integer count) {
        SearchRow search = repository.find(searchId)
                .orElseThrow(() -> new IllegalArgumentException("No search " + searchId + "; start a new search"));
        return page(search, settings.pageSize(count));
    }

    /** Every APPLY job judged so far (and MAYBE ones when asked), shown or not, in rank order. */
    public List<ExportRow> export(UUID searchId, boolean includeMaybe) {
        repository.find(searchId)
                .orElseThrow(() -> new IllegalArgumentException("No search " + searchId + "; start a new search"));
        List<ExportRow> rows = new ArrayList<>();
        List<Verdict> verdicts = includeMaybe ? List.of(Verdict.APPLY, Verdict.MAYBE) : List.of(Verdict.APPLY);
        for (Verdict verdict : verdicts) {
            for (Ranked ranked : repository.withVerdict(searchId, verdict)) {
                jobs.findDetails(ranked.jobId()).ifPresent(job ->
                        rows.add(new ExportRow(job.company(), job.title(), job.url(), verdict)));
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- answers

    /** Takes up to `count` ready jobs (APPLY first; MAYBE only once no APPLY can come), marks them shown, refills. */
    private SearchPage page(SearchRow search, int count) {
        List<Ranked> picked = new ArrayList<>(repository.ready(search.id(), Verdict.APPLY, count));
        if (picked.isEmpty() && !canJudgeMore(search)) {
            picked.addAll(repository.ready(search.id(), Verdict.MAYBE, count));
        }
        if (!picked.isEmpty()) {
            repository.markShown(search.id(), picked.stream().map(Ranked::jobId).toList());
        }
        ensureWorker(search);

        List<JobResult> results = picked.stream().map(r -> result(search, r)).toList();
        Status after = status(search);
        boolean judging = running.contains(search.id());
        boolean finished = !judging && !canJudgeMore(search);
        return new SearchPage(search.id(), search.profileId(), results, after.readyApply(), after.readyMaybe(), after.unjudged(),
                judging, finished, note(results.size(), count, after, judging, finished), null, null, null, null);
    }

    private JobResult result(SearchRow search, Ranked ranked) {
        JobDetails job = jobs.findDetails(ranked.jobId()).orElseThrow();
        StoredJudgment verdict = repository.judgment(search.profileHash(), search.rubricVersion(), ranked.jobId())
                .orElseThrow();
        return new JobResult(job.jobId(), job.company(), job.title(), job.url(), job.cities(), job.remote(),
                job.postedAt(), job.minYears(), job.maxYears(), ranked.score(), verdict.verdict(), verdict.reason(),
                verdict.roleFit(), verdict.experienceFit(), verdict.stackFit());
    }

    static String newProfileNote(String profileId) {
        return "Saved as profile " + profileId + ": next time pass profileId " + profileId
                + " instead of the resume to search with the same facts.";
    }

    static String note(int returned, int asked, Status status, boolean judging, boolean finished) {
        if (returned >= asked) {
            return status.readyApply() > 0 ? status.readyApply() + " more APPLY jobs are ready." : "More jobs are being judged.";
        }
        if (judging) {
            return "The next candidates are being judged in the background; ask again in about a minute for more.";
        }
        return finished ? "That is everything that fits for this search." : "Ask again for more.";
    }

    // ---------------------------------------------------------------- the background worker

    /** Starts a worker for the search unless one runs already or nothing needs judging. */
    private void ensureWorker(SearchRow search) {
        if (needsMore(search) && running.add(search.id())) {
            Thread.ofVirtual().name("search-" + search.id()).start(() -> work(search));
        }
    }

    private void work(SearchRow search) {
        try {
            Rubric rubric = rubric();
            if (!rubric.version().equals(search.rubricVersion())) {
                log.warn("Search {}: rubric changed to {}; not judging further (start a new search)", search.id(), rubric.version());
                return;
            }
            JudgeProfile candidate = jsonMapper.readValue(search.profileJson(), StoredProfile.class).judge();
            while (needsMore(search)) {
                judgeNext(search, candidate, rubric, settings.parallelism());
            }
        } catch (RuntimeException e) {
            log.warn("Search {}: worker stopped: {}", search.id(), e.getMessage());
        } finally {
            running.remove(search.id());
            if (needsMore(search) && failuresInARow(search.id()).get() < FAILURES_BEFORE_PAUSING) {
                ensureWorker(search);                          // a request came in while we were finishing
            }
        }
    }

    /** Fewer than readyTarget APPLY are ready, and more can be judged. */
    private boolean needsMore(SearchRow search) {
        Status status = status(search);
        return status.readyApply() < settings.readyTarget() && canJudgeMore(search)
                && failuresInARow(search.id()).get() < FAILURES_BEFORE_PAUSING;
    }

    /** Some candidate can still be judged: in the main tier, or (once that is done) in the low-priority tier. */
    private boolean canJudgeMore(SearchRow search) {
        return !repository.nextUnjudged(search.id(), 1, settings.stopAfterNos(), failedHere(search)).isEmpty();
    }

    private Status status(SearchRow search) {
        return repository.status(search.id(), settings.stopAfterNos(), failedHere(search));
    }

    private Set<Long> failedHere(SearchRow search) {
        return failed.computeIfAbsent(search.id(), k -> ConcurrentHashMap.newKeySet());
    }

    /** The first answer's batch: the top `count` positions only; already judged ones cost nothing. */
    private void judgeTop(SearchRow search, JudgeProfile candidate, Rubric rubric, int count) {
        judgeAll(search, candidate, rubric,
                repository.nextUnjudged(search.id(), count, settings.stopAfterNos(), failedHere(search), count));
    }

    /** The worker's step: the next `count` candidates without a verdict, wherever they are in the list. */
    private void judgeNext(SearchRow search, JudgeProfile candidate, Rubric rubric, int count) {
        judgeAll(search, candidate, rubric,
                repository.nextUnjudged(search.id(), count, settings.stopAfterNos(), failedHere(search)));
    }

    /** Judges the given candidates, `parallelism` at a time, and waits for all of them. */
    private void judgeAll(SearchRow search, JudgeProfile candidate, Rubric rubric, List<Long> next) {
        Semaphore slots = new Semaphore(settings.parallelism());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long jobId : next) {
                slots.acquireUninterruptibly();
                executor.submit(() -> {
                    try {
                        judgeOne(search, candidate, rubric, jobId);
                    } finally {
                        slots.release();
                    }
                });
            }
        }
    }

    private void judgeOne(SearchRow search, JudgeProfile candidate, Rubric rubric, long jobId) {
        long start = System.currentTimeMillis();
        try {
            JobDetails job = jobs.findDetails(jobId).orElseThrow();
            JobJudge.Result result = judge.judge(rubric, candidate, job, settings.model());
            repository.saveJudgment(search.profileHash(), rubric.version(), jobId, settings.model(), result.judgment(),
                    result.costUsd(), System.currentTimeMillis() - start);
            failuresInARow(search.id()).set(0);
            log.info("Search {}: {} · {} -> {}", search.id(), job.company(), job.title(), result.judgment().verdict());
        } catch (RuntimeException e) {
            failedHere(search).add(jobId);
            failuresInARow(search.id()).incrementAndGet();
            log.warn("Search {}: judging job {} failed: {}", search.id(), jobId, e.getMessage());
        }
    }

    private AtomicInteger failuresInARow(UUID id) {
        return failuresInARow.computeIfAbsent(id, k -> new AtomicInteger());
    }

    private Rubric rubric() {
        return Rubric.load(eval.path("judge-rubric.md"));
    }

    // ---------------------------------------------------------------- tiers

    /**
     * Why a job goes to the low-priority tier, or null for the main tier. Only signs of a likely NO that the hard
     * filters cannot use (they never drop a job on a guess):
     * - the posting states no years, and a level word in the title suggests more than the candidate's window;
     * - embedded / firmware work and the candidate lists neither C nor C++;
     * - the job's main languages are known and the candidate has none of them.
     */
    static String lowPriorityReason(Match job, JobTraits traits, ExperienceWindow window, Set<String> candidateSkills) {
        if (job.minYears() == null && window != null && window.to() != null) {
            Integer estimate = seniorityEstimate(job.title());
            if (estimate != null && estimate > window.to()) {
                return "senior title, no years stated";
            }
        }
        if (traits == null) {
            return null;
        }
        if ("EMBEDDED".equals(traits.specialization()) && !candidateSkills.contains("C") && !candidateSkills.contains("C++")) {
            return "embedded work, no C or C++";
        }
        if (!traits.primaryLanguages().isEmpty() && traits.primaryLanguages().stream().noneMatch(candidateSkills::contains)) {
            return "main languages not the candidate's";
        }
        return null;
    }

    /** The years a level word in the title suggests ("Lead" 5, "Principal" 8, "Director" 12), or null. */
    static Integer seniorityEstimate(String title) {
        if (title == null) {
            return null;
        }
        return SENIORITY_WORDS.stream().filter(w -> w.words().matcher(title).find())
                .map(SeniorityWord::years).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- the candidate

    /**
     * The candidate as the judge reads it: decimal years, the canonical main languages and other skills (as
     * MatchService understood them), and what they want. Nothing else (no name, no location).
     */
    static JudgeProfile judgeProfile(Profile profile, MatchResponse match, String wants) {
        List<String> languages = new ArrayList<>(new TreeSet<>(match.profile().languages()));
        List<String> others = new ArrayList<>(new TreeSet<>(match.profile().skills()));
        others.removeAll(languages);
        String want = wants == null || wants.isBlank()
                ? "Not stated: judge the role type from the skills." : wants.strip();
        return new JudgeProfile("search", null, profile.yearsOfExperience(), languages, others, want);
    }

    /** SHA-256 of the judge-relevant fields in a fixed order, so the same candidate always gets the same hash. */
    static String profileHash(JudgeProfile candidate) {
        String text = String.join("|",
                candidate.years() == null ? "-" : String.format(Locale.ROOT, "%.1f", candidate.years()),
                String.join(",", new TreeSet<>(candidate.mainLanguages())),
                String.join(",", new TreeSet<>(candidate.otherSkills())),
                candidate.wants().strip().toLowerCase(Locale.ROOT));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}