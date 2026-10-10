package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.ExperienceExtractor.Experience;
import io.github.saksham023.jobagent.requirements.JobClassifier.Classification;
import io.github.saksham023.jobagent.requirements.RequirementsRepository.JobText;
import io.github.saksham023.jobagent.requirements.SkillExtractor.Skills;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Runs the three rule-based extractors (experience, family, skills) over jobs and stores the results. Each
 * company's boilerplate lines (CompanyBoilerplate: intro, benefits, legal text) are removed first.
 * Bump EXTRACTOR_VERSION whenever a rule or data file changes, so the next rebuild re-extracts every job.
 * Gaps the rules leave are filled by a model in a separate run (GapFillRunner); a rebuild re-applies those stored
 * fills, so it never costs a model call.
 */
@Service
public class RequirementsService {

    private static final Logger log = LoggerFactory.getLogger(RequirementsService.class);

    /**
     * 1 = the rules and dictionaries of Milestone 2 (2026-10-05).
     * 2 = shared section detection (intro sections skipped for experience), skills after a slash ("Python/Java").
     * 8 = company boilerplate lines skipped, HARDWARE_ENGINEERING family, "8 years to 18 years" ranges (Workday).
     * 9 = a specific technical department beats the generic "engineer" title, ASICS / DV / synthesis are hardware,
     *     level ladders (Qualcomm), dotted and "Remote- India-" locations.
     * 10 = family_guessed: a SOFTWARE_ENGINEERING family that comes only from the catch-all title is marked for the
     *      model to check.
     */
    public static final int EXTRACTOR_VERSION = 14;

    private static final Pattern INTERN = Pattern.compile("(?i)\\b(?:intern(?:ship)?|trainee|apprentice)\\b");
    private static final Pattern CONTRACT = Pattern.compile("(?i)\\b(?:contract|contractor|temporary|freelance)\\b");
    private static final Pattern PART_TIME = Pattern.compile("(?i)\\bpart[- ]?time\\b");
    private static final Pattern FULL_TIME = Pattern.compile("(?i)full[- ]?time|fulltime|permanent|on-roll|regular");

    /** @param skipped jobs whose extraction threw (logged, left for the next rebuild) */
    public record RebuildResult(int extracted, int skipped, long elapsedMs) {
    }

    private final ReentrantLock rebuildLock = new ReentrantLock();
    private final RequirementsRepository repository;
    private final ExperienceExtractor experienceExtractor;
    private final JobClassifier jobClassifier;
    private final SkillExtractor skillExtractor;
    private final GapFillRepository gapFills;

    public RequirementsService(RequirementsRepository repository, ExperienceExtractor experienceExtractor,
                               JobClassifier jobClassifier, SkillExtractor skillExtractor, GapFillRepository gapFills) {
        this.repository = repository;
        this.experienceExtractor = experienceExtractor;
        this.jobClassifier = jobClassifier;
        this.skillExtractor = skillExtractor;
        this.gapFills = gapFills;
    }

    /**
     * @param all true: re-extract every open job; false: only new, changed, or older-version ones
     */
    public RebuildResult rebuild(boolean all) {
        rebuildLock.lock();                    // parallel crawls each trigger extraction; it runs one at a time
        try {
            return rebuildLocked(all);
        } finally {
            rebuildLock.unlock();
        }
    }

    private RebuildResult rebuildLocked(boolean all) {
        long startNanos = System.nanoTime();
        List<JobText> jobs = repository.findJobsToExtract(all ? Integer.MAX_VALUE : EXTRACTOR_VERSION);

        int extracted = 0;
        int skipped = 0;
        Map<String, CompanyBoilerplate> boilerplateByCompany = new HashMap<>();
        for (JobText job : jobs) {
            try {
                CompanyBoilerplate boilerplate = boilerplateByCompany.computeIfAbsent(job.companyName(),
                        company -> CompanyBoilerplate.of(repository.companyPostings(company)));
                extract(job, boilerplate);
                extracted++;
            } catch (RuntimeException e) {
                skipped++;
                log.warn("requirements: skipping job {} '{}': {}", job.jobId(), job.title(), e.toString());
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("requirements: extracted {}, skipped {} ({} ms, extractor v{})", extracted, skipped, elapsedMs, EXTRACTOR_VERSION);
        return new RebuildResult(extracted, skipped, elapsedMs);
    }

    /** @param boilerplate the company's repeated lines, skipped by every extractor */
    private void extract(JobText job, CompanyBoilerplate boilerplate) {
        String description = boilerplate.strip(job.description());
        Experience experience = experienceExtractor.extract(job.title(), description, job.employmentType());
        Classification classification = jobClassifier.classify(job.title(), job.department(), job.function(), description);
        Skills skills = skillExtractor.extract(job.title(), description, job.companyName());
        repository.upsert(job.jobId(), EXTRACTOR_VERSION, experience, classification, skills,
                employmentType(job.employmentType(), job.title()));
        gapFills.reapply(job.jobId());              // the rules reset the model's fills; put them back (no new call)
    }

    /**
     * The platforms' free-text values ("Full-time Employment", "FullTime", "On-roll", "Intern"...) mapped to
     * INTERN / CONTRACT / PART_TIME / FULL_TIME. The title is checked too: "Recruiter (Contract)".
     */
    static String employmentType(String raw, String title) {
        String text = (raw == null ? "" : raw) + " " + (title == null ? "" : title);
        if (INTERN.matcher(text).find()) {
            return "INTERN";
        }
        if (CONTRACT.matcher(text).find()) {
            return "CONTRACT";
        }
        if (PART_TIME.matcher(text).find()) {
            return "PART_TIME";
        }
        if (raw != null && FULL_TIME.matcher(raw.toLowerCase(Locale.ROOT)).find()) {
            return "FULL_TIME";
        }
        return null;
    }
}