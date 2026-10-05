package io.github.saksham023.jobagent.requirements;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Gap-fill settings from application.yaml (jobagent.gap-fill.*).
 *
 * @param families the job families whose gaps a model may fill (a job counts when its family or one of its
 *                 secondary families is listed). Every job is still crawled and extracted by the rules; jobs in other
 *                 families just keep their gaps, so no model calls are spent on them. Add UNCLASSIFIED to let the
 *                 model classify jobs the rules could not place.
 */
@ConfigurationProperties("jobagent.gap-fill")
public record GapFillProperties(List<JobFamily> families) {

    static final List<JobFamily> DEFAULT_FAMILIES = List.of(JobFamily.SOFTWARE_ENGINEERING, JobFamily.DATA_ML,
            JobFamily.INFRA_DEVOPS, JobFamily.UNCLASSIFIED);

    public GapFillProperties {
        families = families == null || families.isEmpty() ? DEFAULT_FAMILIES : List.copyOf(families);
    }
}
