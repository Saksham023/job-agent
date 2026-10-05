package io.github.saksham023.jobagent.eval;

import java.util.List;

/**
 * A candidate as the judge sees it, from eval/judge-profiles.json: plain facts from a resume, no name or contact.
 *
 * @param id            used in run names ("saksham", "python-data-engineer")
 * @param description   for people reading the file; never sent to the model
 * @param years         professional years as on the resume (decimal)
 * @param mainLanguages the languages the candidate mainly works in
 * @param otherSkills   everything else they know
 * @param wants         the kinds of roles they want, in words
 */
public record JudgeProfile(String id, String description, double years, List<String> mainLanguages,
                           List<String> otherSkills, String wants) {
}