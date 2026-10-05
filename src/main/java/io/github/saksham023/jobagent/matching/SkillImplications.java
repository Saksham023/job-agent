package io.github.saksham023.jobagent.matching;

import io.github.saksham023.jobagent.common.CsvResource;
import io.github.saksham023.jobagent.requirements.SkillExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "If you have X, you also have Y": DynamoDB implies AWS (full credit), Distributed Systems is related to
 * Microservices (half credit). Read from classify/skill-implications.csv at startup. Applied to the candidate's
 * skills only, one hop, so every implied skill can be explained by one skill the candidate really listed.
 */
@Component
public class SkillImplications {

    private static final Logger log = LoggerFactory.getLogger(SkillImplications.class);

    /** One implied skill: how much it counts (0 to 1) and which listed skill it comes from. */
    public record Implied(String skill, double credit, String via) {
    }

    private record Rule(String implied, double credit) {
    }

    private final Map<String, List<Rule>> rulesBySkill = new HashMap<>();

    public SkillImplications(SkillExtractor skillExtractor) {
        String file = "classify/skill-implications.csv";                     // reads classify/skill-implications.csv
        int count = 0;
        for (String[] row : CsvResource.read(file, 3)) {
            String from = requireCanonical(skillExtractor, row[0], file);
            String to = requireCanonical(skillExtractor, row[1], file);
            double credit = Double.parseDouble(row[2]);
            if (credit <= 0 || credit > 1) {
                throw new IllegalStateException(file + ": credit must be in (0, 1]: " + String.join(",", row));
            }
            rulesBySkill.computeIfAbsent(from, k -> new ArrayList<>()).add(new Rule(to, credit));
            count++;
        }
        log.info("Skill implications loaded: {} rules", count);
    }

    /**
     * The skills implied by the candidate's own skills, minus the ones they already have. When two of their skills
     * imply the same one, the higher credit wins (the first listed skill on a tie).
     */
    public Map<String, Implied> expand(Set<String> skills) {
        Map<String, Implied> implied = new LinkedHashMap<>();
        for (String skill : skills) {
            for (Rule rule : rulesBySkill.getOrDefault(skill, List.of())) {
                if (skills.contains(rule.implied())) {
                    continue;                                                  // listed directly: full credit anyway
                }
                Implied current = implied.get(rule.implied());
                if (current == null || rule.credit() > current.credit()) {
                    implied.put(rule.implied(), new Implied(rule.implied(), rule.credit(), skill));
                }
            }
        }
        return Map.copyOf(implied);
    }

    /** The file must use the dictionary's canonical spelling, so a typo fails at startup instead of never matching. */
    private static String requireCanonical(SkillExtractor skillExtractor, String name, String file) {
        String canonical = skillExtractor.canonical(name)
                .orElseThrow(() -> new IllegalStateException(file + ": unknown skill " + name))
                .skill();
        if (!canonical.equals(name)) {
            throw new IllegalStateException(file + ": write " + canonical + " instead of " + name);
        }
        return canonical;
    }
}