package io.github.saksham023.jobagent.profile;

import java.time.Instant;

/**
 * A stored profile.
 *
 * @param defaults     the preferences of the last search, or null before the first search
 * @param lastSearchAt when it was last searched with, or null
 */
public record SavedProfile(String id, ProfileFacts facts, String source, SearchPreferences defaults,
                           Instant createdAt, Instant lastUsedAt, Instant lastSearchAt) {
}
