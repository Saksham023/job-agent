package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.RequirementsRepository.Coverage;
import io.github.saksham023.jobagent.requirements.RequirementsService.RebuildResult;
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

    public RequirementsController(RequirementsService requirementsService, RequirementsRepository requirementsRepository) {
        this.requirementsService = requirementsService;
        this.requirementsRepository = requirementsRepository;
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
}