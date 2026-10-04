package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.JobClassifier.Classification;
import io.github.saksham023.jobagent.requirements.JobClassifier.Specialization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real titles and departments from the stored jobs (2026-10-05). Loads the rules from classify/*.csv,
 * so these tests cover the data files as well as the voting logic.
 */
class JobClassifierTest {

    private static final JobClassifier classifier = new JobClassifier();

    private static Classification classify(String title, String department) {
        return classifier.classify(title, department, null, null);
    }

    // ---------------------------------------------------------------- title role words

    @Test
    void softwareEngineerTitles() {
        assertThat(classify("Staff Software Engineer - Backend", "Engineering - Pipeline").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(classify("Sr. Software Development Engineer", "Zero Trust Exchange").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(classify("SDE II", null).family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
    }

    @Test
    void specificEngineeringRolesBeatTheGenericWordEngineer() {
        assertThat(classify("Solutions Architect (Genie)", "Field Engineering - Other").family())
                .isEqualTo(JobFamily.SALES_ENGINEERING);
        assertThat(classify("Designated Support Engineer III", "Support").family()).isEqualTo(JobFamily.SUPPORT);
        assertThat(classify("Escalation Engineer - Cloud", "Support").family()).isEqualTo(JobFamily.SUPPORT);
        assertThat(classify("Senior Detection Engineer", null).family()).isEqualTo(JobFamily.SECURITY);
    }

    @Test
    void engineeringManagersAreTheirOwnFamily() {
        assertThat(classify("Engineering Manager, Backend - Lending", "Information Technology").family())
                .isEqualTo(JobFamily.ENG_MANAGEMENT);
        assertThat(classify("Senior Manager, Software Development Engineering", "Zero Trust Exchange").family())
                .isEqualTo(JobFamily.ENG_MANAGEMENT);
    }

    @Test
    void productMarketingAndProductSecurityAreNotConfused() {
        assertThat(classify("Product Manager II - AI", "Product Management").family()).isEqualTo(JobFamily.PRODUCT);
        assertThat(classify("Product Marketing Manager", null).family()).isEqualTo(JobFamily.MARKETING);
        assertThat(classify("Senior Manager, Product Security Testing", null).family()).isEqualTo(JobFamily.SECURITY);
    }

    @Test
    void salesAndHrTitlesThatMentionOtherFunctions() {
        assertThat(classify("Specialist Account Executive, Data Security", "Global Specialty Sales").family())
                .isEqualTo(JobFamily.SALES);
        assertThat(classify("HRBP - Lending Sales", "Human Resources").family()).isEqualTo(JobFamily.HR);
        assertThat(classify("Lead HR Compliance and Governance", "Human Resources").family()).isEqualTo(JobFamily.HR);
    }

    // ---------------------------------------------------------------- the TECH umbrella

    @Test
    void genericTechDepartmentSupportsTheTitlesTechnicalFamily() {
        Classification sre = classify("Site Reliability Engineer - AWS (4 to 8 Years)", "Information Technology");

        assertThat(sre.family()).isEqualTo(JobFamily.INFRA_DEVOPS);
        assertThat(sre.score()).isEqualTo(5);                                 // 3 title + 2 department, no tie
        assertThat(classify("Director Data Science", "Tech").family()).isEqualTo(JobFamily.DATA_ML);
    }

    @Test
    void techDepartmentAloneMeansSoftwareEngineering() {
        Classification analyst = classify("Analyst", "Engineering");          // e.g. a bank's entry-level title

        assertThat(analyst.family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(analyst.score()).isEqualTo(2);
    }

    @Test
    void techKeywordsInTheDescriptionAddWeakSupport() {
        Classification c = classifier.classify("Analyst", "Engineering", null,
                "- Build microservices in Java and Kafka\n- Strong data structures and system design");

        assertThat(c.family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(c.score()).isEqualTo(3);
        assertThat(c.reasons()).anyMatch(r -> r.startsWith("description keywords"));
    }

    // ---------------------------------------------------------------- departments and functions

    @Test
    void departmentAloneDecidesWhenTheTitleHasNoRoleWord() {
        assertThat(classify("Senior Technical Success Manager", "Customer Success Dept").family())
                .isEqualTo(JobFamily.SUPPORT);
    }

    @Test
    void functionIsUsedWhenTheDepartmentSaysNothing() {
        Classification c = classifier.classify("Associate", "Other", "Human Resources", null);

        assertThat(c.family()).isEqualTo(JobFamily.HR);
    }

    // ---------------------------------------------------------------- unclassified and ties

    @Test
    void vagueTitlesInVagueDepartmentsStayUnclassified() {
        Classification c = classify("Business Head - F&O", "Business");

        assertThat(c.family()).isEqualTo(JobFamily.UNCLASSIFIED);
        assertThat(c.reasons()).isEmpty();
    }

    @Test
    void aSingleWeakDescriptionVoteIsNotEnough() {
        Classification c = classifier.classify("Senior Manager - Production", "Growth", null,
                "Own campaigns, brand and content strategy with performance marketing teams.");

        assertThat(c.family()).isEqualTo(JobFamily.UNCLASSIFIED);              // 1 point < MIN_SCORE
    }

    // ---------------------------------------------------------------- specialization and groups

    @Test
    void specializationOnlyForTechFamilies() {
        assertThat(classify("Staff Software Engineer - Backend", "Engineering").specialization())
                .isEqualTo(Specialization.BACKEND);
        assertThat(classify("Frontend Engineer, Chanakya", "Engineering").specialization())
                .isEqualTo(Specialization.FRONTEND);
        assertThat(classify("Site Reliability Engineer - On-Prem", null).specialization())
                .isEqualTo(Specialization.SRE);
        assertThat(classify("Account Executive - Platform", "Sales").specialization()).isNull();
    }

    @Test
    void familyGroups() {
        assertThat(JobFamily.INFRA_DEVOPS.isTech()).isTrue();
        assertThat(JobFamily.SALES_ENGINEERING.group()).isEqualTo(JobFamily.Group.TECH_ADJACENT);
        assertThat(JobFamily.FINANCE.group()).isEqualTo(JobFamily.Group.BUSINESS);
    }
}