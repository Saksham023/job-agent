package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.JobClassifier.Specialization;

import java.util.List;

/**
 * The model's answer for one job, in the shape of classify/gap-filler-prompt.md. The model always answers every
 * field (one call per job); GapFiller then uses only the fields the rules left empty, after checking them.
 * Null means "not stated" for the years and evidence fields and "none fits" for specialization.
 */
public record GapAnswer(boolean yearsStated, Integer minYears, Integer maxYears, String yearsEvidence,
                        JobFamily family, Specialization specialization, String familyReason,
                        List<String> mainLanguages, String languagesEvidence) {
}
