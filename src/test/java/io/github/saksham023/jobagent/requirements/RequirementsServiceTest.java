package io.github.saksham023.jobagent.requirements;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The employment-type normalizer, using the raw values the four platforms actually send.
 */
class RequirementsServiceTest {

    @Test
    void platformSpellingsOfFullTime() {
        assertThat(RequirementsService.employmentType("Full-time Employment", "SDE II")).isEqualTo("FULL_TIME");
        assertThat(RequirementsService.employmentType("FullTime", "SDE II")).isEqualTo("FULL_TIME");
        assertThat(RequirementsService.employmentType("Full Time Employee", "SDE II")).isEqualTo("FULL_TIME");
        assertThat(RequirementsService.employmentType("On-roll", "Area Sales Manager")).isEqualTo("FULL_TIME");
    }

    @Test
    void internAndContractAlsoComeFromTheTitle() {
        assertThat(RequirementsService.employmentType("Intern", "Marketing")).isEqualTo("INTERN");
        assertThat(RequirementsService.employmentType(null, "Video Editor Intern")).isEqualTo("INTERN");
        assertThat(RequirementsService.employmentType("Full-time", "Sr. Recruiter, GTM (Contract)")).isEqualTo("CONTRACT");
    }

    @Test
    void unknownStaysNull() {
        assertThat(RequirementsService.employmentType(null, "Software Engineer")).isNull();
    }
}
