package io.github.saksham023.jobagent.eval;

import io.github.saksham023.jobagent.eval.Judgment.ExperienceFit;
import io.github.saksham023.jobagent.eval.Judgment.RoleFit;
import io.github.saksham023.jobagent.eval.Judgment.StackFit;
import io.github.saksham023.jobagent.eval.Judgment.Verdict;

/**
 * One line of a run's judgments.jsonl: which job, which judge (model + rubric) and its answer, or the error.
 * Lines are appended as soon as a job is judged, so a run can be watched with `tail -f` and resumed after a stop.
 * Token and cost fields are boxed: lines written before they existed read them as null (= not recorded).
 */
public record JudgmentLine(long jobId, String company, String title, String profile, String model, String rubric,
                           Verdict verdict, RoleFit roleFit, ExperienceFit experienceFit, StackFit stackFit,
                           String reason, long millis, Integer promptTokens, Integer completionTokens, Double costUsd,
                           String error) {

    static JudgmentLine of(long jobId, String company, String title, String profile, String model, String rubric,
                           JobJudge.Result result, long millis) {
        Judgment judgment = result.judgment();
        return new JudgmentLine(jobId, company, title, profile, model, rubric, judgment.verdict(), judgment.roleFit(),
                judgment.experienceFit(), judgment.stackFit(), judgment.reason(), millis, result.promptTokens(),
                result.completionTokens(), result.costUsd(), null);
    }

    static JudgmentLine failed(long jobId, String company, String title, String profile, String model, String rubric,
                               String error, long millis) {
        return new JudgmentLine(jobId, company, title, profile, model, rubric, null, null, null, null, null, millis,
                0, 0, 0.0, error);
    }

    boolean succeeded() {
        return verdict != null;
    }

    long promptTokensOrZero() {
        return promptTokens == null ? 0 : promptTokens;
    }

    long completionTokensOrZero() {
        return completionTokens == null ? 0 : completionTokens;
    }

    double costUsdOrZero() {
        return costUsd == null ? 0 : costUsd;
    }
}