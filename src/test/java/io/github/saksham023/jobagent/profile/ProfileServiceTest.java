package io.github.saksham023.jobagent.profile;

import io.github.saksham023.jobagent.profile.ProfileService.Resolved;
import io.github.saksham023.jobagent.requirements.JobFamily;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Saved profiles with an in-memory stand-in for the table: ids, the either-id-or-facts rule, saved defaults. */
class ProfileServiceTest {

    /** The profiles table as a map; remembers which ids were tried. */
    static class InMemoryProfiles extends ProfileRepository {
        final Map<String, SavedProfile> rows = new HashMap<>();
        int collisions;                                        // the next inserts that find their id taken
        int inserts;

        InMemoryProfiles() {
            super(null, null);
        }

        @Override
        boolean insert(String id, ProfileFacts facts, String source) {
            inserts++;
            if (collisions > 0) {
                collisions--;
                return false;
            }
            if (rows.containsKey(id)) {
                return false;
            }
            rows.put(id, new SavedProfile(id, facts, source, null, Instant.EPOCH, Instant.EPOCH, null));
            return true;
        }

        @Override
        public Optional<SavedProfile> find(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void recordSearch(String id, SearchPreferences used, String profileHash) {
            SavedProfile p = rows.get(id);
            rows.put(id, new SavedProfile(id, p.facts(), p.source(), used, p.createdAt(), p.lastUsedAt(), Instant.EPOCH));
        }

        @Override
        public void touch(String id) {
        }
    }

    private static final ProfileFacts FACTS = new ProfileFacts(1.6, List.of("Java", "Kafka"), List.of("Java"), " backend ");
    private static final ProfileFacts NO_FACTS = new ProfileFacts(null, null, null, null);

    private final InMemoryProfiles table = new InMemoryProfiles();
    private final ProfileService service = new ProfileService(table);

    @Test
    void idsAreShortRandomAndReadable() {
        String id = ProfileIds.next();
        assertThat(id).matches("p-[0-9a-hjkmnp-tv-z]{8}");
        assertThat(ProfileIds.next()).isNotEqualTo(id);
        assertThat(ProfileIds.normalize(" P-7K3X9Q2M ")).isEqualTo("p-7k3x9q2m");
        assertThatThrownBy(() -> ProfileIds.normalize("p-7k3x9q2"))          // too short
                .hasMessage("profileId must look like p-7k3x9q2m, got: p-7k3x9q2");
        assertThatThrownBy(() -> ProfileIds.normalize("p-7k3x9q2i"))         // i is not in the alphabet
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factsCreateANewProfileEveryTime() {
        Resolved first = service.resolve(null, FACTS, null, "mcp");
        Resolved second = service.resolve(null, FACTS, null, "mcp");
        assertThat(first.created()).isTrue();
        assertThat(second.profile().id()).isNotEqualTo(first.profile().id());   // same facts, still a new profile
        assertThat(first.wants()).isEqualTo("backend");
        assertThat(first.search().skills()).containsExactly("Java", "Kafka");
        assertThat(first.search().yearsOfExperience()).isEqualTo(1.6);
    }

    @Test
    void aTakenIdIsDrawnAgain() {
        table.collisions = 2;
        SavedProfile saved = service.create(FACTS, "api");
        assertThat(table.inserts).isEqualTo(3);
        assertThat(saved.source()).isEqualTo("api");

        table.collisions = 5;
        assertThatThrownBy(() -> service.create(FACTS, "api")).hasMessage("Could not draw a free profile id");
    }

    @Test
    void theIdUsesTheStoredFactsAndTheLastPreferences() {
        String id = service.resolve(null, FACTS, null, "mcp").profile().id();
        table.recordSearch(id, new SearchPreferences(List.of("Bengaluru"), false,
                List.of(JobFamily.SOFTWARE_ENGINEERING), null, 4), "hash");

        Resolved again = service.resolve(id.toUpperCase(), NO_FACTS,
                new SearchPreferences(null, true, null, null, null), "mcp");
        assertThat(again.created()).isFalse();
        assertThat(again.search().skills()).containsExactly("Java", "Kafka");
        assertThat(again.search().preferredLocations()).containsExactly("Bengaluru");   // from the last search
        assertThat(again.search().openToRemote()).isTrue();                             // asked for now
        assertThat(again.search().families()).containsExactly(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(again.search().jobYearsTo()).isEqualTo(4);

        Resolved anywhere = service.resolve(id, NO_FACTS, new SearchPreferences(List.of(), null, null, null, null), "mcp");
        assertThat(anywhere.search().preferredLocations()).isEmpty();                   // [] = anywhere, not "left out"
    }

    @Test
    void idAndFactsTogetherAreRefused() {
        String id = service.resolve(null, FACTS, null, "mcp").profile().id();
        assertThatThrownBy(() -> service.resolve(id, new ProfileFacts(null, List.of("Go"), null, null), null, "mcp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Pass either profileId or the resume facts");
    }

    @Test
    void unknownIdsAndMissingSkillsGiveClearErrors() {
        assertThatThrownBy(() -> service.resolve("p-00000000", NO_FACTS, null, "mcp"))
                .hasMessage("Unknown profileId p-00000000; send the resume facts instead to create a new profile");
        assertThatThrownBy(() -> service.resolve(null, NO_FACTS, null, "mcp"))
                .hasMessage("skills are required to save a profile");
        assertThatThrownBy(() -> service.create(new ProfileFacts(60.0, List.of("Java"), null, null), "api"))
                .hasMessage("yearsOfExperience must be between 0 and 50");
    }

    @Test
    void leftOutPreferencesFallBackFieldByField() {
        SearchPreferences saved = new SearchPreferences(List.of("NCR"), false, List.of(JobFamily.DATA_ML), 1, 3);
        assertThat(SearchPreferences.NONE.orElse(saved)).isEqualTo(saved);
        assertThat(SearchPreferences.NONE.orElse(null)).isEqualTo(SearchPreferences.NONE);
        assertThat(new SearchPreferences(List.of(), null, null, null, null).orElse(saved).preferredLocations()).isEmpty();
    }
}
