package io.github.saksham023.jobagent.eval;

import io.github.saksham023.jobagent.eval.EvalReport.Report;
import io.github.saksham023.jobagent.eval.Judgment.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.saksham023.jobagent.eval.Judgment.Verdict.APPLY;
import static io.github.saksham023.jobagent.eval.Judgment.Verdict.MAYBE;
import static io.github.saksham023.jobagent.eval.Judgment.Verdict.NO;
import static org.assertj.core.api.Assertions.assertThat;

class EvalReportTest {

    private static JudgmentLine line(long id, Verdict verdict) {
        return new JudgmentLine(id, "Acme", "Job " + id, "dev", "opus", "v2", verdict, null, null, null,
                "reason " + id, 1000, 7000, 200, 0.05, null);
    }

    private static JudgmentLine failed(long id) {
        return new JudgmentLine(id, "Acme", "Job " + id, "dev", "opus", "v2", null, null, null, null, null, 10,
                0, 0, 0.0, "boom");
    }

    @Test
    void agreementTableAndDisagreements() {
        Map<Long, Verdict> human = Map.of(1L, APPLY, 2L, NO, 3L, NO, 4L, MAYBE);
        List<JudgmentLine> lines = List.of(line(1, APPLY), line(2, NO), line(3, MAYBE), line(4, MAYBE), line(5, NO));

        Report report = EvalReport.compare("run", lines, human);

        assertThat(report.judged()).isEqualTo(5);
        assertThat(report.compared()).isEqualTo(4);                   // job 5 has no human label
        assertThat(report.agreement()).isEqualTo(0.75);
        assertThat(report.applyAgreement()).isEqualTo(1.0);          // nobody disagrees on APPLY vs not
        assertThat(report.table().get(NO).get(MAYBE)).isEqualTo(1);
        assertThat(report.disagreements()).singleElement()
                .satisfies(d -> assertThat(d.jobId()).isEqualTo(3));
    }

    @Test
    void kappaIsOneForPerfectAgreementAndZeroForChance() {
        Report perfect = EvalReport.compare("run", List.of(line(1, APPLY), line(2, NO)), Map.of(1L, APPLY, 2L, NO));
        assertThat(perfect.kappa()).isEqualTo(1.0);

        // the model always says NO: it agrees on the NO jobs only by its own habit, not by judgment
        Report constant = EvalReport.compare("run", List.of(line(1, NO), line(2, NO), line(3, NO), line(4, NO)),
                Map.of(1L, NO, 2L, NO, 3L, APPLY, 4L, APPLY));
        assertThat(constant.agreement()).isEqualTo(0.5);
        assertThat(constant.kappa()).isEqualTo(0.0);
    }

    @Test
    void aRetriedJobCountsOnceWithItsLatestAnswer() {
        Report report = EvalReport.compare("run", List.of(failed(1), line(1, APPLY), failed(2)), Map.of(1L, APPLY));

        assertThat(report.judged()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);                     // job 2 never succeeded
        assertThat(report.agreement()).isEqualTo(1.0);
    }

    @Test
    void readsLabelsWhoseNotesContainCommas(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("labels.csv");
        Files.writeString(file, """
                job_id,label,note,company,title
                395,1,"revised by the user: asks 4-6 years, outside the default window",Paytm,Backend - TL/STL
                710,2,,ServiceNow,Software Engineer
                768,0,,ServiceNow,Advisory AI Foundry Architect
                """);

        assertThat(EvalReport.humanLabels(file)).containsExactlyInAnyOrderEntriesOf(Map.of(395L, MAYBE, 710L, APPLY, 768L, NO));
    }

    @Test
    void usageAddsUpEveryCallAndAveragesOverJudgedJobs() {
        Report report = EvalReport.compare("run", List.of(failed(1), line(1, APPLY), line(2, NO)), Map.of());

        assertThat(report.usage().calls()).isEqualTo(3);
        assertThat(report.usage().promptTokens()).isEqualTo(14_000);
        assertThat(report.usage().costUsd()).isEqualTo(0.1);
        assertThat(report.usage().promptTokensPerJob()).isEqualTo(7_000);
        assertThat(report.usage().secondsPerJob()).isEqualTo(1.0);
    }

    @Test
    void linesWrittenBeforeUsageWasRecordedStillLoadAndCountAsZero() {
        String oldLine = """
                {"jobId":107,"company":"Databricks","title":"Staff Software Engineer","profile":"saksham","model":"opus",\
                "rubric":"v2","verdict":"NO","roleFit":"STRETCH","experienceFit":"MISMATCH","stackFit":"STRETCH",\
                "reason":"Staff role","millis":10938,"error":null}""";

        JudgmentLine line = tools.jackson.databind.json.JsonMapper.builder().build().readValue(oldLine, JudgmentLine.class);

        assertThat(line.promptTokens()).isNull();
        assertThat(EvalReport.usage(List.of(line), 1).promptTokens()).isZero();
    }
}
