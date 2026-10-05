package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.matching.MatchCandidateRepository.Candidate;
import io.github.saksham023.jobagent.matching.SkillImplications.Implied;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    static final double PARTIAL_LANGUAGE = 0.6;

    /**
     * Long skill lists are wish lists: matching 5 core skills of a 17-skill posting is a strong match, so
     * coverage is measured against at most this many required skills.
     */
    static final int MAX_REQUIRED_SKILLS_COUNTED = 8;

    /**
     * A posting that lists only one or two skills is weak evidence of fit, so coverage is measured against at
     * least this many (2 of 2 matched counts as 2 of 4, not as a perfect match).
     */
    static final double MIN_SKILLS_COUNTED = 4;

    /**
     * The candidate after normalization: canonical skills and languages, plus the location filter as canonical
     * cities (used by the SQL filter and shown back to the caller, never by the score). impliedSkills are the
     * skills credited through SkillImplications (AWS via DynamoDB), each with its credit.
     */
    public record ResolvedProfile(Integer years, Set<String> skills, Set<String> languages,
                                  Set<String> preferredCities, boolean openToRemote,
                                  Map<String, Implied> impliedSkills) {

        /** A profile without implied skills (tests, callers that do not expand). */
        public ResolvedProfile(Integer years, Set<String> skills, Set<String> languages,
                               Set<String> preferredCities, boolean openToRemote) {
            this(years, skills, languages, preferredCities, openToRemote, Map.of());
        }

        /** 1 for a skill the candidate listed, the implied credit for an implied one, 0 otherwise. */
        double credit(String skill) {
            if (skills.contains(skill)) {
                return 1.0;
            }
            Implied implied = impliedSkills.get(skill);
            return implied == null ? 0.0 : implied.credit();
        }

        /** How a matched skill is shown: "Kafka", "AWS (via DynamoDB)", "Microservices (half credit, via Distributed Systems)". */
        String label(String skill) {
            Implied implied = skills.contains(skill) ? null : impliedSkills.get(skill);
            if (implied == null) {
                return skill;
            }
            return skill + (implied.credit() < 1.0 ? " (half credit, via " : " (via ") + implied.via() + ")";
        }
    }

    /** One ranked job with everything needed to explain it. */
    public record Match(long jobId, int score, String company, String title, String url, List<String> cities,
                        boolean remote, Instant postedAt, Integer minYears, Integer maxYears, String family,
                        List<String> secondaryFamilies, List<String> matchedRequired, List<String> missingRequired, List<String> matchedPreferred,
                        List<String> reasons) {
    }

    public Match score(Candidate job, ResolvedProfile profile) {
        List<String> reasons = new ArrayList<>();

        List<String> matchedRequired = job.requiredSkills().stream()
                .filter(s -> profile.credit(s) > 0).map(profile::label).toList();
        List<String> missingRequired = job.requiredSkills().stream().filter(s -> profile.credit(s) == 0).toList();
        List<String> matchedPreferred = job.preferredSkills().stream()
                .filter(s -> profile.credit(s) > 0).map(profile::label).toList();

        double requiredHits = job.requiredSkills().stream().mapToDouble(profile::credit).sum();
        double preferredHits = job.preferredSkills().stream().mapToDouble(profile::credit).sum();
        double skills = skillScore(job, requiredHits, preferredHits, reasons);
        double language = languageScore(job, profile, reasons);
        double experience = experienceScore(job, profile, reasons);
        if (job.remote()) {
            reasons.add("remote");                                    // information only, not scored
        }

        int score = (int) Math.round(SKILLS_WEIGHT * skills + LANGUAGE_WEIGHT * language
                + EXPERIENCE_WEIGHT * experience);

        return new Match(job.jobId(), score, job.company(), job.title(), job.url(), job.cities(), job.remote(),
                job.postedAt(),
                job.minYears(), job.maxYears(), job.family(), job.secondaryFamilies(), matchedRequired, missingRequired, matchedPreferred,
                List.copyOf(reasons));
    }

    /**
     * Required skills count fully, preferred ones half: (req hit + 0.5 pref hit) / (req + 0.5 pref), where the
     * required count is capped at MAX_REQUIRED_SKILLS_COUNTED and the whole denominator is at least
     * MIN_SKILLS_COUNTED; the result is capped at 1. A hit is the skill's credit, so a half-credit implied skill
     * adds 0.5.
     */
    private static double skillScore(Candidate job, double requiredHits, double preferredHits, List<String> reasons) {
        int required = job.requiredSkills().size();
        int preferred = job.preferredSkills().size();
        if (required == 0 && preferred == 0) {
            reasons.add("job lists no recognizable skills (neutral)");
            return NEUTRAL;
        }
        double possible = Math.max(MIN_SKILLS_COUNTED,
                Math.min(required, MAX_REQUIRED_SKILLS_COUNTED) + PREFERRED_SKILL_VALUE * preferred);
        double earned = requiredHits + PREFERRED_SKILL_VALUE * preferredHits;
        reasons.add(count(requiredHits) + "/" + required + " required skills"
                + (preferred > 0 ? ", " + count(preferredHits) + "/" + preferred + " preferred" : ""));
        return Math.min(1.0, earned / possible);
    }

    /** 3.0 -> "3", 2.5 -> "2.5". */
    private static String count(double hits) {
        return hits == Math.rint(hits) ? String.valueOf((long) hits) : String.valueOf(hits);
    }

    /**
     * Full when you work in every one of the job's main languages, partial when in some of them, 0 when in none.
     * The order a posting lists them in does not matter ("JavaScript and Java" = "Java and JavaScript"), so a
     * Java-only job outranks a Java + JavaScript job for a Java developer, and the reverse for a JavaScript one.
     */
    private static double languageScore(Candidate job, ResolvedProfile profile, List<String> reasons) {
        List<String> jobLanguages = job.primaryLanguages();
        if (jobLanguages.isEmpty()) {
            return NEUTRAL;
        }
        List<String> shared = jobLanguages.stream().filter(profile.languages()::contains).toList();
        List<String> others = jobLanguages.stream().filter(l -> !profile.languages().contains(l)).toList();
        if (others.isEmpty()) {
            reasons.add(jobLanguages.size() == 1 ? "main language " + jobLanguages.getFirst() + " matches"
                    : "main languages " + String.join(", ", jobLanguages) + " match");
            return 1.0;
        }
        if (!shared.isEmpty()) {
            reasons.add("uses " + String.join(", ", shared) + ", also " + String.join(", ", others));
            return PARTIAL_LANGUAGE;
        }
        reasons.add("main language " + String.join(", ", jobLanguages) + " is not one of yours");
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