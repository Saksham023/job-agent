package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.JobClassifier.Classification;
import io.github.saksham023.jobagent.requirements.JobClassifier.Specialization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void quantResearchAndTrading() {
        assertThat(classify("Quantitative Researcher (2027 Graduate)", "Quantitative Trading").family()).isEqualTo(JobFamily.QUANT);
        assertThat(classify("Senior Quantitative Trader", "Quantitative Trading").family()).isEqualTo(JobFamily.QUANT);
        assertThat(classify("Trader - Gift City Securities PLC Branch - Vice President", null).family()).isEqualTo(JobFamily.QUANT);
        assertThat(classify("Markets- Commodities Systematic Trading-Associate-Mumbai", null).family()).isEqualTo(JobFamily.QUANT);
        // boundaries: model risk is risk work, quant developers are software engineers (with QUANT as a secondary)
        assertThat(classify("Quant Model Risk Analyst", null).family()).isEqualTo(JobFamily.RISK_COMPLIANCE);
        Classification developer = classify("Quantitative Developer", "Technology");
        assertThat(developer.family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(developer.secondaryFamilies()).contains(JobFamily.QUANT);
        assertThat(classify("Senior Infrastructure Engineer - Trader Voice (Unigy-Cloud9)", null).family()).isEqualTo(JobFamily.INFRA_DEVOPS);
        Classification backend = classify("Java Backend Engineer (Quant Analytics), Vice President", null);
        assertThat(backend.family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(backend.secondaryFamilies()).contains(JobFamily.QUANT);
        assertThat(classify("GBM - STS AI Structuring - Sales Strats - Analyst/Associate - Bengaluru", null).family()).isEqualTo(JobFamily.QUANT);
        assertThat(classify("Sr Solutions Architect, AWS Industries India - Strat Acc.", null).secondaryFamilies()).doesNotContain(JobFamily.QUANT);
    }

    @Test
    void chipDesignIsHardwareNotSoftware() {
        assertThat(classify("ASIC Design and STA Engineer", "Engineering").family()).isEqualTo(JobFamily.HARDWARE_ENGINEERING);
        assertThat(classify("Senior Verification Engineer, PCIE", "Engineering").family()).isEqualTo(JobFamily.HARDWARE_ENGINEERING);
        assertThat(classify("SRAM QA Engineer", "R&D (JFG)").family()).isEqualTo(JobFamily.HARDWARE_ENGINEERING);
        assertThat(classify("ASIC Methodology, Flow and Integration Engineer", "Engineering").family())
                .isEqualTo(JobFamily.HARDWARE_ENGINEERING);
        assertThat(classify("Memory Layout Manager", "Silicon Hardware Engineering").family())
                .isEqualTo(JobFamily.HARDWARE_ENGINEERING);
    }

    @Test
    void softwareJobsThatMentionHardwareStaySoftware() {
        assertThat(classify("Senior System Software Engineer - GPU and SOC", "Engineering").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(classify("Software Verification Engineer", "Engineering").family())
                .isNotEqualTo(JobFamily.HARDWARE_ENGINEERING);
        assertThat(classify("Sr. SW Engineer - Software & Hardware QA Automation", "Engineering & Technology").family())
                .isNotEqualTo(JobFamily.HARDWARE_ENGINEERING);
    }

    @Test
    void hardwareIsNotATechFamilyAndGetsNoSoftwareFromTheDepartment() {
        Classification asic = classify("ASIC Verification Engineer", "Engineering");
        assertThat(JobFamily.HARDWARE_ENGINEERING.isTech()).isFalse();          // not in default software searches
        assertThat(asic.secondaryFamilies()).doesNotContain(JobFamily.SOFTWARE_ENGINEERING);
    }

    @Test
    void aGenericEngineerTitleFollowsASpecificTechnicalDepartment() {
        assertThat(classify("Synthesis Engineer, Sr Lead", "Hardware Engineering").family())
                .isEqualTo(JobFamily.HARDWARE_ENGINEERING);
        assertThat(classify("NPU Synthesis Engineer, Principal", "ASICS Engineering").family())
                .isEqualTo(JobFamily.HARDWARE_ENGINEERING);

        Classification infra = classify("Performance Engineer, Kernels", "Infrastructure");
        assertThat(infra.family()).isEqualTo(JobFamily.INFRA_DEVOPS);
        assertThat(infra.secondaryFamilies()).contains(JobFamily.SOFTWARE_ENGINEERING);   // still found by software searches
    }

    @Test
    void aFamilyFromTheCatchAllTitleAloneIsMarkedAsGuessed() {
        assertThat(classify("Senior Lead Engineer", "Engineering").familyGuessed()).isTrue();
        assertThat(classify("Staff Software Engineer - Backend", "Engineering").familyGuessed()).isFalse();
        assertThat(classify("Synthesis Engineer, Sr Lead", "Hardware Engineering").familyGuessed()).isFalse();
        assertThat(classify("Account Executive", "Sales").familyGuessed()).isFalse();
    }

    @Test
    void aBusinessDepartmentDoesNotOverruleAnEngineerTitle() {
        assertThat(classify("Senior Lead Engineer", "Customer Success").family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
    }

    @Test
    void technicalConsultingIsSalesEngineering() {
        assertThat(classify("Manager, Technical Consulting-Health Domain", "Customer Success").family())
                .isEqualTo(JobFamily.SALES_ENGINEERING);
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

    // ---------------------------------------------------------------- secondary families (recall first)

    /** The family filter matches primary OR secondary families, so this is what a software engineering search finds. */
    private static boolean foundBySoftwareSearch(Classification c) {
        return c.family() == JobFamily.SOFTWARE_ENGINEERING || c.secondaryFamilies().contains(JobFamily.SOFTWARE_ENGINEERING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Sr. Software Development Engineer",
            "Staff Software Development Engineer - AI Engineer",
            "AI Engineer - FDE (Forward Deployed Engineer)",
            "Senior Software AIML Engineer",
            "Agent Engineer",
            "IT Software Engineer, Infrastructure",
            "Software Engineer - SRE (Rust)",
            "Sr Software Engineer - Kubernetes",
            "Staff Software Engineer - Machine Learning (Search)",
            "Strategic Deployment Engineer, Chanakya",
            "FDE - Enterprise AI"})
    void softwareRolesAreNeverHiddenFromASoftwareSearch(String title) {
        assertThat(foundBySoftwareSearch(classify(title, "Engineering"))).as(title).isTrue();
    }

    @Test
    void aiEngineeringTitlesKeepBothFamilies() {
        Classification c = classify("Staff Software Development Engineer - AI Engineer", "Product Management");

        assertThat(c.family()).isEqualTo(JobFamily.DATA_ML);
        assertThat(c.secondaryFamilies()).containsExactly(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(c.specialization()).isEqualTo(Specialization.AI_ENGINEERING);
        assertThat(c.reasons()).contains("title 'Software Development' -> also SOFTWARE_ENGINEERING");
    }

    @Test
    void solutionsWorkWithEngineeringContentAlsoCountsAsSoftwareEngineering() {
        Classification c = classifier.classify("Solutions Engineer", "Field Engineering", null,
                "You will build integrations in Python and Java, deploy on Kubernetes and Docker, and design REST APIs.");

        assertThat(c.family()).isEqualTo(JobFamily.SALES_ENGINEERING);
        assertThat(c.secondaryFamilies()).containsExactly(JobFamily.SOFTWARE_ENGINEERING);
    }

    @Test
    void deploymentEngineersArePostSaleEngineeringNotSalesEngineering() {
        assertThat(classify("Strategic Deployment Engineer, Chanakya", "Deployment Engineering").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(classify("Forward Deployed Engineer", "Professional Services").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(classify("FDE - Enterprise AI", "Professional Services").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(classify("Senior Solutions Engineer", "Field Engineering").family())
                .isEqualTo(JobFamily.SALES_ENGINEERING);                 // pre-sale
    }

    @Test
    void anInfrastructureHeavyDescriptionAddsInfraWithoutChangingThePrimary() {
        Classification c = classifier.classify("Strategic Deployment Engineer, Chanakya", "Deployment Engineering", null,
                "Own deployments on-prem and in air-gapped environments. Manage deployment pipelines and model serving. "
                        + "Production experience in Python, Docker and Linux systems administration. Own uptime.");

        assertThat(c.family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(c.secondaryFamilies()).containsExactly(JobFamily.INFRA_DEVOPS);
    }

    @Test
    void businessProductAndDesignJobsGetNoSecondaryFamilies() {
        assertThat(classify("Product Manager II - AI", "Engineering").secondaryFamilies()).isEmpty();
        assertThat(classify("Specialist Account Executive, Data Security", "Global Specialty Sales").secondaryFamilies())
                .isEmpty();
    }

    @Test
    void itSupportAndToolAdministratorsAreNotSoftwareEngineering() {
        assertThat(classify("Executive - IT Support", "Corp IT").family()).isEqualTo(JobFamily.SUPPORT);
        assertThat(classify("Jira Administrator (Migration/Scripting/Integration)", "Engineering Operations").family())
                .isEqualTo(JobFamily.INFRA_DEVOPS);
        assertThat(classify("Senior LMS Administrator", "Product Management").family()).isEqualTo(JobFamily.INFRA_DEVOPS);
        assertThat(classify("IT Lead", "Information Technology").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);           // ambiguous: kept, the ranking decides
    }

    @Test
    void serviceNowsEngineeringOrgIsNotAnInfrastructureSignal() {
        Classification c = classify("Staff Software Engineer", "Engineering, Infrastructure and Operations");

        assertThat(c.family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(c.secondaryFamilies()).isEmpty();
    }

    @Test
    void researchersBuildModelsEvenWhenTheTitleMentionsAgents() {
        assertThat(classify("Senior Research Scientist, Agent Evaluation", null).specialization())
                .isEqualTo(Specialization.ML_AI);
        assertThat(classify("Principal Software Development Engineer - Agentic Systems", null).specialization())
                .isEqualTo(Specialization.AI_ENGINEERING);
    }

    @Test
    void theRolePartBeforeTheCommaDecidesNotTheTeamName() {
        // Amazon names the team after the comma: its words must not decide the family
        assertThat(classify("Software Development Engineer II, Sales Abuse Prevention", "Software Development").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(classify("SDE II - Multimedia, Hardware Compute Group", "Software Development").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        // no rule in the role part: the whole title counts
        assertThat(classify("Manager, Software Engineering", null).family()).isEqualTo(JobFamily.ENG_MANAGEMENT);
        // the rest of the title still adds secondary families
        assertThat(classify("AI Engineer, FDE (Forward Deployed Engineer)", null).secondaryFamilies())
                .contains(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(JobClassifier.rolePart("BIE II, Amazon Now")).isEqualTo("BIE II");
        assertThat(JobClassifier.rolePart("Backend Engineer")).isNull();
    }

    @Test
    void amazonRoleWords() {
        assertThat(classify("Manager III, Software Dev, TITANS", null).family()).isEqualTo(JobFamily.ENG_MANAGEMENT);
        assertThat(classify("SDM III,  Selling Partner Services Tech", null).family()).isEqualTo(JobFamily.ENG_MANAGEMENT);
        assertThat(classify("Software Dev Manager III, Amazon Pharmacy", null).family()).isEqualTo(JobFamily.ENG_MANAGEMENT);
        Classification sysDe = classify("System Development Engineer II, Customer Experience Infrastructure", null);
        assertThat(sysDe.family()).isEqualTo(JobFamily.INFRA_DEVOPS);
        assertThat(sysDe.secondaryFamilies()).contains(JobFamily.SOFTWARE_ENGINEERING);
        assertThat(classify("Network Development Engineer, Capacity Restoration Team", null).family())
                .isEqualTo(JobFamily.INFRA_DEVOPS);
        assertThat(classify("BIE II, Amazon Now Quick Commerce", null).family()).isEqualTo(JobFamily.ANALYTICS);
        assertThat(classify("Business Intel Engineer I, FinOps", null).family()).isEqualTo(JobFamily.ANALYTICS);
        assertThat(classify("SDE2, Amazon", null).family()).isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
        // a hardware "development engineer" gets no software secondary
        assertThat(classify("PERC Rule Deck Development Engineer", "Engineering").secondaryFamilies())
                .doesNotContain(JobFamily.SOFTWARE_ENGINEERING);
    }

    @Test
    void chipTestAndDesignRolesAreHardware() {
        for (String title : new String[]{"Validation/Characterization Engineer", "ESD Lead", "Senior Digital PD Engineer",
                "Timing closure expert", "RF Device Modeling Engineer", "AV&V Manager"}) {
            assertThat(classify(title, "Engineering - Product Dev").family()).as(title).isEqualTo(JobFamily.HARDWARE_ENGINEERING);
        }
        assertThat(classify("Software Validation Engineer", null).family()).isNotEqualTo(JobFamily.HARDWARE_ENGINEERING);
    }

    @Test
    void repeatedSpacesInTitlesDoNotBreakRules() {
        assertThat(classify("Principal Software  Architect – Logistics Systems", "Information Technology").family())
                .isEqualTo(JobFamily.SOFTWARE_ENGINEERING);
    }
}
