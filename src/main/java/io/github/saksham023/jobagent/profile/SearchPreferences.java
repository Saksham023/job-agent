package io.github.saksham023.jobagent.profile;

import io.github.saksham023.jobagent.requirements.JobFamily;

import java.util.List;

/**
 * Where and what the candidate wants to search: may change from one search to the next, so it is not part of the
 * profile. The preferences of a profile's last search are saved as its defaults; a later search with the profile id
 * uses a default for every preference it leaves out (null). An empty list is a real answer ("anywhere"), not "left out".
 */
public record SearchPreferences(List<String> preferredLocations, Boolean openToRemote, List<JobFamily> families,
                                Integer jobYearsFrom, Integer jobYearsTo) {

    public static final SearchPreferences NONE = new SearchPreferences(null, null, null, null, null);

    /** These preferences, with every left-out one taken from the saved defaults (if any). */
    public SearchPreferences orElse(SearchPreferences defaults) {
        if (defaults == null) {
            return this;
        }
        return new SearchPreferences(
                preferredLocations != null ? preferredLocations : defaults.preferredLocations(),
                openToRemote != null ? openToRemote : defaults.openToRemote(),
                families != null ? families : defaults.families(),
                jobYearsFrom != null ? jobYearsFrom : defaults.jobYearsFrom(),
                jobYearsTo != null ? jobYearsTo : defaults.jobYearsTo());
    }
}
