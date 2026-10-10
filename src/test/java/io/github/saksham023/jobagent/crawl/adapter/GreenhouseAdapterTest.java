package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.crawl.RawLocation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GreenhouseAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void anOfficeWithoutALocationGivesItsName() {
        var job = JSON.readTree("""
                {"location": {"name": "N/A"},
                 "offices": [{"name": "India Locations", "location": null}, {"name": "Bengaluru", "location": "Bengaluru, India"}]}
                """);
        assertThat(GreenhouseAdapter.locations(job)).extracting(RawLocation::text)
                .containsExactly("N/A", "India", "Bengaluru, India");
    }
}
