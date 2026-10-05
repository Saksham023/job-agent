package io.github.saksham023.jobagent.matching;

/**
 * The experience range a search accepts. A job is eligible when its own range overlaps this window: with
 * 0 to 3, a "3-5 years" job is shown and a "4-6 years" job is not. Either end may be null (open), and a job
 * that states no years is always eligible.
 */
public record ExperienceWindow(Integer from, Integer to) {

    /**
     * Whole years from what the candidate has, e.g. 1.6 from a resume: at or above roundUpFrom the fraction
     * rounds up (0.5: 1.5 -> 2, 1.4 -> 1). Null stays null (unknown experience).
     */
    public static Integer roundYears(Double years, double roundUpFrom) {
        if (years == null) {
            return null;
        }
        double whole = Math.floor(years);
        double fraction = years - whole;
        return (int) whole + (fraction + 1e-9 >= roundUpFrom ? 1 : 0);
    }

    /**
     * The window to filter on: the candidate's own from/to when they asked for one, otherwise years minus
     * yearsBelow to years plus yearsAbove (never below 0). Null when there is nothing to filter on.
     */
    public static ExperienceWindow resolve(Integer years, Integer askedFrom, Integer askedTo, MatchingProperties settings) {
        Integer from = askedFrom != null ? askedFrom : years == null ? null : Math.max(0, years - settings.yearsBelow());
        Integer to = askedTo != null ? askedTo : years == null ? null : years + settings.yearsAbove();
        return from == null && to == null ? null : new ExperienceWindow(from, to);
    }
}
