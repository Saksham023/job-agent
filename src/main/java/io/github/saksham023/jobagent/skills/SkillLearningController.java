package io.github.saksham023.jobagent.skills;

import io.github.saksham023.jobagent.skills.SkillLearningService.LearnReport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /admin/skills/learn: one whole run of the self-learning skill dictionary (mine, Opus review with checks,
 * apply), the same as the daily scheduled run. Local admin use only; takes a few minutes; costs ~$1 per 250 new words.
 */
@RestController
@RequestMapping("/admin/skills")
public class SkillLearningController {

    private final SkillLearningService service;

    public SkillLearningController(SkillLearningService service) {
        this.service = service;
    }

    @PostMapping("/learn")
    public LearnReport learn() {
        return service.learn();
    }
}
