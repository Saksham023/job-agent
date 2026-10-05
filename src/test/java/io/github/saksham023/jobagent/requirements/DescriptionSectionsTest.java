package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.DescriptionSections.Kind;
import io.github.saksham023.jobagent.requirements.DescriptionSections.Line;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section detection shared by the experience and skill extractors. Headings are taken from real postings.
 */
class DescriptionSectionsTest {

    @Test
    void headingsSwitchTheSectionOfTheLinesBelowThem() {
        List<Line> lines = DescriptionSections.lines("""
                We build payments infrastructure.
                About Us
                - Founded in 2014
                What You Will Do
                - Build APIs
                Nice to have
                - Kafka
                Equal Opportunity Employer
                - We celebrate diversity""");

        assertThat(lines).extracting(Line::section).containsExactly(
                Kind.REQUIRED, Kind.INTRO, Kind.REQUIRED, Kind.PREFERRED, Kind.INTRO);
        assertThat(lines).extracting(Line::text).doesNotContain("About Us", "Nice to have");
    }

    @Test
    void indianProfileHeadingsAreRequirementsDespiteTheirWords() {
        List<Line> lines = DescriptionSections.lines("""
                Preferred candidate profile
                - Graduate with 2-4 years in sales
                Desired Skills & Experience
                - Excel""");

        assertThat(lines).extracting(Line::section).containsOnly(Kind.REQUIRED);
    }

    @Test
    void linesWithYearsOrPunctuationAreNeverHeadings() {
        assertThat(DescriptionSections.isHeading("Nice to have")).isTrue();
        assertThat(DescriptionSections.isHeading("3-5 years in Java")).isFalse();
        assertThat(DescriptionSections.isHeading("Two years of experience")).isFalse();
        assertThat(DescriptionSections.isHeading("We are hiring.")).isFalse();
        assertThat(DescriptionSections.isHeading("- Kafka")).isFalse();
    }

    @Test
    void preferredOnlyInTheSameSentenceAndOutsideParentheses() {
        String line = "Strong Java skills. Kafka experience is a plus. Any degree (MBA preferred) with Go.";

        assertThat(DescriptionSections.preferredInSentence(line, line.indexOf("Java"))).isFalse();
        assertThat(DescriptionSections.preferredInSentence(line, line.indexOf("Kafka"))).isTrue();
        assertThat(DescriptionSections.preferredInSentence(line, line.indexOf("Go."))).isFalse();
    }

    @Test
    void typedBulletCharactersAreListItemsNotHeadings() {
        for (String line : new String[]{"- Java", "\u2022 Java", "\u25CF Java, Kafka", "* Redis", "\u27A2 Spring Boot"}) {
            assertThat(DescriptionSections.isBullet(line)).as(line).isTrue();
            assertThat(DescriptionSections.isHeading(line)).as(line).isFalse();
        }
        assertThat(DescriptionSections.isHeading("Nice to have")).isTrue();
        assertThat(DescriptionSections.isBullet("Requirements")).isFalse();
    }

    @Test
    void numberedItemsAreListItems() {
        for (String line : new String[]{"1) Java", "2. Kafka and Redis", "(3) Spring Boot"}) {
            assertThat(DescriptionSections.isBullet(line)).as(line).isTrue();
        }
        assertThat(DescriptionSections.isBullet("3.5 years of experience")).isFalse();
        assertThat(DescriptionSections.isBullet("2026 roadmap")).isFalse();
    }
}
