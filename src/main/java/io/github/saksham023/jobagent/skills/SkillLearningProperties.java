package io.github.saksham023.jobagent.skills;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * jobagent.skills.learning.*: the self-learning skill dictionary run (mine, Opus review, checks, apply).
 *
 * @param enabled         run it on the schedule (the manual POST /admin/skills/learn always works)
 * @param cron            when (Spring cron: second minute hour day month weekday); daily 03:30, after the 00:00 crawl
 * @param minCompanies    a word must appear in skill lists at this many different companies before Opus is asked
 * @param maxTermsPerRun  at most this many words go to Opus per run (cost cap: ~$1 per 250 words)
 * @param batchSize       words per Opus call
 * @param parallelism     Opus calls at the same time
 * @param model           the model that decides
 */
@ConfigurationProperties("jobagent.skills.learning")
public record SkillLearningProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("0 30 3 * * *") String cron,
        @DefaultValue("3") int minCompanies,
        @DefaultValue("300") int maxTermsPerRun,
        @DefaultValue("20") int batchSize,
        @DefaultValue("4") int parallelism,
        @DefaultValue("opus") String model) {

    public SkillLearningProperties {
        if (minCompanies < 1 || maxTermsPerRun < 1 || batchSize < 1 || batchSize > 40 || parallelism < 1 || parallelism > 8) {
            throw new IllegalArgumentException("jobagent.skills.learning: minCompanies >= 1, maxTermsPerRun >= 1, "
                    + "batchSize 1-40, parallelism 1-8");
        }
    }
}
