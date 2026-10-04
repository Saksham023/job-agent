package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.SkillExtractor.Skills;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real phrasings from the stored job descriptions (2026-10-05). Loads classify/skills.csv, so these tests
 * cover the dictionary as well as the matching rules.
 */
class SkillExtractorTest {

    private static final SkillExtractor extractor = new SkillExtractor();

    private static Skills skills(String description) {
        return extractor.extract("Software Engineer", description, "Acme");
    }

    // ---------------------------------------------------------------- dictionary and aliases

    @Test
    void aliasesBecomeCanonicalNames() {
        assertThat(skills("- Experience with k8s, Postgres and Golang").required())
                .containsExactly("Kubernetes", "PostgreSQL", "Go");
    }

    @Test
    void javaIsNotJavaScript() {
        assertThat(skills("- Strong JavaScript and TypeScript skills").required())
                .contains("JavaScript", "TypeScript")
                .doesNotContain("Java");
    }

    @Test
    void symbolsInNamesAreMatched() {
        assertThat(skills("- Proficiency in C++ or C# and CI/CD pipelines").required())
                .contains("C++", "C#", "CI/CD");
    }

    // ---------------------------------------------------------------- ambiguous words

    @Test
    void goCountsOnlyNextToOtherSkills() {
        assertThat(skills("- Scripting proficiency in Python, Bash, or Go for automation.").required()).contains("Go");
        assertThat(skills("- Build value propositions and repeatable go-to-market plays.").required()).doesNotContain("Go");
        assertThat(skills("- Shape where our AI roadmap needs to go next, working with Python teams.").required())
                .doesNotContain("Go");
    }

    @Test
    void ambiguousNameAfterInWithOrUsingCounts() {
        assertThat(skills("- Strong experience in Go").required()).containsExactly("Go");
        assertThat(skills("- Proficient with Excel for reporting").required()).containsExactly("Excel");
    }

    @Test
    void excelTheVerbIsNotExcelTheTool() {
        assertThat(skills("- You excel in a fast-paced environment working with SQL dashboards.").required())
                .doesNotContain("Excel");
        assertThat(skills("- Advanced Excel and SQL skills").required()).contains("Excel");
    }

    // ---------------------------------------------------------------- required vs preferred vs ignored

    @Test
    void preferredSectionSkillsArePreferred() {
        Skills s = skills("""
                Requirements
                - 5+ years building backend systems in Java; experience with Kafka and Postgres
                Nice to have
                - Kubernetes and Terraform""");

        assertThat(s.required()).containsExactly("Java", "Kafka", "PostgreSQL");
        assertThat(s.preferred()).containsExactly("Kubernetes", "Terraform");
    }

    @Test
    void aPlusInTheSameSentenceMakesItPreferred() {
        Skills s = skills("- Strong Python skills. Experience with Kafka is a plus.");

        assertThat(s.required()).containsExactly("Python");
        assertThat(s.preferred()).containsExactly("Kafka");
    }

    @Test
    void requiredWinsWhenASkillIsInBothPlaces() {
        Skills s = skills("""
                - Build services in Go
                Preferred
                - Go and Rust""");

        assertThat(s.required()).contains("Go");
        assertThat(s.preferred()).containsExactly("Rust");
    }

    @Test
    void companyIntroSectionsAreIgnored() {
        Skills s = skills("""
                About Us
                - Our platform runs on Scala, Spark and AWS at massive scale.
                What You Will Do
                - Build APIs in Java""");

        assertThat(s.required()).containsExactly("Java");
    }

    @Test
    void indianProfileHeadingMeansRequired() {
        Skills s = skills("""
                Preferred candidate profile
                - Hands-on with Java and Spring Boot""");

        assertThat(s.required()).containsExactly("Java", "Spring Boot");
        assertThat(s.preferred()).isEmpty();
    }

    @Test
    void theHiringCompanysOwnProductIsNotASkill() {
        Skills s = extractor.extract("Senior Software Engineer", "- Build the Databricks platform with Scala and Spark", "databricks");

        assertThat(s.required()).containsExactly("Scala", "Apache Spark");
    }

    // ---------------------------------------------------------------- primary languages

    @Test
    void languageInTheTitleIsPrimary() {
        Skills s = extractor.extract("Principal Software Development Engineer - Rust",
                "- Experience with Python tooling and Kubernetes", "zscaler");

        assertThat(s.primaryLanguages()).containsExactly("Rust");
        assertThat(s.required()).contains("Rust", "Python");
    }

    @Test
    void requiredLanguagesArePrimaryAndSqlIsNot() {
        Skills s = skills("- Strong SQL skills and experience writing Python and Scala pipelines");

        assertThat(s.primaryLanguages()).containsExactly("Python", "Scala");
        assertThat(s.required()).contains("SQL");
    }

    @Test
    void preferredLanguagesWhenNoneAreRequired() {
        Skills s = skills("""
                - Lead the data platform roadmap
                Nice to have
                - Hands-on experience with Python, Java or Scala""");

        assertThat(s.primaryLanguages()).containsExactly("Python", "Java", "Scala");
    }

    @Test
    void nothingFoundIsEmptyNotNull() {
        Skills s = skills("- Define platform vision and strategy aligned to business outcomes.");

        assertThat(s.required()).isEmpty();
        assertThat(s.preferred()).isEmpty();
        assertThat(s.primaryLanguages()).isEmpty();
        assertThat(extractor.extract(null, null, null).required()).isEmpty();
    }

    // ---------------------------------------------------------------- slashes separate skills

    @Test
    void skillsAfterASlashCount() {
        assertThat(skills("- Strong Python/Java and TS/JS skills").required()).contains("Python", "Java", "JavaScript");
        assertThat(extractor.extract("Staff Software Development Engineer - Java/Go", null, "zscaler").primaryLanguages())
                .containsExactly("Java", "Go");
    }

    // ---------------------------------------------------------------- one name as a person types it (profiles)

    @Test
    void canonicalNameForATypedSkill() {
        assertThat(extractor.canonical("k8s")).map(SkillExtractor.SkillName::skill).hasValue("Kubernetes");
        assertThat(extractor.canonical("  postgres ")).map(SkillExtractor.SkillName::skill).hasValue("PostgreSQL");
        assertThat(extractor.canonical("go")).map(SkillExtractor.SkillName::category).hasValue(SkillExtractor.Category.LANGUAGE);
        assertThat(extractor.canonical("Spring Boot")).map(SkillExtractor.SkillName::skill).hasValue("Spring Boot");
        assertThat(extractor.canonical("Underwater basket weaving")).isEmpty();
    }
}
