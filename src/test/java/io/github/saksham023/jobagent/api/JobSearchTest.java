package io.github.saksham023.jobagent.api;

import io.github.saksham023.jobagent.api.JobSearch.Where;
import io.github.saksham023.jobagent.requirements.JobFamily;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public search's filters: which conditions and parameters each produces, and what is refused. */
class JobSearchTest {

    private static JobSearch search(Integer from, Integer to, boolean includeUnstated, List<String> cities) {
        return new JobSearch(List.of(), List.of(), from, to, includeUnstated, cities, List.of(), null, null, null, 0, 24);
    }

    @Test
    void visitorsCannotBuildExpensiveRequestsButTheKeyHolderCan() {
        List<String> many = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "city" + i).toList();
        assertThatThrownBy(() -> search(null, null, true, many).checkedForVisitor())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 50");
        assertThatThrownBy(() -> new JobSearch(List.of(), List.of(), null, null, true, List.of(), List.of(), "x".repeat(101),
                null, null, 0, 24).checkedForVisitor()).hasMessageContaining("keyword");
        assertThatThrownBy(() -> new JobSearch(List.of(), List.of(), null, null, true, List.of(), List.of(), null,
                null, null, 1001, 24).checkedForVisitor()).hasMessageContaining("page must be between");
        assertThat(search(null, null, true, many.subList(0, 50)).checkedForVisitor().cities()).hasSize(50);
        // without checkedForVisitor (the trusted path) nothing above is refused
        assertThat(search(null, null, true, many).cities()).hasSize(51);
    }

    @Test
    void pageSizeIsCappedForVisitorsAndHighForTheKeyHolder() {
        JobSearch big = new JobSearch(List.of(), List.of(), null, null, true, List.of(), List.of(), null, null, null, 0, 500);
        assertThat(big.size()).isEqualTo(500);                           // trusted: as asked
        assertThat(big.checkedForVisitor().size()).isEqualTo(JobSearch.MAX_SIZE);
        assertThat(new JobSearch(List.of(), List.of(), null, null, true, List.of(), List.of(), null, null, null, 0, 99999)
                .size()).isEqualTo(JobSearch.MAX_TRUSTED_SIZE);
    }

    @Test
    void noFiltersMeansOpenJobsInTheCountry() {
        Where where = search(null, null, true, List.of()).where("IN");
        assertThat(where.sql()).isEqualTo("j.closed_at IS NULL AND j.country_codes @> ARRAY[CAST(:country AS text)]");
        assertThat(where.params()).containsEntry("country", "IN").hasSize(1);
    }

    @Test
    void experienceIsAHardOverlapWithStatedYears() {
        Where where = search(3, 5, false, List.of()).where("IN");
        assertThat(where.sql()).contains("r.min_years <= :maxYears", "coalesce(r.max_years, 99) >= :minYears")
                .doesNotContain("OR NOT");
        assertThat(where.params()).containsEntry("minYears", 3).containsEntry("maxYears", 5);
        // with unstated jobs included, a job without stated years also passes
        assertThat(search(3, 5, true, List.of()).where("IN").sql()).contains("OR NOT " + JobSearch.STATED);
    }

    @Test
    void remoteIsALocationOption() {
        Where both = search(null, null, true, List.of("Pune", "Remote")).where("IN");
        assertThat(both.sql()).contains("(j.cities && CAST(:cities AS text[]) OR j.remote)");
        assertThat((String[]) both.params().get("cities")).containsExactly("Pune");
        assertThat(search(null, null, true, List.of("Remote")).where("IN").sql()).contains("(j.remote)")
                .doesNotContain(":cities");
    }

    @Test
    void familiesMatchPrimaryOrSecondaryAndTheKeywordIsEscaped() {
        JobSearch search = new JobSearch(List.of("adobe", " "), List.of(JobFamily.DATA_ML), null, null, true, List.of(),
                List.of("Java", "Kafka"), "50%_off", 7, JobSearch.Sort.EXPERIENCE, 2, 500);
        Where where = search.where("IN");
        assertThat(where.sql()).contains("r.secondary_families && CAST(:families AS text[])",
                "@> CAST(:skills AS text[])", "make_interval(days => :days)");
        assertThat((String[]) where.params().get("companies")).containsExactly("adobe");
        assertThat(where.params()).containsEntry("query", "%50\\%\\_off%");
        assertThat(search.checkedForVisitor().size()).isEqualTo(JobSearch.MAX_SIZE);
        assertThat(search.orderBy()).startsWith(JobSearch.STATED + " DESC, r.min_years NULLS LAST");
    }

    @Test
    void badInputIsRefusedWithAClearMessage() {
        assertThatThrownBy(() -> search(5, 3, true, List.of())).hasMessage("Experience 'from' must not be greater than 'to'");
        assertThatThrownBy(() -> search(-1, null, true, List.of())).hasMessage("Experience must be between 0 and 50 years");
        assertThatThrownBy(() -> JobSearch.families(List.of("ROCKET_SCIENCE"))).hasMessage("Unknown job family: ROCKET_SCIENCE");
        assertThat(JobSearch.families(List.of("software_engineering"))).containsExactly(JobFamily.SOFTWARE_ENGINEERING);
    }

    @Test
    void aListsOwnCountsIgnoreItsOwnSelectionButSkillsKeepTheirs() {
        JobSearch search = new JobSearch(List.of("adobe"), List.of(JobFamily.DATA_ML), 0, 3, true, List.of("Pune"),
                List.of("Java"), null, null, null, 0, 24);
        assertThat(search.without(JobSearch.Facet.COMPANY).companies()).isEmpty();
        assertThat(search.without(JobSearch.Facet.COMPANY).families()).containsExactly(JobFamily.DATA_ML);
        assertThat(search.without(JobSearch.Facet.FAMILY).families()).isEmpty();
        assertThat(search.without(JobSearch.Facet.LOCATION).cities()).isEmpty();
        assertThat(search.without(JobSearch.Facet.SKILL).skills()).containsExactly("Java");
        assertThat(search.without(JobSearch.Facet.LOCATION).maxYears()).isEqualTo(3);
    }
}
