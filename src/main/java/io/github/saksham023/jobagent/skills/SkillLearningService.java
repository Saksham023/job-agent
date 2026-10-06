package io.github.saksham023.jobagent.skills;

import io.github.saksham023.jobagent.geo.Gazetteer;
import io.github.saksham023.jobagent.requirements.DescriptionSections;
import io.github.saksham023.jobagent.requirements.RequirementsService;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Category;
import io.github.saksham023.jobagent.skills.SkillLearningRepository.JobText;
import io.github.saksham023.jobagent.skills.SkillLearningRepository.StoredCandidate;
import io.github.saksham023.jobagent.skills.SkillMiner.Candidate;
import io.github.saksham023.jobagent.skills.SkillReviewer.BatchResult;
import io.github.saksham023.jobagent.skills.SkillReviewer.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The self-learning skill dictionary. One run does every step in order (learn()):
 * 1. mine: code finds candidate words in skill lists of all open jobs (free);
 * 2. review: Opus decides what each new word seen at minCompanies+ companies is (paid, capped by maxTermsPerRun);
 * 3. checks: code verifies every answer before it may change anything (in SkillReviewer);
 * 4. apply: accepted spellings go live on top of skills.csv; only jobs mentioning them are re-extracted.
 * It runs on a daily schedule when jobagent.skills.learning.enabled is true, or by hand (POST /admin/skills/learn).
 */
@Service
public class SkillLearningService {

    private static final Logger log = LoggerFactory.getLogger(SkillLearningService.class);

    static final int CONTEXTS_PER_TERM = 5;

    public record MineReport(int jobsScanned, int termsFound, int newTerms, int termsAtMinCompanies, int minCompanies,
                             List<StoredCandidate> top) {
    }

    public record ReviewReport(int asked, int newSkills, int aliases, int rejected, int unanswered, int failedBatches,
                               double costUsd, List<StoredCandidate> accepted) {
    }

    public record ApplyReport(List<String> newSkills, int spellingsAdded, int jobsReextracted, int jobsWithLearnedSkills) {
    }

    /** One whole run, in plain numbers. */
    public record LearnReport(int jobsScanned, int candidateWords, int wordsAskedToOpus, int newSkills, int otherNames,
                              int rejected, int unanswered, double costUsd, List<String> skillsAdded,
                              int spellingsAdded, int jobsUpdated, long seconds) {
    }

    private final SkillLearningRepository repository;
    private final SkillReviewer reviewer;
    private final SkillExtractor dictionary;
    private final RequirementsService requirements;
    private final Gazetteer gazetteer;
    private final SkillLearningProperties properties;
    private final AtomicBoolean busy = new AtomicBoolean();

    public SkillLearningService(SkillLearningRepository repository, SkillReviewer reviewer, SkillExtractor dictionary,
                                RequirementsService requirements, Gazetteer gazetteer, SkillLearningProperties properties) {
        this.repository = repository;
        this.reviewer = reviewer;
        this.dictionary = dictionary;
        this.requirements = requirements;
        this.gazetteer = gazetteer;
        this.properties = properties;
        log.info("Skill learning schedule: {}", properties.enabled()
                ? "ON (cron " + properties.cron() + ", at most " + properties.maxTermsPerRun() + " words per run)"
                : "OFF (only POST /admin/skills/learn runs it)");
    }

    /** The daily run, when enabled. */
    @Scheduled(cron = "${jobagent.skills.learning.cron:0 30 3 * * *}")
    void scheduledRun() {
        if (!properties.enabled()) {
            return;
        }
        try {
            learn();
        } catch (RuntimeException e) {
            log.warn("Scheduled skill learning failed: {}", e.getMessage());
        }
    }

    /** Every step in order: mine, Opus review with checks, apply. Refuses to start while a run is going. */
    public LearnReport learn() {
        return exclusive(() -> {
            long start = System.nanoTime();
            MineReport mined = mine(properties.minCompanies());
            ReviewReport reviewed = review(properties.minCompanies(), properties.maxTermsPerRun(), properties.batchSize(),
                    properties.parallelism(), properties.model());
            ApplyReport applied = apply();
            LearnReport report = new LearnReport(mined.jobsScanned(), mined.termsFound(), reviewed.asked(),
                    reviewed.newSkills(), reviewed.aliases(), reviewed.rejected(), reviewed.unanswered(),
                    reviewed.costUsd(), applied.newSkills(), applied.spellingsAdded(), applied.jobsReextracted(),
                    (System.nanoTime() - start) / 1_000_000_000);
            log.info("Skill learning run: {}", report);
            return report;
        });
    }

    /** At startup: the learned spellings go into the dictionary. */
    @EventListener(ApplicationReadyEvent.class)
    public void loadLearned() {
        dictionary.useLearned(repository.activeLearned());
    }

    // ---------------------------------------------------------------- 1. mine

    private MineReport mine(int minCompanies) {
        Set<String> companies = new LinkedHashSet<>();
        repository.companyNames().forEach(n -> companies.add(n.toLowerCase(Locale.ROOT)));
        SkillMiner.Tally tally = new SkillMiner.Tally();
        List<JobText> jobs = repository.openJobs();
        for (JobText job : jobs) {
            tally.add(job.id(), job.companyId(), SkillMiner.candidates(text(job),
                    item -> dictionary.canonical(item).isPresent(),
                    item -> companies.contains(item.toLowerCase(Locale.ROOT)) || !gazetteer.citiesByName(item).isEmpty()
                            || gazetteer.countryByName(item).isPresent()));
        }
        List<Candidate> found = tally.candidates(CONTEXTS_PER_TERM);
        int added = repository.upsert(found);
        int atMin = (int) found.stream().filter(c -> c.companies() >= minCompanies).count();
        log.info("Skill mining: {} jobs, {} candidate terms ({} new), {} at {}+ companies", jobs.size(), found.size(),
                added, atMin, minCompanies);
        return new MineReport(jobs.size(), found.size(), added, atMin, minCompanies,
                repository.candidates("NEW", minCompanies, 40));
    }

    /** The job as the miner reads it: the title and the posting without its intro sections. */
    static String text(JobText job) {
        StringBuilder text = new StringBuilder(job.title() == null ? "" : job.title()).append('\n');
        if (job.description() != null) {
            for (DescriptionSections.Line line : DescriptionSections.lines(job.description())) {
                if (line.section() != DescriptionSections.Kind.INTRO) {
                    text.append(line.text()).append('\n');
                }
            }
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- 2. review

    private ReviewReport review(int minCompanies, int limit, int batchSize, int parallelism, String model) {
        List<StoredCandidate> todo = repository.candidates("NEW", minCompanies, limit);
        List<List<StoredCandidate>> batches = new ArrayList<>();
        for (int i = 0; i < todo.size(); i += batchSize) {
            batches.add(todo.subList(i, Math.min(i + batchSize, todo.size())));
        }
        log.info("Skill review: {} terms in {} batches, model {}", todo.size(), batches.size(), model);
        Semaphore slots = new Semaphore(Math.max(1, parallelism));
        List<Future<BatchResult>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<StoredCandidate> batch : batches) {
                futures.add(executor.submit(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        return reviewer.review(batch.stream().map(StoredCandidate::candidate).toList(), model);
                    } finally {
                        slots.release();
                    }
                }));
            }
        }
        int newSkills = 0, aliases = 0, rejected = 0, answered = 0, failed = 0;
        double cost = 0;
        for (Future<BatchResult> future : futures) {
            BatchResult result;
            try {
                result = future.get();
            } catch (Exception e) {
                failed++;
                log.warn("Skill review batch failed: {}", e.getMessage());
                continue;
            }
            cost += result.costUsd();
            for (var entry : result.outcomes().entrySet()) {
                Outcome outcome = entry.getValue();
                repository.decide(entry.getKey(), outcome, model);
                answered++;
                switch (outcome.status()) {
                    case "NEW_SKILL" -> newSkills++;
                    case "ALIAS" -> aliases++;
                    default -> rejected++;
                }
            }
        }
        log.info("Skill review done: {} new skills, {} aliases, {} rejected, {} unanswered, {} failed batches, ${}",
                newSkills, aliases, rejected, todo.size() - answered, failed, String.format(Locale.ROOT, "%.2f", cost));
        return new ReviewReport(todo.size(), newSkills, aliases, rejected, todo.size() - answered, failed, cost,
                repository.toApply());
    }

    // ---------------------------------------------------------------- 3. apply / undo

    private ApplyReport apply() {
        List<StoredCandidate> accepted = repository.toApply();
        List<String> spellings = new ArrayList<>();
        Set<String> skills = new LinkedHashSet<>();
        Set<String> newSkills = new LinkedHashSet<>();
        for (StoredCandidate c : accepted) {
            Category category = Category.valueOf(c.category());
            for (String spelling : c.spellings()) {
                repository.learn(c.skill(), category, spelling, c.ambiguous(), c.key());
                spellings.add(spelling);
            }
            skills.add(c.skill());
            if ("NEW_SKILL".equals(c.status())) {
                newSkills.add(c.skill());
            }
            repository.markApplied(c.key());
        }
        loadLearned();
        int marked = repository.markJobsMentioning(spellings);
        requirements.rebuild(false);
        int jobsWith = repository.jobsWithAnySkill(List.copyOf(skills));
        log.info("Skill apply: {} spellings ({} new skills), {} jobs re-extracted, {} jobs now ask for them",
                spellings.size(), newSkills.size(), marked, jobsWith);
        return new ApplyReport(List.copyOf(newSkills), spellings.size(), marked, jobsWith);
    }

    private <T> T exclusive(Supplier<T> work) {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("Skill learning is already running");
        }
        try {
            return work.get();
        } finally {
            busy.set(false);
        }
    }
}
