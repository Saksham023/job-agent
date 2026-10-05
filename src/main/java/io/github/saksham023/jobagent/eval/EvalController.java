package io.github.saksham023.jobagent.eval;

import io.github.saksham023.jobagent.eval.EvalReport.Report;
import io.github.saksham023.jobagent.eval.JudgeRunner.RunStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Evaluation endpoints. Local use only (no authentication), like the other /admin endpoints.
 *
 * POST /admin/eval/judge?profile=saksham&model=opus&limit=10&parallelism=1  starts a judge run in the background
 * GET  /admin/eval/judge/status                                              progress of the current run
 * GET  /admin/eval/report?run=2026-10-05-opus-saksham                         the run compared with eval/labels.csv
 */
@RestController
@RequestMapping("/admin/eval")
public class EvalController {

    private final JudgeRunner runner;
    private final EvalReport report;

    public EvalController(JudgeRunner runner, EvalReport report) {
        this.runner = runner;
        this.report = report;
    }

    @PostMapping("/judge")
    public ResponseEntity<RunStatus> judge(@RequestParam String profile,
                                           @RequestParam(defaultValue = "opus") String model,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(defaultValue = "1") int parallelism) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(runner.start(profile, model, limit, parallelism));
    }

    @GetMapping("/judge/status")
    public ResponseEntity<RunStatus> status() {
        return runner.status().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/report")
    public Report report(@RequestParam String run) {
        return report.report(run);
    }
}