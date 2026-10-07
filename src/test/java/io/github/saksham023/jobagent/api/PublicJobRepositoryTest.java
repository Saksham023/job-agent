package io.github.saksham023.jobagent.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The facet counting rule that keeps the public API answering while a crawl is saving jobs. */
class PublicJobRepositoryTest {

    @Test
    void aJobWithoutAFamilyYetIsNotCountedAndDoesNotBreakTheJson() {
        Map<String, Integer> counts = new HashMap<>();
        PublicJobRepository.addCount(counts, "SOFTWARE_ENGINEERING", 12);
        PublicJobRepository.addCount(counts, null, 1);                 // saved a moment ago, requirements not extracted yet
        PublicJobRepository.addCount(counts, "DATA_ML", 3);

        assertThat(counts).containsOnlyKeys("SOFTWARE_ENGINEERING", "DATA_ML");
        // a null key is exactly what made the response fail ("Null key for a Map not allowed in JSON")
        assertThat(JsonMapper.builder().build().writeValueAsString(counts)).contains("SOFTWARE_ENGINEERING").doesNotContain("null");
    }
}
