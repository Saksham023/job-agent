package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.ExperienceExtractor.Confidence;
import io.github.saksham023.jobagent.requirements.ExperienceExtractor.Experience;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real experience phrasings from the stored job descriptions (2026-10-05), plus the traps we found.
 * Descriptions are plain text as HtmlToText produces it: one line per paragraph or bullet ("- ").
 */
class ExperienceExtractorTest {

    private final ExperienceExtractor extractor = new ExperienceExtractor();

    private Experience fromDescription(String description) {
        return extractor.extract("Some Role", description);
    }

    // ---------------------------------------------------------------- basic shapes

    @Test
    void plusMeansOpenEndedMinimum() {
        Experience e = fromDescription("- 5+ years of software engineering experience");

        assertThat(e.minYears()).isEqualTo(5);
        assertThat(e.maxYears()).isNull();
        assertThat(e.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(e.evidence()).contains("5+ years");
    }

    @Test
    void rangesWithHyphenEnDashAndTo() {
        assertThat(fromDescription("- 3-5 years of backend experience").maxYears()).isEqualTo(5);
        assertThat(fromDescription("- 4\u20138 years of experience in HR Technology").minYears()).isEqualTo(4);
        assertThat(fromDescription("- 8 to 12 years in a consulting environment").maxYears()).isEqualTo(12);
    }

    @Test
    void lowerBoundWords() {
        assertThat(fromDescription("- Minimum 4 years of auditing source code").minYears()).isEqualTo(4);
        assertThat(fromDescription("- At least 3 years of experience with Java").minYears()).isEqualTo(3);
    }

    @Test
    void upToMeansZeroToN() {
        Experience e = fromDescription("- CA with up to ~2 years post-qualification experience in audit");

        assertThat(e.minYears()).isZero();
        assertThat(e.maxYears()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- title wins

    @Test
    void rangeInTitleWins() {
        Experience e = extractor.extract("Product Management - UPI Product | Experience : 7-9 yrs",
                "- 3+ years of product experience");

        assertThat(e.minYears()).isEqualTo(7);
        assertThat(e.maxYears()).isEqualTo(9);
        assertThat(e.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void titleNumbersWithoutYearsAreIgnored() {
        Experience e = extractor.extract("Software Engineer (5-7) Lending",
                "We are seeking a Software Engineer with 7 to 10 years of experience to join our team.");

        assertThat(e.minYears()).isEqualTo(7);
    }

    // ---------------------------------------------------------------- several requirements: largest lower bound

    @Test
    void skillScopedRequirementDoesNotLowerTheOverallOne() {
        Experience e = fromDescription("""
                - 2+ years of software engineering experience
                - 1+ year of experience in Java""");

        assertThat(e.minYears()).isEqualTo(2);
        assertThat(e.confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void subRequirementsOnTheSameLine() {
        assertThat(fromDescription("- 14+ years of software development experience and 4+ years in leading managers.")
                .minYears()).isEqualTo(14);
        assertThat(fromDescription("- 5+ years of experience in collections, with a minimum of 1-2 years in a team lead role.")
                .minYears()).isEqualTo(5);
        assertThat(fromDescription("- 5-7 years of HR experience with 3+ years of core HRBP experience.")
                .maxYears()).isEqualTo(7);
    }

    @Test
    void scopedRequirementLargerThanGeneralOneWins() {
        Experience e = fromDescription("""
                - 3+ years of experience with Java
                - 5+ years of backend development experience""");

        assertThat(e.minYears()).isEqualTo(5);
    }

    // ---------------------------------------------------------------- alternatives

    @Test
    void alternativesTakeTheEasierPathWithLowConfidence() {
        Experience e = fromDescription("- 15+ years of relevant experience, or 10+ years of experience and an advanced degree");

        assertThat(e.minYears()).isEqualTo(10);
        assertThat(e.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void orBetweenFieldsIsNotAnAlternative() {
        Experience e = fromDescription(
                "- 3\u20136 years in data science, ML research, or applied AI; at least 2 years working with LLMs");

        assertThat(e.minYears()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- preferred vs required

    @Test
    void preferredSectionDoesNotRaiseTheMinimum() {
        Experience e = fromDescription("""
                Requirements
                - 4+ years of backend development experience
                Nice to have
                - 6+ years in payments""");

        assertThat(e.minYears()).isEqualTo(4);
        assertThat(e.preferredMinYears()).isEqualTo(6);
    }

    @Test
    void preferredInParenthesesQualifiesSomethingElse() {
        Experience e = fromDescription(
                "- Any graduate degree (MBA preferred) with 7 to 9 years of prior experience in a similar position.");

        assertThat(e.minYears()).isEqualTo(7);
        assertThat(e.preferredMinYears()).isNull();
    }

    @Test
    void preferredInAnotherSentenceDoesNotCount() {
        Experience e = fromDescription(
                "- Experience: 2\u20134 years of core experience. Prior Sales HRBP or Field HR experience is preferred.");

        assertThat(e.minYears()).isEqualTo(2);
    }

    @Test
    void indianProfileHeadingsMeanRequirements() {
        Experience e = fromDescription("""
                Preferred candidate profile
                - 2-7 years of experience in mutual fund distribution or sales.""");

        assertThat(e.minYears()).isEqualTo(2);
        assertThat(e.preferredMinYears()).isNull();
    }

    @Test
    void onlySoftRequirementsBecomeTheMinimumWithLowConfidence() {
        Experience e = fromDescription("- 0-1 years of similar experience(s) are preferred; Freshers can apply.");

        assertThat(e.minYears()).isZero();
        assertThat(e.maxYears()).isEqualTo(1);
        assertThat(e.confidence()).isEqualTo(Confidence.LOW);
    }

    // ---------------------------------------------------------------- noise and nothing

    @Test
    void numbersAboutTheCompanyAreIgnored() {
        Experience e = fromDescription("""
                We are a fintech founded 12 years ago with a 3-year roadmap.
                - 4+ years of backend development experience""");

        assertThat(e.minYears()).isEqualTo(4);
        assertThat(e.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void withinAsPartOfTheSentenceIsNotNoise() {
        assertThat(fromDescription("- 10+ years of sales experience within software or solutions sales").minYears())
                .isEqualTo(10);
    }

    @Test
    void noMentionGivesNone() {
        Experience e = fromDescription("- Experience designing database schemas and distributed storage layers.");

        assertThat(e.confidence()).isEqualTo(Confidence.NONE);
        assertThat(e.minYears()).isNull();
        assertThat(extractor.extract("Some Role", null).confidence()).isEqualTo(Confidence.NONE);
    }

    // ---------------------------------------------------------------- estimate from the title when no years are written

    @Test
    void titleEstimateWhenNoYearsAreWritten() {
        Experience staff = extractor.extract("Senior Staff Software Engineer- Search Quality", "- Build search systems.");

        assertThat(staff.minYears()).isEqualTo(8);
        assertThat(staff.confidence()).isEqualTo(Confidence.LOW);
        assertThat(staff.evidence()).startsWith("title:");
        assertThat(extractor.extract("Director Sales - Moveworks", null).minYears()).isEqualTo(12);
        assertThat(extractor.extract("Senior Solutions Engineer", null).minYears()).isEqualTo(5);
    }

    @Test
    void internsFromTitleOrEmploymentType() {
        assertThat(extractor.extract("Video Editor Intern", null).maxYears()).isEqualTo(1);
        assertThat(extractor.extract("Marketing Associate", null, "Intern").maxYears()).isEqualTo(1);
    }

    @Test
    void ambiguousTitleWordsGiveNoEstimate() {
        assertThat(extractor.extract("Senior Executive - Lending Operations", null).confidence()).isEqualTo(Confidence.NONE);
        assertThat(extractor.extract("Collections Manager", null).confidence()).isEqualTo(Confidence.NONE);
        assertThat(extractor.extract("Business Analyst", null).confidence()).isEqualTo(Confidence.NONE);
    }

    @Test
    void writtenYearsAlwaysBeatTheTitleEstimate() {
        assertThat(extractor.extract("Senior Software Engineer", "- 3+ years of backend experience").minYears()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- company intro sections

    @Test
    void introSectionsAreSkipped() {
        Experience e = fromDescription("""
                About Us
                - Our leadership team brings 15+ years of experience in payments.
                Requirements
                - 3+ years of backend development experience""");

        assertThat(e.minYears()).isEqualTo(3);
        assertThat(e.confidence()).isEqualTo(Confidence.HIGH);
    }

    // ---------------------------------------------------------------- typed bullet characters

    @Test
    void typedBulletsCountAsListItemsEvenWithoutTheWordExperience() {
        // Sarvam (Ashby) types "\u2022" into the text; HtmlToText only produces "- " for HTML lists
        Experience e = fromDescription("What We're Looking For\n"
                + "\u2022 3\u20136 years in backend engineering with production systems in continuous operation");

        assertThat(e.minYears()).isEqualTo(3);
        assertThat(e.maxYears()).isEqualTo(6);
        assertThat(e.confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void aShortTypedBulletIsNotAHeadingThatResetsTheSection() {
        // Paytm: "* ... (Preferable)" used to be taken for a heading and switched the section back to required
        Experience e = fromDescription("Preferred:\n"
                + "* Good Communication skills in English, French (Preferable)\n"
                + "* 4+ years of experience with IVR Marketing, Business Development.");

        assertThat(e.preferredMinYears()).isEqualTo(4);
    }

    @Test
    void aMisspelledYearsAfterANumberStillCounts() {
        // Paytm, Backend Technical Lead: the posting really says "ears"
        Experience e = fromDescription("What We're Looking For:\n"
                + "1) 4 to 6 ears of hands-on backend engineering experience \u2014 Java/Nodejs.");

        assertThat(e.minYears()).isEqualTo(4);
        assertThat(e.maxYears()).isEqualTo(6);
        assertThat(fromDescription("- Keep your eyes and ears open").confidence()).isEqualTo(Confidence.NONE);
    }
}
