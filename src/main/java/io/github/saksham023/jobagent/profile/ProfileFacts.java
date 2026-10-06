package io.github.saksham023.jobagent.profile;

import java.util.List;

/**
 * What a resume says about the candidate, as Claude (or an API caller) read it. Stored once per profile and never
 * changed; where and what the candidate wants to search lives in SearchPreferences instead.
 *
 * @param yearsOfExperience total professional years, decimals allowed (1.6), or null when unknown
 * @param skills            every technical skill as written on the resume
 * @param primaryLanguages  the languages the candidate mainly works in (empty = derived from skills)
 * @param wants             the kinds of roles wanted, in the candidate's words, or null
 */
public record ProfileFacts(Double yearsOfExperience, List<String> skills, List<String> primaryLanguages, String wants) {

    public ProfileFacts {
        skills = skills == null ? List.of() : List.copyOf(skills);
        primaryLanguages = primaryLanguages == null ? List.of() : List.copyOf(primaryLanguages);
        wants = wants == null || wants.isBlank() ? null : wants.strip();
    }

    /** No fact at all was given (a call that only names a profile id). */
    public boolean noneGiven() {
        return yearsOfExperience == null && skills.isEmpty() && primaryLanguages.isEmpty() && wants == null;
    }
}
