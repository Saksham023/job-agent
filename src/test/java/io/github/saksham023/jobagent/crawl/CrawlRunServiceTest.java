package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.common.ShutdownSignal;
import io.github.saksham023.jobagent.company.Company;
import io.github.saksham023.jobagent.company.CompanyRepository;
import io.github.saksham023.jobagent.crawl.CrawlService.CrawlResult;
import io.github.saksham023.jobagent.requirements.GapFillRunner;
import io.github.saksham023.jobagent.requirements.GapFillRunner.RunStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Grouping companies by server: one thread per group, the companies of a group one after another. */
class CrawlRunServiceTest {

    private static Company company(String slug) {
        return new Company(1, slug, slug, "x", null, null, true, null, null, null);
    }

    @Test
    void companiesOnTheSameServerShareAGroupInOrder() {
        Map<String, String> servers = Map.of("paytm", "lever", "meesho", "lever", "adobe", "wd5.myworkdayjobs.com",
                "visa", "wd5.myworkdayjobs.com", "qualcomm", "careers.qualcomm.com");
        List<Company> companies = List.of(company("paytm"), company("adobe"), company("qualcomm"), company("meesho"),
                company("visa"));

        Map<String, List<Company>> groups = CrawlRunService.groupByServer(companies, c -> servers.get(c.slug()));

        assertThat(groups.keySet()).containsExactly("lever", "wd5.myworkdayjobs.com", "careers.qualcomm.com");
        assertThat(groups.get("lever")).extracting(Company::slug).containsExactly("paytm", "meesho");
        assertThat(groups.get("wd5.myworkdayjobs.com")).extracting(Company::slug).containsExactly("adobe", "visa");
    }

    @Test
    void aCompanyWithAMinimumIntervalWaitsUntilItHasPassed() {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        Company qualcomm = new Company(1, "qualcomm", "Qualcomm", "eightfold",
                JsonMapper.builder().build().readTree("{\"host\": \"careers.qualcomm.com\", \"minCrawlHours\": 24}"),
                null, true, null, null, null);
        assertThat(CrawlRunService.tooSoon(qualcomm, now.minusSeconds(3 * 3600), now)).isEqualTo("last crawl 3 h ago, minimum 24 h");
        assertThat(CrawlRunService.tooSoon(qualcomm, now.minusSeconds(25 * 3600), now)).isNull();
        assertThat(CrawlRunService.tooSoon(qualcomm, null, now)).isNull();                       // never crawled
        assertThat(CrawlRunService.tooSoon(company("adobe"), now.minusSeconds(60), now)).isNull();  // no minimum
    }

    // ---------------------------------------------------------------- the per-server rounds

    private static Company onServer(long id, String slug) {
        return new Company(id, slug, slug, "x", null, null, true, null, null, null);
    }

    @Test
    void everyServerGroupRunsItsOwnRoundAndASlowOneNeverHoldsBackAnother() throws Exception {
        Company slowCompany = onServer(1, "slow");
        Company fastCompany = onServer(2, "fast");
        CrawlService crawlService = mock(CrawlService.class);
        GapFillRunner gapFillRunner = mock(GapFillRunner.class);
        when(gapFillRunner.fillCompany(anyLong(), any(), anyInt())).thenReturn(status(1, 0.01));
        CountDownLatch slowInside = new CountDownLatch(1);
        CountDownLatch slowMayFinish = new CountDownLatch(1);
        CountDownLatch fastFilled = new CountDownLatch(1);
        when(crawlService.crawl(slowCompany)).thenAnswer(call -> {
            slowInside.countDown();
            slowMayFinish.await(10, TimeUnit.SECONDS);
            return result("slow");
        });
        when(crawlService.crawl(fastCompany)).thenReturn(result("fast"));
        when(gapFillRunner.fillCompany(eq(2L), any(), anyInt())).thenAnswer(call -> {
            fastFilled.countDown();
            return status(1, 0.01);
        });
        CrawlRunService service = service(crawlService, gapFillRunner, new ShutdownSignal(), slowCompany, fastCompany);

        service.startDueServers();                                          // returns at once: one thread per server group
        assertThat(slowInside.await(5, TimeUnit.SECONDS)).as("the slow crawl started").isTrue();
        assertThat(fastFilled.await(5, TimeUnit.SECONDS)).as("the fast company was crawled AND its gap fill ran").isTrue();
        assertThat(slowMayFinish.getCount()).as("while the slow crawl is still going").isEqualTo(1);

        service.startDueServers();                                          // slow group still running, fast group not due yet
        slowMayFinish.countDown();
        Thread.sleep(300);
        service.startDueServers();                                          // both rounds ended, but their delay has not passed
        verify(crawlService, times(1)).crawl(slowCompany);
        verify(crawlService, times(1)).crawl(fastCompany);
    }

    @Test
    void aGroupsCompaniesAreCrawledOneAfterAnotherAndEachGetsItsGapFillEvenWhenNothingIsNew() throws Exception {
        Company first = onServer(1, "first");
        Company second = onServer(2, "second");
        CrawlService crawlService = mock(CrawlService.class);
        when(crawlService.serverKey(any())).thenReturn("same-server");       // one server: one thread, one after another
        when(crawlService.crawl(any())).thenAnswer(call -> result(((Company) call.getArgument(0)).slug()));
        GapFillRunner gapFillRunner = mock(GapFillRunner.class);
        CountDownLatch bothFilled = new CountDownLatch(2);
        when(gapFillRunner.fillCompany(anyLong(), any(), anyInt())).thenAnswer(call -> {
            bothFilled.countDown();
            return status(0, 0);
        });
        CrawlRunService service = new CrawlRunService(companyRepository(first, second), crawlService, runsMock(), gapFillRunner,
                new CrawlScheduleProperties(true, java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(1),
                        java.time.Duration.ofMinutes(1), true, "opus", 10, 2), new ShutdownSignal());
        when(crawlService.isSupported(any())).thenReturn(true);

        service.startDueServers();

        assertThat(bothFilled.await(5, TimeUnit.SECONDS)).isTrue();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(crawlService);
        order.verify(crawlService).crawl(first);
        order.verify(crawlService).crawl(second);
    }

    private static CompanyRepository companyRepository(Company... companies) {
        CompanyRepository repository = mock(CompanyRepository.class);
        when(repository.findAll()).thenReturn(List.of(companies));
        return repository;
    }

    private static CrawlRunRepository runsMock() {
        CrawlRunRepository runs = mock(CrawlRunRepository.class);
        when(runs.recentOkKept(anyLong(), anyInt())).thenReturn(List.of());
        when(runs.lastCrawlStarts()).thenReturn(Map.of());
        return runs;
    }

    // ---------------------------------------------------------------- a full run with stand-ins for the database and the sites

    private static CrawlResult result(String slug) {
        return new CrawlResult(slug, "fake", true, 3, 0, 3, 0, 0, 3, 0, 0, 0, Map.of(), List.of(), 10, Instant.now(), 0, 0);
    }

    private static RunStatus status(int done, double cost) {
        return new RunStatus("opus", "v2", done, done, 0, done, 0, 0, 0, cost, false, Instant.now(), Instant.now(), null);
    }

    private static Company company(long id, String slug) {
        return new Company(id, slug, slug, "x", null, null, true, null, null, null);
    }

    private CrawlRunService service(CrawlService crawlService, GapFillRunner gapFillRunner, ShutdownSignal shutdown,
                                    Company... companies) {
        CompanyRepository repository = mock(CompanyRepository.class);
        when(repository.findAll()).thenReturn(List.of(companies));
        CrawlRunRepository runs = mock(CrawlRunRepository.class);
        when(runs.recentOkKept(anyLong(), anyInt())).thenReturn(List.of());
        when(runs.lastCrawlStarts()).thenReturn(Map.of());
        when(crawlService.isSupported(any())).thenReturn(true);
        when(crawlService.serverKey(any())).thenAnswer(call -> "server-" + ((Company) call.getArgument(0)).slug());
        return new CrawlRunService(repository, crawlService, runs, gapFillRunner,
                new CrawlScheduleProperties(true, java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(1),
                        java.time.Duration.ofMinutes(1), true, "opus", 10, 2), shutdown);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    @Test
    void aCompanysGapFillStartsRightAfterItsCrawlWhileASlowCompanyIsStillCrawling() throws Exception {
        Company fast = company(1, "fast");
        Company slow = company(2, "slow");
        CrawlService crawlService = mock(CrawlService.class);
        GapFillRunner gapFillRunner = mock(GapFillRunner.class);
        CountDownLatch slowMayFinish = new CountDownLatch(1);
        CountDownLatch fastWasFilled = new CountDownLatch(1);
        when(crawlService.crawl(fast)).thenReturn(result("fast"));
        when(crawlService.crawl(slow)).thenAnswer(call -> {
            slowMayFinish.await(10, TimeUnit.SECONDS);
            return result("slow");
        });
        when(gapFillRunner.fillCompany(eq(1L), any(), anyInt())).thenAnswer(call -> {
            fastWasFilled.countDown();
            return status(3, 0.08);
        });
        when(gapFillRunner.fillCompany(eq(2L), any(), anyInt())).thenReturn(status(2, 0.05));
        CrawlRunService service = service(crawlService, gapFillRunner, new ShutdownSignal(), fast, slow);
        CrawlRunService.Run[] run = new CrawlRunService.Run[1];
        Thread runner = new Thread(() -> run[0] = service.runAll("manual"));
        runner.start();

        assertThat(fastWasFilled.await(5, TimeUnit.SECONDS)).as("the fast company's gap fill ran").isTrue();
        assertThat(slowMayFinish.getCount()).as("while the slow company has not finished").isEqualTo(1);

        slowMayFinish.countDown();
        runner.join(10_000);
        assertThat(run[0].outcomes()).extracting(CrawlRunService.RunOutcome::company).containsExactly("fast", "slow");
        assertThat(run[0].gapFills()).hasSize(2);
        assertThat(RunStatus.combine(run[0].gapFills()).done()).isEqualTo(5);
    }

    @Test
    void aCompanyThatIsBeingCrawledIsNotCrawledASecondTimeAtOnce() throws Exception {
        Company slow = company(2, "slow");
        CrawlService crawlService = mock(CrawlService.class);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(crawlService.crawl(slow)).thenAnswer(call -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return result("slow");
        });
        CrawlRunService service = service(crawlService, mock(GapFillRunner.class), new ShutdownSignal(), slow);
        CrawlRunService.RunOutcome[] first = new CrawlRunService.RunOutcome[1];
        Thread running = new Thread(() -> first[0] = service.crawlOne(slow, "manual"));
        running.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        CrawlRunService.RunOutcome second = service.crawlOne(slow, "schedule");        // the next schedule slot

        assertThat(second.error()).isEqualTo("already being crawled");
        assertThat(second.result()).isNull();
        release.countDown();
        running.join(10_000);
        assertThat(first[0].error()).isNull();                                          // the first crawl went on undisturbed
        verify(crawlService, times(1)).crawl(slow);
        // and once it is finished the company can be crawled again
        assertThat(service.crawlOne(slow, "manual").error()).isNull();
    }

    @Test
    void aCompanyWithNothingNewIsStillAskedAboutJobsAnEarlierFailedFillLeftUnasked() {
        Company quiet = company(1, "quiet");
        CrawlService crawlService = mock(CrawlService.class);
        GapFillRunner gapFillRunner = mock(GapFillRunner.class);
        when(crawlService.crawl(quiet)).thenReturn(new CrawlResult("quiet", "fake", true, 3, 0, 3, 0, 0, 0, 0, 3, 0, Map.of(),
                List.of(), 10, Instant.now(), 0, 0));

        when(gapFillRunner.fillCompany(eq(1L), any(), anyInt())).thenReturn(status(0, 0));

        CrawlRunService.Run run = service(crawlService, gapFillRunner, new ShutdownSignal(), quiet).runAll("manual");

        // the question to the database is cheap and finds nothing when nothing is unasked; it must run so that jobs left
        // unasked by an earlier failed fill (or an interrupted one) are not forgotten until the company posts something new
        assertThat(run.gapFills()).hasSize(1);
        verify(gapFillRunner, times(1)).fillCompany(eq(1L), any(), anyInt());
    }

    @Test
    void nothingNewStartsWhileTheAppIsShuttingDown() {
        Company company = company(1, "acme");
        CrawlService crawlService = mock(CrawlService.class);
        GapFillRunner gapFillRunner = mock(GapFillRunner.class);
        ShutdownSignal shutdown = new ShutdownSignal();
        shutdown.stop();

        CrawlRunService.Run run = service(crawlService, gapFillRunner, shutdown, company).runAll("manual");

        assertThat(run.outcomes()).isEmpty();
        verify(crawlService, never()).crawl(any());
    }

    @Test
    void severalGapFillsAddUpToOneReport() {
        RunStatus total = RunStatus.combine(List.of(status(3, 0.08), status(2, 0.05), status(0, 0)));
        assertThat(total.done()).isEqualTo(5);
        assertThat(total.toFill()).isEqualTo(5);
        assertThat(total.costUsd()).isEqualTo(0.13);
        assertThat(total.running()).isFalse();
    }
}
