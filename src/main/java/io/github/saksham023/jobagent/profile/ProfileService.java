package io.github.saksham023.jobagent.profile;

import io.github.saksham023.jobagent.matching.Profile;
import org.springframework.stereotype.Service;

/**
 * Saved profiles (Milestone 8a). A search names EITHER a profile id OR the resume facts:
 * - facts only: a new profile is saved and its id returned, so the next search needs only the id;
 * - id only: the stored facts are used, so the candidate (and its profile_hash) is exactly the same as last time and
 *   every stored verdict is reused; preferences left out come from the profile's last search.
 * Both at once is refused: the facts of a profile never change (a new resume = a new profile).
 */
@Service
public class ProfileService {

    /** Draws before giving up; with 2^40 ids a single collision is already unlikely. */
    private static final int MAX_ID_ATTEMPTS = 5;

    /**
     * What a search runs with.
     *
     * @param profile     the stored profile
     * @param created     the profile was created by this call (tell the user its id)
     * @param preferences the preferences in effect (asked for, else the saved defaults)
     * @param search      facts + preferences as the matching code takes them
     */
    public record Resolved(SavedProfile profile, boolean created, SearchPreferences preferences, Profile search) {

        public String wants() {
            return profile.facts().wants();
        }
    }

    private final ProfileRepository repository;

    public ProfileService(ProfileRepository repository) {
        this.repository = repository;
    }

    /** Saves a new profile for these facts (always a new id, even for facts seen before). */
    public SavedProfile create(ProfileFacts facts, String source) {
        if (facts.skills().isEmpty()) {
            throw new IllegalArgumentException("skills are required to save a profile");
        }
        if (facts.yearsOfExperience() != null && (facts.yearsOfExperience() < 0 || facts.yearsOfExperience() > 50)) {
            throw new IllegalArgumentException("yearsOfExperience must be between 0 and 50");
        }
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            String id = ProfileIds.next();
            if (repository.insert(id, facts, source)) {
                return repository.find(id).orElseThrow();
            }
        }
        throw new IllegalStateException("Could not draw a free profile id");
    }

    /** The stored profile, or a clear error for an unknown id. */
    public SavedProfile get(String profileId) {
        String id = ProfileIds.normalize(profileId);
        SavedProfile profile = repository.find(id).orElseThrow(() -> new IllegalArgumentException(
                "Unknown profileId " + id + "; send the resume facts instead to create a new profile"));
        repository.touch(id);
        return profile;
    }

    /**
     * The profile and preferences for one search.
     *
     * @param profileId   a saved profile's id, or null
     * @param facts       the resume facts, or empty when profileId is given
     * @param preferences this search's preferences (null fields = use the saved defaults)
     * @param source      where a new profile comes from (mcp / api)
     */
    public Resolved resolve(String profileId, ProfileFacts facts, SearchPreferences preferences, String source) {
        SearchPreferences asked = preferences == null ? SearchPreferences.NONE : preferences;
        if (profileId != null && !profileId.isBlank()) {
            if (!facts.noneGiven()) {
                throw new IllegalArgumentException("Pass either profileId or the resume facts (years, skills, "
                        + "primaryLanguages, wants), not both: a profile's facts never change. For a new or changed "
                        + "resume, leave profileId out and a new profile is created.");
            }
            SavedProfile saved = get(profileId);
            return resolved(saved, false, asked.orElse(saved.defaults()));
        }
        return resolved(create(facts, source), true, asked);
    }

    /** Remembers the preferences and judgments key of a search made with this profile. */
    public void recordSearch(String profileId, SearchPreferences used, String profileHash) {
        repository.recordSearch(profileId, used, profileHash);
    }

    static Resolved resolved(SavedProfile saved, boolean created, SearchPreferences preferences) {
        ProfileFacts facts = saved.facts();
        Profile search = new Profile(facts.yearsOfExperience(), facts.skills(), facts.primaryLanguages(),
                preferences.preferredLocations(), preferences.openToRemote(), preferences.families(),
                preferences.jobYearsFrom(), preferences.jobYearsTo());
        return new Resolved(saved, created, preferences, search);
    }
}
