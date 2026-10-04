package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.requirements.JobFamily;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Arrays;
import java.util.List;

/**
 * What a candidate is looking for, as Claude (or a test) fills it from a resume. Any spelling is accepted;
 * the service maps skills and places onto the same canonical names the jobs use.
 *
 * @param yearsOfExperience  total professional years, or null when unknown (then experience never filters)
 * @param skills             everything the candidate knows: "Java", "Spring", "k8s", "Postgres"...
 * @param primaryLanguages   the languages the candidate mainly works in; derived from skills when empty
 * @param preferredLocations cities or metros the USER EXPLICITLY ASKED FOR ("Bengaluru", "Gurgaon", "NCR"). Never
 *                           inferred from the resume's address: where someone lives is not where they want to
 *                           work. Empty = anywhere in India. A filter only; location never affects the score.
 * @param openToRemote       whether remote jobs are acceptable (default true); remote jobs pass a location filter
 * @param families           job families to search; empty = all TECH families
 */
public record Profile(
        @Min(0) @Max(50) Integer yearsOfExperience,
        List<String> skills,
        List<String> primaryLanguages,
        List<String> preferredLocations,
        Boolean openToRemote,
        List<JobFamily> families
) {

    public Profile {
        skills = skills == null ? List.of() : List.copyOf(skills);
        primaryLanguages = primaryLanguages == null ? List.of() : List.copyOf(primaryLanguages);
        preferredLocations = preferredLocations == null ? List.of() : List.copyOf(preferredLocations);
        openToRemote = openToRemote == null ? Boolean.TRUE : openToRemote;
        families = families == null || families.isEmpty()
                ? Arrays.stream(JobFamily.values()).filter(JobFamily::isTech).toList()
                : List.copyOf(families);
    }
}