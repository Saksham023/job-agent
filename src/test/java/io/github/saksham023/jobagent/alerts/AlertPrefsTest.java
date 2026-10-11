package io.github.saksham023.jobagent.alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertPrefsTest {

    @Test
    void tidiesWhatTheUserSent() {
        AlertPrefs prefs = new AlertPrefs(true, List.of("software_engineering", "UNCLASSIFIED", "SOFTWARE_ENGINEERING", "data_ml"),
                0, 3, true, List.of(" Java ", "Java", ""), List.of("remote", "Bengaluru"), List.of("Amazon", "okta")).checked();

        assertThat(prefs.families()).containsExactly("SOFTWARE_ENGINEERING", "DATA_ML");
        assertThat(prefs.skills()).containsExactly("Java");
        assertThat(prefs.cities()).containsExactly("Remote", "Bengaluru");               // written as the website writes it
        assertThat(prefs.companies()).containsExactly("amazon", "okta");
    }

    @Test
    void emptyMeansNoFilter() {
        AlertPrefs prefs = new AlertPrefs(false, null, null, null, true, null, null, null).checked();
        assertThat(prefs.families()).isEmpty();
        assertThat(prefs.minYears()).isNull();
        assertThat(prefs.companies()).isEmpty();
    }

    @Test
    void refusesWhatCannotBeRight() {
        assertThatThrownBy(() -> prefs(List.of("NOT_A_FAMILY"), 0, 3, List.of()).checked()).hasMessageContaining("Unknown job family");
        assertThatThrownBy(() -> prefs(List.of(), 5, 2, List.of()).checked()).hasMessageContaining("'From' years");
        assertThatThrownBy(() -> prefs(List.of(), -1, 2, List.of()).checked()).hasMessageContaining("between 0 and 50");
        assertThatThrownBy(() -> prefs(List.of(), null, null, List.of("Not A Slug!")).checked()).hasMessageContaining("Unknown company");
    }

    private static AlertPrefs prefs(List<String> families, Integer min, Integer max, List<String> companies) {
        return new AlertPrefs(true, families, min, max, true, List.of(), List.of(), companies);
    }
}
