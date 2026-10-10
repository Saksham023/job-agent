package io.github.saksham023.jobagent.referral;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferralTemplateTest {

    @Test
    void theDefaultPassesItsOwnChecks() {
        assertThat(ReferralTemplate.check(ReferralTemplate.DEFAULT)).isEqualTo(ReferralTemplate.DEFAULT);
    }

    @Test
    void placeholdersAreTidiedAndOptionalPartsKept() {
        assertThat(ReferralTemplate.check("  Hi, {{ jobTitle }} at {{company}}.[ Resume: {{resumeLink}}]\r\n"))
                .isEqualTo("Hi, {{jobTitle}} at {{company}}.[ Resume: {{resumeLink}}]");
    }

    @Test
    void mistakesGetAMessageTheUserCanActOn() {
        assertThatThrownBy(() -> ReferralTemplate.check(" ")).hasMessageContaining("empty");
        assertThatThrownBy(() -> ReferralTemplate.check("Hi {{name}}")).hasMessageContaining("Unknown placeholder {{name}}");
        assertThatThrownBy(() -> ReferralTemplate.check("Hi {{company}")).hasMessageContaining("not written right");
        assertThatThrownBy(() -> ReferralTemplate.check("Hi [a [b]]")).hasMessageContaining("Square brackets");
        assertThatThrownBy(() -> ReferralTemplate.check("Hi [a")).hasMessageContaining("Square brackets");
        assertThatThrownBy(() -> ReferralTemplate.check("Hi a]")).hasMessageContaining("Square brackets");
        assertThatThrownBy(() -> ReferralTemplate.check("x".repeat(601))).hasMessageContaining("too long");
    }
}
