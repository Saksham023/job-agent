package io.github.saksham023.jobagent.alerts;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AlertSenderTest {

    @Test
    void waitsLongerAfterEachFailureUpToAnHour() {
        assertThat(AlertSender.backoff(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(AlertSender.backoff(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(AlertSender.backoff(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(AlertSender.backoff(10)).isEqualTo(Duration.ofMinutes(60));
    }
}
