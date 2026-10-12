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
    void oneDerivedPostingTimeIsUsedForDisplaySortAndFilter() {
        assertThat(JobSearch.POSTED).contains("j.first_seen_at", "time '12:00'", "Asia/Kolkata");
        assertThat(search(null, null, true, List.of()).orderBy()).startsWith(JobSearch.POSTED + " DESC").endsWith(", j.id DESC");
        JobSearch byExperience = new JobSearch(List.of(), List.of(), null, null, true, List.of(), List.of(), null, null,
                JobSearch.Sort.EXPERIENCE, 0, 24);
        assertThat(byExperience.orderBy()).contains(JobSearch.POSTED + " DESC, j.id");
    }

    @Test
    void noFiltersMeansOpenJobsInTheCountry() {
        Where where = search(null, null, true, List.of()).where("IN");
        assertThat(where.sql()).isEqualTo("j.closed_at IS NULL AND j.country_codes @> ARRAY[CAST(:country AS text)]");
        assertThat(where.params()).containsEntry("country", "IN").containsEntry("userId", null).hasSize(2);
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

    private static JobSearch withStatus(List<JobStatus> statuses, Long userId) {
        return withStatus(statuses, List.of(), userId);
    }

    private static JobSearch withStatus(List<JobStatus> statuses, List<JobStatus> hidden, Long userId) {
        return new JobSearch(List.of(), List.of(), null, null, true, List.of(), List.of(), null, null, null, 0, 24,
                statuses, hidden, userId);
    }

    @Test
    void aSignedInUsersHiddenJobsAreLeftOutByDefault() {
        Where where = withStatus(List.of(), 7L).where("IN");
        assertThat(where.sql()).contains(
                "(s.job_id IS NULL OR (NOT s.hidden AND (s.status IS NULL OR s.status <> ALL(CAST(:hidden AS text[])))))");
        assertThat((String[]) where.params().get("hidden")).isEmpty();
        assertThat(where.params()).containsEntry("userId", 7L).doesNotContainKey("statuses");
    }

    @Test
    void appliedJobsCanBeHiddenForNowOnTopOfTheHiddenJobs() {
        Where where = withStatus(List.of(), List.of(JobStatus.APPLIED, JobStatus.HIDDEN), 7L).where("IN");
        assertThat((String[]) where.params().get("hidden")).containsExactly("APPLIED");    // HIDDEN is not a status
        // picking what to show wins: nothing is hidden then
        Where shown = withStatus(List.of(JobStatus.APPLIED), List.of(JobStatus.APPLIED), 7L).where("IN");
        assertThat(shown.sql()).contains("s.status = ANY").doesNotContain(":hidden").doesNotContain("s.hidden");
        // the hidden choice survives the facet copies
        assertThat(withStatus(List.of(), List.of(JobStatus.SAVED), 7L).without(JobSearch.Facet.COMPANY).hidden())
                .containsExactly(JobStatus.SAVED);
    }

    @Test
    void showOnlyHiddenListsTheHiddenJobsWhateverTheirStatus() {
        Where where = withStatus(List.of(JobStatus.APPLIED, JobStatus.HIDDEN), 7L).where("IN");
        assertThat(where.sql()).contains("(s.status = ANY(CAST(:statuses AS text[])) OR s.hidden)");
        assertThat((String[]) where.params().get("statuses")).containsExactly("APPLIED");
        Where hiddenOnly = withStatus(List.of(JobStatus.HIDDEN), 7L).where("IN");
        assertThat((String[]) hiddenOnly.params().get("statuses")).isEmpty();
        assertThat(hiddenOnly.sql()).contains("OR s.hidden)");
    }

    @Test
    void withoutAUserStatusesPlayNoPartAndAStatusFilterMatchesNothing() {
        Where none = withStatus(List.of(), null).where("IN");
        assertThat(none.sql()).doesNotContain("s.status");
        assertThat(none.params()).containsEntry("userId", null);
        assertThat(withStatus(List.of(JobStatus.SAVED), null).where("IN").sql()).contains("AND false");
    }

    @Test
    void theStatusCountsCountEveryStatusAndKeepTheUser() {
        JobSearch counted = withStatus(List.of(JobStatus.APPLIED), 7L).without(JobSearch.Facet.STATUS);
        assertThat(counted.statuses()).containsExactly(JobStatus.values());
        assertThat(counted.userId()).isEqualTo(7L);
        assertThat(withStatus(List.of(JobStatus.APPLIED), 7L).without(JobSearch.Facet.COMPANY).statuses())
                .containsExactly(JobStatus.APPLIED);
        assertThat(withStatus(List.of(), List.of(JobStatus.APPLIED), 7L).checkedForVisitor().hidden())
                .containsExactly(JobStatus.APPLIED);
    }

    @Test
    void onlyTheKnownStatusesAreAcceptedAndTheOldNameMeansHidden() {
        assertThat(JobStatus.parse(List.of("applied", " Not_Interested ", "hidden", "applied")))
                .containsExactly(JobStatus.APPLIED, JobStatus.HIDDEN);
        assertThatThrownBy(() -> JobStatus.parse("INTERVIEWING")).hasMessageContaining("status must be one of");
        assertThatThrownBy(() -> JobStatus.parse((String) null)).isInstanceOf(IllegalArgumentException.class);
    }
}
