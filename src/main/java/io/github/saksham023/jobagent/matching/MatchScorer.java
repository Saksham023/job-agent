package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.matching.MatchCandidateRepository.Candidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scores one eligible job against a resolved profile, 0 to 100, and explains the score.
 * Skills 65, primary language 20, experience 15. Location is deliberately NOT scored: it is only a filter the
 * user chooses explicitly, so a job is never ranked higher just for being near where the candidate lives.
 * A component the job has no data for counts as neutral (half), so missing extraction never sinks a job.
 */
@Component
public class MatchScorer {

    static final double SKILLS_WEIGHT = 65;
    static final double LANGUAGE_WEIGHT = 20;
    static final double EXPERIENCE_WEIGHT = 15;
    static final double NEUTRAL = 0.5;
    static final double PREFERRED_SKILL_VALUE = 0.5;

    /**
     * Long skill lists are wish lists: matching 5 core skills of a 17-skill posting is a strong match, so
     * coverage is measured against at most this many required skills.
     */
    static final int MAX_REQUIRED_SKILLS_COUNTED = 8;

    /**
     * The candidate after normalization: canonical skills and languages, plus the location filter as canonical
     * cities (used by the SQL filter and shown back to the caller, never by the score).
     */
    public record ResolvedProfile(Integer years, Set<String> skills, Set<String> languages,
                                  Set<String> preferredCities, boolean openToRemote) {
    }

    /** One ranked job with everything needed to explain it. */
    public record Match(long jobId, int score, String company, String title, String url, List<String> cities,
                        boolean remote, Integer minYears, Integer maxYears, String family,
                        List<String> matchedRequired, List<String> missingRequired, List<String> matchedPreferred,
                        List<String> reasons) {
    }

    public Match score(Candidate job, ResolvedProfile profile) {
        List<String> reasons = new ArrayList<>();

        List<String> matchedRequired = job.requiredSkills().stream().filter(profile.skills()::contains).toList();
        List<String> missingRequired = job.requiredSkills().stream().filter(s -> !profile.skills().contains(s)).toList();
        List<String> matchedPreferred = job.preferredSkills().stream().filter(profile.skills()::contains).toList();

        double skills = skillScore(job, matchedRequired.size(), matchedPreferred.size(), reasons);
        double language = languageScore(job, profile, reasons);
        double experience = experienceScore(job, profile, reasons);
        if (job.remote()) {
            reasons.add("remote");                                    // information only, not scored
        }

        int score = (int) Math.round(SKILLS_WEIGHT * skills + LANGUAGE_WEIGHT * language
                + EXPERIENCE_WEIGHT * experience);

        return new Match(job.jobId(), score, job.company(), job.title(), job.url(), job.cities(), job.remote(),
                job.minYears(), job.maxYears(), job.family(), matchedRequired, missingRequired, matchedPreferred,
                List.copyOf(reasons));
    }

    /**
     * Required skills count fully, preferred ones half: (req hit + 0.5 pref hit) / (req + 0.5 pref), where the
     * required count is capped at MAX_REQUIRED_SKILLS_COUNTED; the result is capped at 1.
     */
    private static double skillScore(Candidate job, int requiredHits, int preferredHits, List<String> reasons) {
        int required = job.requiredSkills().size();
        int preferred = job.preferredSkills().size();
        if (required == 0 && preferred == 0) {
            reasons.add("job lists no recognizable skills (neutral)");
            return NEUTRAL;
        }
        double possible = Math.min(required, MAX_REQUIRED_SKILLS_COUNTED) + PREFERRED_SKILL_VALUE * preferred;
        double earned = requiredHits + PREFERRED_SKILL_VALUE * preferredHits;
        reasons.add(requiredHits + "/" + required + " required skills"
                + (preferred > 0 ? ", " + preferredHits + "/" + preferred + " preferred" : ""));
        return Math.min(1.0, earned / possible);
    }

    /** Full when the job's main language is yours, partial when one of its languages is, 0 when none is. */
    private static double languageScore(Candidate job, ResolvedProfile profile, List<String> reasons) {
        List<String> jobLanguages = job.primaryLanguages();
        if (jobLanguages.isEmpty()) {
            return NEUTRAL;
        }
        if (profile.languages().contains(jobLanguages.getFirst())) {
            reasons.add("main language " + jobLanguages.getFirst() + " matches");
            return 1.0;
        }
        List<String> shared = jobLanguages.stream().filter(profile.languages()::contains).toList();
        if (!shared.isEmpty()) {
            reasons.add("uses " + String.join(", ", shared) + " (main language is " + jobLanguages.getFirst() + ")");
            return 0.6;
        }
        reasons.add("main language " + jobLanguages.getFirst() + " is not one of yours");
        return 0.0;
    }

    /** Full inside the job's range, partial when slightly under or over, neutral when either side is unknown. */
    private static double experienceScore(Candidate job, ResolvedProfile profile, List<String> reasons) {
        Integer years = profile.years();
        Integer min = job.minYears();
        if (years == null || min == null) {
            return NEUTRAL;
        }
        Integer max = job.maxYears();
        String range = min + (max == null ? "+" : "-" + max) + " years";
        if (years < min) {
            reasons.add("asks " + range + ", you have " + years + " (slightly under)");
            return 0.6;
        }
        if (max != null && years > max) {
            reasons.add("asks " + range + ", you have " + years + " (above the range)");
            return 0.7;
        }
        reasons.add("asks " + range + ", you have " + years);
        return 1.0;
    }
}