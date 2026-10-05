package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.requirements.JobFamily;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Arrays;
import java.util.List;

/**
 * What a candidate is looking for, as Claude (or a test) fills it from a resume. Any spelling is accepted;
 * the service maps skills and places onto the same canonical names the jobs use.
 *
 * @param yearsOfExperience  total professional years as on the resume, decimals allowed (1.6); the service rounds
 *                           them (MatchingProperties.roundUpFrom). Null when unknown: then only an explicit
 *                           jobYearsFrom/jobYearsTo filters on experience
 * @param skills             everything the candidate knows: "Java", "Spring", "k8s", "Postgres"...
 * @param primaryLanguages   the languages the candidate mainly works in; derived from skills when empty
 * @param preferredLocations cities or metros the USER EXPLICITLY ASKED FOR ("Bengaluru", "Gurgaon", "NCR"). Never
 *                           inferred from the resume's address: where someone lives is not where they want to
 *                           work. Empty = anywhere in India. A filter only; location never affects the score.
 * @param openToRemote       whether remote jobs are acceptable (default true); remote jobs pass a location filter
 * @param families           job families to search; empty = all TECH families
 * @param jobYearsFrom       only when the user EXPLICITLY asks for another experience range: the lowest years a
 *                           job may ask for... (null = the default window from the candidate's years)
 * @param jobYearsTo         ...and the highest ("show me jobs asking up to 4 years" = jobYearsTo 4)
 */
public record Profile(
        @DecimalMin("0") @DecimalMax("50") Double yearsOfExperience,
        List<String> skills,
        List<String> primaryLanguages,
        List<String> preferredLocations,
        Boolean openToRemote,
        List<JobFamily> families,
        @Min(0) @Max(50) Integer jobYearsFrom,
        @Min(0) @Max(50) Integer jobYearsTo
) {

    public Profile {
        skills = skills == null ? List.of() : List.copyOf(skills);
        primaryLanguages = primaryLanguages == null ? List.of() : List.copyOf(primaryLanguages);
        preferredLocations = preferredLocations == null ? List.of() : List.copyOf(preferredLocations);
        openToRemote = openToRemote == null ? Boolean.TRUE : openToRemote;
        families = families == null || families.isEmpty()
                ? Arrays.stream(JobFamily.values()).filter(JobFamily::isTech).toList()
                : List.copyOf(families);
        if (jobYearsFrom != null && jobYearsTo != null && jobYearsFrom > jobYearsTo) {
            throw new IllegalArgumentException("jobYearsFrom must not be greater than jobYearsTo");
        }
    }

    /** A profile with the default experience window. */
    public Profile(Double yearsOfExperience, List<String> skills, List<String> primaryLanguages,
                   List<String> preferredLocations, Boolean openToRemote, List<JobFamily> families) {
        this(yearsOfExperience, skills, primaryLanguages, preferredLocations, openToRemote, families, null, null);
    }
}