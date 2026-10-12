package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.alerts.AlertMatchRepository.Claimed;
import io.github.saksham023.jobagent.alerts.AlertMatchRepository.MatchedJob;
import io.github.saksham023.jobagent.alerts.TelegramClient.TelegramException;
import io.github.saksham023.jobagent.matching.MatchingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertSenderTest {

    private static final long USER = 7;
    private static final long CHAT = 99;
    private static final List<Long> JOBS = List.of(1L);

    private final AlertMatchRepository matches = mock(AlertMatchRepository.class);
    private final AlertPrefsRepository prefs = mock(AlertPrefsRepository.class);
    private final TelegramLinkRepository links = mock(TelegramLinkRepository.class);
    private final TelegramClient telegram = mock(TelegramClient.class);
    private AlertSender sender;

    @BeforeEach
    void setUp() {
        AlertProperties properties = new AlertProperties(true, 50, 20, 5, Duration.ofMinutes(5), Duration.ofDays(2),
                Duration.ofMinutes(30), "", new AlertProperties.Telegram("1:x", "bot", "http://localhost",
                Duration.ofMinutes(10), AlertProperties.Telegram.Mode.POLLING, ""));
        sender = new AlertSender(matches, prefs, links, telegram, properties, new MatchingProperties("IN", 2, 1, 0.5),
                mock(TransactionTemplate.class));
        when(prefs.find(USER)).thenReturn(Optional.of(prefs(true)));
        when(links.forUser(USER)).thenReturn(Optional.of(new TelegramLinkRepository.Link(USER, CHAT, true)));
        when(matches.jobs(eq(USER), eq(JOBS), anyString())).thenReturn(List.of(
                new MatchedJob(1L, "Software Engineer", "Microsoft", List.of("Hyderabad"), false, 2, null, true,
                        "https://example.test/job/1", true)));
    }

    @Test
    void waitsLongerAfterEachFailureUpToAnHour() {
        assertThat(AlertSender.backoff(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(AlertSender.backoff(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(AlertSender.backoff(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(AlertSender.backoff(10)).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void sendsOneMessageAndMarksTheMatchesSent() {
        sender.send(new Claimed(USER, JOBS));

        verify(telegram).sendMessage(eq(CHAT), anyString());
        verify(matches).finish(USER, JOBS, "SENT", null);
    }

    @Test
    void anAlertSwitchedOffAfterTheMatchIsNotSent() {
        when(prefs.find(USER)).thenReturn(Optional.of(prefs(false)));

        sender.send(new Claimed(USER, JOBS));

        verify(telegram, never()).sendMessage(anyLong(), anyString());
        verify(matches).finish(USER, JOBS, "SKIPPED", "alert switched off");
    }

    @Test
    void telegramAskingToSlowDownDoesNotUseUpATry() {
        doThrow(new TelegramException(429, Duration.ofSeconds(30), "Telegram sendMessage: 429 Too Many Requests"))
                .when(telegram).sendMessage(eq(CHAT), anyString());

        sender.send(new Claimed(USER, JOBS));

        verify(matches).retryLater(eq(USER), eq(JOBS), eq(Duration.ofSeconds(30)), eq(false), anyInt(), anyString());
    }

    @Test
    void aRealFailureUsesUpATryAndBacksOff() {
        when(matches.attempts(USER, JOBS)).thenReturn(1);
        doThrow(new TelegramException(500, null, "Telegram sendMessage: 500 Internal Server Error"))
                .when(telegram).sendMessage(eq(CHAT), anyString());

        sender.send(new Claimed(USER, JOBS));

        verify(matches).retryLater(eq(USER), eq(JOBS), eq(Duration.ofMinutes(2)), eq(true), anyInt(), anyString());
    }

    private static AlertPrefs prefs(boolean enabled) {
        return new AlertPrefs(enabled, List.of("SOFTWARE_ENGINEERING"), 0, 3, true, List.of(), List.of(), List.of());
    }
}
