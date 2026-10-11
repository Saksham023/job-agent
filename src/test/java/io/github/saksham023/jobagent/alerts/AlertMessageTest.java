package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.alerts.AlertMatchRepository.MatchedJob;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertMessageTest {

    private static MatchedJob job(long id, String title, List<String> cities, boolean remote, Integer min, Integer max, boolean stated) {
        return new MatchedJob(id, title, "Amazon", cities, remote, min, max, stated, "https://www.amazon.jobs/en/jobs/" + id, true);
    }

    @Test
    void listsEachJobWithCompanyPlaceAndYears() {
        String message = AlertMessage.build(List.of(
                job(1, "SDE II", List.of("Bengaluru"), false, 2, 4, true),
                job(2, "Senior <Backend> & Infra", List.of(), true, 5, null, true),
                job(3, "Data Engineer", List.of("Pune", "Hyderabad"), true, null, null, false)), 20, "https://jobs.example/");

        assertThat(message).startsWith("<b>🔔 3 new jobs for you</b>");
        assertThat(message).contains("<a href=\"https://www.amazon.jobs/en/jobs/1\">SDE II</a>\n   Amazon · Bengaluru · 2-4 yrs");
        assertThat(message).contains("Senior &lt;Backend&gt; &amp; Infra</a>\n   Amazon · Remote · 5+ yrs");   // escaped for Telegram HTML
        assertThat(message).contains("Amazon · Pune, Hyderabad / Remote · experience not stated");
        assertThat(message).endsWith("Amazon · Pune, Hyderabad / Remote · experience not stated\n\n<a href=\"https://jobs.example/\">Manage your alert</a> · /stop to pause");
        assertThat(message).doesNotContain("more on JobRadar");
    }

    @Test
    void longListsStopAtTheMaximumAndCountTheRest() {
        List<MatchedJob> jobs = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            jobs.add(job(i, "Job " + i, List.of("Bengaluru"), false, 1, 3, true));
        }
        String message = AlertMessage.build(jobs, 20, "");

        assertThat(message).startsWith("<b>🔔 25 new jobs for you</b>");
        assertThat(message).contains("Job 20</a>").doesNotContain("Job 21</a>");
        assertThat(message).contains("…and 5 more on JobRadar").endsWith("/stop to pause these alerts");
        assertThat(message.length()).isLessThan(4096);
    }

    @Test
    void neverPassesTelegramsLengthLimit() {
        List<MatchedJob> jobs = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            jobs.add(job(i, "x".repeat(300), List.of("Bengaluru"), false, 1, 3, true));
        }
        String message = AlertMessage.build(jobs, 20, "https://jobs.example/");
        assertThat(message.length()).isLessThan(4096);
        assertThat(message).contains("more on JobRadar");
    }

    @Test
    void oneJobIsSingular() {
        assertThat(AlertMessage.build(List.of(job(1, "SDE", List.of(), false, 3, 3, true)), 20, ""))
                .startsWith("<b>🔔 1 new job for you</b>").contains("Amazon · 3 yrs");
    }
}
