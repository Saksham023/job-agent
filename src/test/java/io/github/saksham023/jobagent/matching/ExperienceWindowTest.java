package io.github.saksham023.jobagent.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rounding resume years and the default "years minus 2 to plus 1" window.
 */
class ExperienceWindowTest {

    private static final MatchingProperties DEFAULTS = MatchingProperties.defaults();

    @Test
    void halfAYearRoundsUpAndLessRoundsDown() {
        assertThat(ExperienceWindow.roundYears(1.5, 0.5)).isEqualTo(2);
        assertThat(ExperienceWindow.roundYears(1.6, 0.5)).isEqualTo(2);
        assertThat(ExperienceWindow.roundYears(1.4, 0.5)).isEqualTo(1);
        assertThat(ExperienceWindow.roundYears(2.0, 0.5)).isEqualTo(2);
        assertThat(ExperienceWindow.roundYears(0.2, 0.5)).isEqualTo(0);
        assertThat(ExperienceWindow.roundYears(null, 0.5)).isNull();
    }

    @Test
    void theCutOffIsConfigurable() {
        assertThat(ExperienceWindow.roundYears(1.4, 0.4)).isEqualTo(2);
        assertThat(ExperienceWindow.roundYears(1.3, 0.4)).isEqualTo(1);
    }

    @Test
    void theDefaultWindowIsTwoBelowToOneAbove() {
        assertThat(ExperienceWindow.resolve(2, null, null, DEFAULTS)).isEqualTo(new ExperienceWindow(0, 3));
        assertThat(ExperienceWindow.resolve(5, null, null, DEFAULTS)).isEqualTo(new ExperienceWindow(3, 6));
        assertThat(ExperienceWindow.resolve(1, null, null, DEFAULTS)).isEqualTo(new ExperienceWindow(0, 2));
    }

    @Test
    void anExplicitRangeReplacesTheDefaultEnd() {
        assertThat(ExperienceWindow.resolve(2, null, 4, DEFAULTS)).isEqualTo(new ExperienceWindow(0, 4));
        assertThat(ExperienceWindow.resolve(2, 3, 6, DEFAULTS)).isEqualTo(new ExperienceWindow(3, 6));
        assertThat(ExperienceWindow.resolve(null, null, 4, DEFAULTS)).isEqualTo(new ExperienceWindow(null, 4));
    }

    @Test
    void unknownYearsAndNoRangeMeanNoExperienceFilter() {
        assertThat(ExperienceWindow.resolve(null, null, null, DEFAULTS)).isNull();
    }
}
