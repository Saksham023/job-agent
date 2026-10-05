package io.github.saksham023.jobagent.eval;

import io.github.saksham023.jobagent.eval.Judgment.Verdict;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a judge run with the human labels in eval/labels.csv (2 = would apply, 1 = worth a look, 0 = not for
 * me, read as APPLY / MAYBE / NO): how often they agree, Cohen's kappa (agreement corrected for chance), a
 * human-by-model table, and every disagreement with the model's reason so it can be reviewed.
 */
@Component
public class EvalReport {

    /** One job where the model and the human differ. */
    public record Disagreement(long jobId, String company, String title, Verdict human, Verdict model, String reason) {
    }

    /**
     * @param compared   jobs that have both a human label and a model verdict
     * @param agreement  share of compared jobs with the same verdict, 0 to 1
     * @param kappa      Cohen's kappa: 1 = perfect, 0 = no better than chance
     * @param applyAgreement share that agree on the yes/no question "APPLY or not"
     * @param table      human verdict -> model verdict -> count
     * @param usage      what the run cost, over every call including failed and retried ones
     */
    public record Report(String runId, int judged, int failed, int compared, double agreement, double kappa,
                         double applyAgreement, Map<Verdict, Map<Verdict, Integer>> table,
                         Map<Verdict, Integer> modelVerdicts, RunUsage usage, List<Disagreement> disagreements) {
    }

    /** Totals and per-judged-job averages, to estimate what a bigger run would take. */
    public record RunUsage(int calls, long promptTokens, long completionTokens, double costUsd,
                           long promptTokensPerJob, long completionTokensPerJob, double costUsdPerJob,
                           double secondsPerJob) {
    }

    private final JudgeRunner runner;
    private final EvalProperties eval;

    public EvalReport(JudgeRunner runner, EvalProperties eval) {
        this.runner = runner;
        this.eval = eval;
    }

    public Report report(String runId) {
        List<JudgmentLine> lines = runner.lines(runId);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("No judgments for run " + runId);
        }
        return compare(runId, lines, humanLabels(eval.path("labels.csv")));
    }

    /** The comparison itself, without files: package-private for tests. */
    static Report compare(String runId, List<JudgmentLine> lines, Map<Long, Verdict> human) {
        Map<Long, JudgmentLine> latest = new LinkedHashMap<>();
        for (JudgmentLine line : lines) {
            if (line.succeeded()) {
                latest.put(line.jobId(), line);                    // a retried job: the last answer counts
            }
        }
        int failed = (int) lines.stream().filter(l -> !l.succeeded() && !latest.containsKey(l.jobId())).count();

        Map<Verdict, Map<Verdict, Integer>> table = new EnumMap<>(Verdict.class);
        for (Verdict h : Verdict.values()) {
            Map<Verdict, Integer> row = new EnumMap<>(Verdict.class);
            for (Verdict m : Verdict.values()) {
                row.put(m, 0);
            }
            table.put(h, row);
        }
        Map<Verdict, Integer> modelVerdicts = new EnumMap<>(Verdict.class);
        List<Disagreement> disagreements = new java.util.ArrayList<>();
        int compared = 0, same = 0, sameApply = 0;
        for (JudgmentLine line : latest.values()) {
            modelVerdicts.merge(line.verdict(), 1, Integer::sum);
            Verdict h = human.get(line.jobId());
            if (h == null) {
                continue;
            }
            compared++;
            table.get(h).merge(line.verdict(), 1, Integer::sum);
            if (h == line.verdict()) {
                same++;
            } else {
                disagreements.add(new Disagreement(line.jobId(), line.company(), line.title(), h, line.verdict(), line.reason()));
            }
            if ((h == Verdict.APPLY) == (line.verdict() == Verdict.APPLY)) {
                sameApply++;
            }
        }
        double agreement = compared == 0 ? 0 : (double) same / compared;
        double applyAgreement = compared == 0 ? 0 : (double) sameApply / compared;
        return new Report(runId, latest.size(), failed, compared, round(agreement), round(kappa(table, compared)),
                round(applyAgreement), table, modelVerdicts, usage(lines, latest.size()), disagreements);
    }

    static RunUsage usage(List<JudgmentLine> lines, int judged) {
        long prompt = lines.stream().mapToLong(JudgmentLine::promptTokensOrZero).sum();
        long completion = lines.stream().mapToLong(JudgmentLine::completionTokensOrZero).sum();
        double cost = lines.stream().mapToDouble(JudgmentLine::costUsdOrZero).sum();
        double seconds = lines.stream().filter(JudgmentLine::succeeded).mapToLong(JudgmentLine::millis).sum() / 1000.0;
        int per = Math.max(judged, 1);
        return new RunUsage(lines.size(), prompt, completion, round(cost), prompt / per, completion / per,
                Math.round(cost / per * 10000) / 10000.0, Math.round(seconds / per * 10) / 10.0);
    }

    /** Cohen's kappa = (observed - expected) / (1 - expected), expected from the two raters' own label mixes. */
    static double kappa(Map<Verdict, Map<Verdict, Integer>> table, int n) {
        if (n == 0) {
            return 0;
        }
        double observed = 0, expected = 0;
        for (Verdict v : Verdict.values()) {
            observed += table.get(v).get(v);
            int humanTotal = table.get(v).values().stream().mapToInt(Integer::intValue).sum();
            int modelTotal = table.values().stream().mapToInt(row -> row.get(v)).sum();
            expected += (double) humanTotal * modelTotal / n;
        }
        observed /= n;
        expected /= n;
        return expected == 1 ? 1 : (observed - expected) / (1 - expected);
    }

    /** job_id,label,... rows; only the first two columns are read (the note may contain commas). */
    static Map<Long, Verdict> humanLabels(Path file) {
        Map<Long, Verdict> labels = new HashMap<>();
        try {
            List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String row : rows.subList(1, rows.size())) {
                String[] fields = row.split(",", 3);
                if (fields.length >= 2 && !fields[0].isBlank()) {
                    labels.put(Long.parseLong(fields[0].strip()), switch (fields[1].strip()) {
                        case "2" -> Verdict.APPLY;
                        case "1" -> Verdict.MAYBE;
                        case "0" -> Verdict.NO;
                        default -> throw new IllegalArgumentException(file + ": label must be 0, 1 or 2: " + row);
                    });
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        return labels;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}