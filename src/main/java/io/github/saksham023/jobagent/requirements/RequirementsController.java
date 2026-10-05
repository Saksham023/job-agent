package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.GapFillRunner.RunStatus;
import io.github.saksham023.jobagent.requirements.RequirementsRepository.Coverage;
import io.github.saksham023.jobagent.requirements.RequirementsService.RebuildResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for requirement extraction. Local use only (no authentication).
 */
@RestController
@RequestMapping("/admin/requirements")
public class RequirementsController {

    private final RequirementsService requirementsService;
    private final RequirementsRepository requirementsRepository;
    private final GapFillRunner gapFillRunner;

    public RequirementsController(RequirementsService requirementsService, RequirementsRepository requirementsRepository,
                                  GapFillRunner gapFillRunner) {
        this.requirementsService = requirementsService;
        this.requirementsRepository = requirementsRepository;
        this.gapFillRunner = gapFillRunner;
    }

    /** Extracts requirements for new / changed / outdated jobs, or for every open job with all=true. */
    @PostMapping("/rebuild")
    public RebuildResult rebuild(@RequestParam(defaultValue = "false") boolean all) {
        return requirementsService.rebuild(all);
    }

    /** How much the extractors found: families, experience confidence, skills, top unclassified titles. */
    @GetMapping("/coverage")
    public Coverage coverage() {
        return requirementsRepository.coverage();
    }

    /**
     * Starts filling the rules' gaps with a model in the background (one call per job, tech jobs only):
     * POST /admin/requirements/fill-gaps?limit=15&parallelism=3. Re-running continues with the jobs not yet asked.
     */
    @PostMapping("/fill-gaps")
    public ResponseEntity<RunStatus> fillGaps(@RequestParam(defaultValue = "opus") String model,
                                              @RequestParam(required = false) Integer limit,
                                              @RequestParam(defaultValue = "1") int parallelism) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(gapFillRunner.start(model, limit, parallelism));
    }

    @GetMapping("/fill-gaps/status")
    public ResponseEntity<RunStatus> fillGapsStatus() {
        return gapFillRunner.status().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }
}
