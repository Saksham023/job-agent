package io.github.saksham023.jobagent.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RubricTest {

    @Test
    void theVersionComesFromTheTitleAndTheInstructionsFollowTheSeparator() {
        Rubric rubric = Rubric.parse("# Job-fit judge: rubric v3\n\nNotes for people.\n\n---\n\nYou are screening jobs.\n");

        assertThat(rubric.version()).isEqualTo("v3");
        assertThat(rubric.instructions()).isEqualTo("You are screening jobs.");
    }

    @Test
    void theRealRubricLoads() {
        Rubric rubric = Rubric.load(Path.of("eval", "judge-rubric.md"));

        assertThat(rubric.version()).isEqualTo("v2");
        assertThat(rubric.instructions()).startsWith("You are screening job postings").contains("\"verdict\"");
    }

    @Test
    void aFileWithoutVersionOrSeparatorIsRejected() {
        assertThatThrownBy(() -> Rubric.parse("just some text")).isInstanceOf(IllegalArgumentException.class);
    }
}