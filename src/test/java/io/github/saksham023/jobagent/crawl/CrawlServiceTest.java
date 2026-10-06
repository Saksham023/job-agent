package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.crawl.CrawlService.CrawlResult;
import io.github.saksham023.jobagent.crawl.CrawlService.PartialCrawlException;
import io.github.saksham023.jobagent.job.JobRepository;
import io.github.saksham023.jobagent.job.JobRepository.UpsertOutcome;
import io.github.saksham023.jobagent.job.NormalizedJob;
import io.github.saksham023.jobagent.requirements.RequirementsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a crawl hands jobs to the database: all at once for a quick adapter, in batches for a streaming one, and what
 * stays saved when a long crawl fails part way. Database and normalizer are stand-ins.
 */
class CrawlServiceTest {

    private static final Company COMPANY =
            new Company(1, "acme", "Acme", "fake", JsonMapper.builder().build().createObjectNode(), null, true, null, null, null);

    private JobRepository repository;
    private JobNormalizer normalizer;
    private RequirementsService requirements;

    @BeforeEach
    void setUp() {
        repository = mock(JobRepository.class);
        when(repository.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        normalizer = mock(JobNormalizer.class);
        when(normalizer.normalize(any(), any())).thenAnswer(call -> {
            RawJob raw = call.getArgument(1);
            return new NormalizedJob(1, raw.externalId(), raw.title(), null, null, List.of("India"), List.of(), List.of(),
                    List.of("IN"), false, null, raw.url(), raw.description(), null, null, "hash", null, null, false);
        });
        requirements = mock(RequirementsService.class, Answers.RETURNS_DEEP_STUBS);
    }

    private CrawlService service(JobBoardAdapter adapter) {
        return new CrawlService(List.of(adapter), normalizer, repository, mock(PlatformTransactionManager.class),
                requirements, List.of("IN"));
    }

    private static RawJob job(int id) {
        return new RawJob(String.valueOf(id), "Job " + id, null, null, List.of(), null, "https://x/" + id, "text " + id, null, null, null);
    }

    /** A quick adapter: the default method hands everything back at the end. */
    private static JobBoardAdapter quick(List<RawJob> jobs) {
        return new JobBoardAdapter() {
            public String platform() { return "fake"; }
            public List<RawJob> fetchJobs(Company company) { return jobs; }
        };
    }

    /** A streaming adapter: passes the first batch, then fails (or finishes). */
    private static JobBoardAdapter streaming(List<RawJob> firstBatch, List<RawJob> secondBatch, RuntimeException failure) {
        return new JobBoardAdapter() {
            public String platform() { return "fake"; }
            public List<RawJob> fetchJobs(Company company) { throw new UnsupportedOperationException(); }
            public List<RawJob> fetchJobs(Company company, Consumer<List<RawJob>> sink) {
                sink.accept(firstBatch);
                if (failure != null) {
                    throw failure;
                }
                sink.accept(secondBatch);
                return List.of();
            }
        };
    }

    @Test
    void aQuickAdapterIsSavedOnceAtTheEnd() {
        CrawlResult result = service(quick(List.of(job(1), job(2), job(3)))).crawl(COMPANY);

        assertThat(result.fetched()).isEqualTo(3);
        assertThat(result.kept()).isEqualTo(3);
        assertThat(result.inserted()).isEqualTo(3);
        verify(repository, times(3)).upsert(any(), any());
    }

    @Test
    void aStreamingAdapterIsSavedBatchByBatchAndCountedAcrossThem() {
        CrawlResult result = service(streaming(List.of(job(1), job(2)), List.of(job(3)), null)).crawl(COMPANY);

        assertThat(result.fetched()).isEqualTo(3);
        assertThat(result.inserted()).isEqualTo(3);
        assertThat(result.keptJobs()).extracting(NormalizedJob::externalId).containsExactly("1", "2", "3");
        verify(repository, times(3)).upsert(any(), any());
    }

    @Test
    void aFailureAfterABatchWasSavedKeepsTheJobsAndReportsAPartialCrawl() {
        RuntimeException failure = new IllegalStateException("blocked");

        assertThatThrownBy(() -> service(streaming(List.of(job(1), job(2)), List.of(), failure)).crawl(COMPANY))
                .isInstanceOf(PartialCrawlException.class)
                .hasMessageContaining("stopped after saving 2 jobs")
                .hasCause(failure)
                .satisfies(e -> {
                    CrawlResult partial = ((PartialCrawlException) e).partial();
                    assertThat(partial.inserted()).isEqualTo(2);
                    assertThat(partial.kept()).isEqualTo(2);
                });
        verify(repository, times(2)).upsert(any(), any());           // the two jobs of the batch are in the database
    }

    @Test
    void aFailureBeforeAnythingWasSavedIsAnOrdinaryFailure() {
        RuntimeException failure = new IllegalStateException("list page failed");
        JobBoardAdapter failing = new JobBoardAdapter() {
            public String platform() { return "fake"; }
            public List<RawJob> fetchJobs(Company company) { throw failure; }
        };

        assertThatThrownBy(() -> service(failing).crawl(COMPANY)).isSameAs(failure);
        verify(repository, never()).upsert(any(), any());
    }

    @Test
    void aPreviewSavesNothingAndASavedJobsFailureIsNotPartial() {
        CrawlResult preview = service(streaming(List.of(job(1)), List.of(job(2)), null)).preview(COMPANY);

        assertThat(preview.saved()).isFalse();
        assertThat(preview.kept()).isEqualTo(2);
        verify(repository, never()).upsert(any(), any());
        // with nothing saved (a preview) a failure is not a partial crawl
        assertThatThrownBy(() -> service(streaming(List.of(job(1)), List.of(), new IllegalStateException("x"))).preview(COMPANY))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(PartialCrawlException.class);
    }
}
