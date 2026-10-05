package io.github.saksham023.jobagent.requirements;

import io.github.saksham023.jobagent.requirements.CompanyBoilerplate.Posting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding a company's repeated lines, with the PayPal case that started it (2026-10-06): every PayPal posting opens
 * with "...for more than 25 years...", which the experience rules read as "25+ years".
 */
class CompanyBoilerplateTest {

    private static final String INTRO = "PayPal has been revolutionizing commerce globally for more than 25 years.";
    private static final String HEADING = "Minimum Qualifications";

    private static Posting posting(String title, String requirement) {
        return new Posting(title, String.join("\n", INTRO, HEADING, "- " + requirement));
    }

    private static List<Posting> fivePostings() {
        List<Posting> postings = new ArrayList<>();
        for (String title : List.of("Analyst, Tax", "Sr Recruiter", "Payroll Accountant", "Sr. SRE", "Staff Security Engineer")) {
            postings.add(posting(title, "2+ years of experience as a " + title));
        }
        return postings;
    }

    @Test
    void aLineUnderMostTitlesIsRemovedButHeadingsAndRequirementsStay() {
        CompanyBoilerplate boilerplate = CompanyBoilerplate.of(fivePostings());

        String stripped = boilerplate.strip(posting("Sr. SRE", "2+ years of experience as a Sr. SRE").description());

        assertThat(boilerplate.size()).isEqualTo(1);
        assertThat(stripped).doesNotContain("25 years").contains(HEADING, "2+ years of experience");
    }

    @Test
    void sameLineWithAnotherBulletOrSpacingStillMatches() {
        CompanyBoilerplate boilerplate = CompanyBoilerplate.of(fivePostings());

        assertThat(boilerplate.strip("•  " + INTRO.replace(" ", "  "))).isEmpty();
    }

    @Test
    void manyCopiesOfOnePostingDoNotMakeItsRequirementsBoilerplate() {
        List<Posting> postings = new ArrayList<>(fivePostings());
        for (int i = 0; i < 20; i++) {
            postings.add(new Posting("Senior Software Engineer", "- 5+ years of Java backend development experience"));
        }

        CompanyBoilerplate boilerplate = CompanyBoilerplate.of(postings);

        assertThat(boilerplate.strip("- 5+ years of Java backend development experience")).contains("5+ years");
    }

    @Test
    void tooFewTitlesMeansNoBoilerplate() {
        CompanyBoilerplate boilerplate = CompanyBoilerplate.of(fivePostings().subList(0, 4));

        assertThat(boilerplate.size()).isZero();
        assertThat(boilerplate.strip(INTRO)).isEqualTo(INTRO);
    }

    @Test
    void noneKeepsEverything() {
        assertThat(CompanyBoilerplate.none().strip(INTRO)).isEqualTo(INTRO);
        assertThat(CompanyBoilerplate.none().strip(null)).isNull();
    }
}
