package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.crawl.DetailCache.Known;
import io.github.saksham023.jobagent.crawl.DetailCache.Stored;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which stored details a crawl may reuse (the database part, loading only fresh details, is plain SQL). */
class DetailCacheTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode DETAIL = JSON.readTree("{\"jobDescription\": \"<p>Build APIs.</p>\"}");

    private static final Known KNOWN = new Known(Map.of(
            "A", new Stored("hash-1", Instant.now(), DETAIL),
            "B", new Stored(null, Instant.now(), DETAIL)));             // crawled before list fingerprints existed

    @Test
    void anUnchangedListEntryReusesTheStoredDetail() {
        assertThat(KNOWN.reusable("A", "hash-1")).isSameAs(DETAIL);
    }

    @Test
    void aChangedListEntryOrANewJobIsFetched() {
        assertThat(KNOWN.reusable("A", "hash-2")).isNull();             // title or location changed
        assertThat(KNOWN.reusable("C", "hash-1")).isNull();             // new job
        assertThat(KNOWN.reusable(null, "hash-1")).isNull();
        assertThat(Known.NONE.reusable("A", "hash-1")).isNull();
    }

    @Test
    void aJobWithoutARecordedFingerprintCountsAsUnchangedOnce() {
        assertThat(KNOWN.reusable("B", "anything")).isSameAs(DETAIL);
    }

    @Test
    void anOldDetailIsNotReusedButStaysAvailableAsAFallback() {
        Instant now = Instant.now();
        Known known = new Known(Map.of("A", new Stored("hash-1", now.minusSeconds(8 * 86_400), DETAIL)),
                now.minusSeconds(7 * 86_400));
        assertThat(known.reusable("A", "hash-1")).isNull();             // 8 days old: fetch again
        assertThat(known.anyAge("A")).isSameAs(DETAIL);                 // but better than nothing if that fails
        assertThat(known.anyAge("C")).isNull();
    }

    @Test
    void theFingerprintDependsOnEveryPartAndItsOrder() {
        JsonNode title = JSON.readTree("\"Backend Engineer\"");
        String hash = DetailCache.fingerprint(title, "Pune");
        assertThat(DetailCache.fingerprint(title, "Pune")).isEqualTo(hash);
        assertThat(DetailCache.fingerprint(title, "Mumbai")).isNotEqualTo(hash);
        assertThat(DetailCache.fingerprint("ab", "c")).isNotEqualTo(DetailCache.fingerprint("a", "bc"));
        assertThat(DetailCache.fingerprint(JSON.missingNode(), null)).isEqualTo(DetailCache.fingerprint("", ""));
    }
}