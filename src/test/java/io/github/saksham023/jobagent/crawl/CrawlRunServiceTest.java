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

    private static Company slowCompany(long id, String slug) throws Exception {
        return new Company(id, slug, slug, "eightfold",
                JsonMapper.builder().build().readTree("{\"host\": \"" + slug + ".example\", \"minCrawlHours\": 24}"),
                null, true, null, null, null);
    }

    private static CrawlRunRepository.RunState ended(CrawlHealth.Status status, Instant finishedAt) {
        return new CrawlRunRepository.RunState(status, finishedAt);
    }

    @Test
    void aSlowCompanyIsDueWhenNeverCrawledWhenItsIntervalPassedOrForARetryAfterAThrottledCrawl() throws Exception {
        Company microsoft = slowCompany(1, "microsoft");
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        java.time.Duration retryAfter = java.time.Duration.ofMinutes(30);

        assertThat(CrawlRunService.dueReason(microsoft, null, List.of(), retryAfter, 3, now)).isEqualTo("never crawled");
        assertThat(CrawlRunService.dueReason(microsoft, now.minusSeconds(25 * 3600),
                List.of(ended(CrawlHealth.Status.OK, now.minusSeconds(24 * 3600))), retryAfter, 3, now))
                .isEqualTo("minimum interval passed");
        // a good crawl 3 h ago: not due
        assertThat(CrawlRunService.dueReason(microsoft, now.minusSeconds(3 * 3600),
                List.of(ended(CrawlHealth.Status.OK, now.minusSeconds(3 * 3600))), retryAfter, 3, now)).isNull();
        // a throttled crawl: not before retryAfter, then due ("retry 1 of 3")
        assertThat(CrawlRunService.dueReason(microsoft, now.minusSeconds(3600),
                List.of(ended(CrawlHealth.Status.PARTIAL, now.minusSeconds(10 * 60))), retryAfter, 3, now)).isNull();
        assertThat(CrawlRunService.dueReason(microsoft, now.minusSeconds(3600),
                List.of(ended(CrawlHealth.Status.PARTIAL, now.minusSeconds(31 * 60))), retryAfter, 3, now))
                .isEqualTo("retry 1 of 3 after a PARTIAL");
        // the second failure in a row is retry 2; the fourth failure in a row has used up the 3 retries
        assertThat(CrawlRunService.dueReason(microsoft, now.minusSeconds(3600),
                List.of(ended(CrawlHealth.Status.FAILED, now.minusSeconds(31 * 60)),
                        ended(CrawlHealth.Status.PARTIAL, now.minusSeconds(70 * 60))), retryAfter, 3, now))
                .isEqualTo("retry 2 of 3 after a FAILED");
        assertThat(CrawlRunService.dueReason(microsoft, now.minusSeconds(3600),
                List.of(ended(CrawlHealth.Status.FAILED, now.minusSeconds(31 * 60)),
                        ended(CrawlHealth.Status.FAILED, now.minusSeconds(70 * 60)),
                        ended(CrawlHealth.Status.PARTIAL, now.minusSeconds(110 * 60)),
                        ended(CrawlHealth.Status.PARTIAL, now.minusSeconds(150 * 60))), retryAfter, 3, now)).isNull();
        // an OK crawl ends the streak: older failures do not count
        assertThat(CrawlRunService.dueReason(microsoft, now.minusSeconds(3600),
                List.of(ended(CrawlHealth.Status.OK, now.minusSeconds(31 * 60)),
                        ended(CrawlHealth.Status.PARTIAL, now.minusSeconds(70 * 60))), retryAfter, 3, now)).isNull();
    }

    private static Company dailyCompany() throws Exception {
        return new Company(1, "microsoft", "Microsoft", "eightfold", JsonMapper.builder().build().readTree("""
                {"host": "m.example", "minCrawlHours": 6, "fullCrawlFromHour": 3, "peekEveryMinutes": 60,
                 "newestSortBy": "timestamp"}"""), null, true, null, null, null);
    }

    @Test
    void theFullCrawlOfADailyCompanyStartsOncePerDayFromItsHourInIndia() throws Exception {
        Company microsoft = dailyCompany();
        java.time.Duration retryAfter = java.time.Duration.ofMinutes(30);
        Instant at = Instant.parse("2026-10-07T06:30:00Z");                     // 12:00 India time on 7 Oct
        Instant yesterday = Instant.parse("2026-10-06T21:40:00Z");               // 03:10 India time on 7 Oct is 21:40Z on the 6th
        Instant today0310 = Instant.parse("2026-10-06T21:40:00Z");

        // 12:00 on the 7th, the last full crawl started 03:10 on the 6th: today's window (03:00 on the 7th) has opened
        assertThat(CrawlRunService.dueReason(microsoft, yesterday.minusSeconds(24 * 3600), List.of(), retryAfter, 3, at))
                .contains("daily window from 03:00 India time");
        // the full crawl already started in today's window (03:10 on the 7th): not again today, whatever the interval
        assertThat(CrawlRunService.dueReason(microsoft, today0310,
                List.of(ended(CrawlHealth.Status.OK, today0310.plusSeconds(4200))), retryAfter, 3, at)).isNull();
        // 02:00 on the 7th: today's window has not opened, yesterday's crawl counts
        assertThat(CrawlRunService.dueReason(microsoft, yesterday.minusSeconds(24 * 3600),
                List.of(ended(CrawlHealth.Status.OK, yesterday.minusSeconds(23 * 3600))), retryAfter, 3,
                Instant.parse("2026-10-06T20:30:00Z"))).isNull();
        // a throttled crawl retries at any time of day, the window does not matter
        assertThat(CrawlRunService.dueReason(microsoft, today0310,
                List.of(ended(CrawlHealth.Status.PARTIAL, at.minusSeconds(31 * 60))), retryAfter, 3, at))
                .isEqualTo("retry 1 of 3 after a PARTIAL");
    }

    @Test
    void theQuickCheckIsHourlyAfterAFullCrawlAndNotWhileTheSiteThrottlesUs() throws Exception {
        Company microsoft = dailyCompany();
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        List<CrawlRunRepository.RunState> good = List.of(ended(CrawlHealth.Status.OK, now.minusSeconds(8 * 3600)));
        List<CrawlRunRepository.RunState> throttled = List.of(ended(CrawlHealth.Status.PARTIAL, now.minusSeconds(3600)));
        Instant full = now.minusSeconds(9 * 3600);

        assertThat(CrawlRunService.peekReason(microsoft, null, null, List.of(), now)).isNull();          // no base yet
        assertThat(CrawlRunService.peekReason(microsoft, full, null, good, now)).isEqualTo("no quick check yet");
        assertThat(CrawlRunService.peekReason(microsoft, full, now.minusSeconds(20 * 60), good, now)).isNull();
        assertThat(CrawlRunService.peekReason(microsoft, full, now.minusSeconds(61 * 60), good, now)).isEqualTo("every 60 minutes");
        assertThat(CrawlRunService.peekReason(microsoft, full, null, throttled, now)).isNull();         // leave a throttling site alone
        assertThat(CrawlRunService.peekReason(slowCompany(2, "qualcomm"), full, null, good, now)).isNull(); // no peekEveryMinutes
    }

    @Test
    void aQuickCheckAndAFullCrawlOfOneCompanyNeverRunTogetherAndAQuickCheckNeverClosesJobs() throws Exception {
        Company microsoft = dailyCompany();
        CrawlService crawlService = mock(CrawlService.class);
        when(crawlService.isSupported(any())).thenReturn(true);
        when(crawlService.serverKey(any())).thenReturn("m.example");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(crawlService.crawl(microsoft)).thenAnswer(call -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return result("microsoft");
        });
        when(crawlService.crawlNewest(microsoft)).thenReturn(result("microsoft"));
        CompanyRepository repository = mock(CompanyRepository.class);
        CrawlRunRepository runs = mock(CrawlRunRepository.class);
        when(runs.recentOkKept(anyLong(), anyInt())).thenReturn(List.of());
        CrawlRunService service = new CrawlRunService(repository, crawlService, runs, mock(GapFillRunner.class),
                new CrawlScheduleProperties(true, java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(1),
                        java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(30), 3, true, "opus", 10, 2),
                new ShutdownSignal());
        CrawlRunService.RunOutcome[] full = new CrawlRunService.RunOutcome[1];
        Thread fullCrawl = new Thread(() -> full[0] = service.crawlOne(microsoft, "schedule"));
        fullCrawl.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        // while the full crawl runs, a quick check is refused and does nothing
        CrawlRunService.RunOutcome refused = service.crawlOne(microsoft, "schedule", CrawlRunRepository.CrawlRun.PEEK);
        assertThat(refused.error()).isEqualTo("already being crawled");
        verify(crawlService, never()).crawlNewest(any());

        release.countDown();
        fullCrawl.join(10_000);
        // now the quick check may run: recorded as PEEK, and closeMissing is not called for it
        CrawlRunService.RunOutcome peek = service.crawlOne(microsoft, "schedule", CrawlRunRepository.CrawlRun.PEEK);
        assertThat(peek.status()).isEqualTo(CrawlHealth.Status.OK);
        org.mockito.ArgumentCaptor<CrawlRunRepository.CrawlRun> recorded = org.mockito.ArgumentCaptor.forClass(CrawlRunRepository.CrawlRun.class);
        verify(runs, times(2)).insert(recorded.capture());
        assertThat(recorded.getAllValues()).extracting(CrawlRunRepository.CrawlRun::kind).containsExactly("FULL", "PEEK");
        verify(runs, times(1)).closeMissing(anyLong(), anyInt(), anyLong());       // only the FULL crawl closed jobs
    }

    @Test
    void onlyCompaniesWithAMinimumIntervalHaveTheirOwnLoop() throws Exception {
        assertThat(CrawlRunService.hasOwnLoop(slowCompany(1, "microsoft"))).isTrue();
        assertThat(CrawlRunService.hasOwnLoop(company("adobe"))).isFalse();
    }

    @Test
    void theRegularRunLeavesTheSlowCompaniesToTheirOwnThreadThatNeverBlocksIt() throws Exception {
        Company fast = company(1, "fast");
        Company slow = slowCompany(2, "slow");
        CrawlService crawlService = mock(CrawlService.class);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(crawlService.crawl(fast)).thenReturn(result("fast"));
        when(crawlService.crawl(slow)).thenAnswer(call -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return result("slow");
        });
        GapFillRunner gapFillRunner = mock(GapFillRunner.class);
        when(gapFillRunner.fillCompany(anyLong(), any(), anyInt())).thenReturn(status(1, 0.01));
        CrawlRunService service = service(crawlService, gapFillRunner, new ShutdownSignal(), fast, slow);

        service.startDueSlowCompanies();                                    // returns at once, the crawl runs on its own thread
        assertThat(inside.await(5, TimeUnit.SECONDS)).as("the slow crawl started").isTrue();
        service.startDueSlowCompanies();                                    // still running: not started a second time

        CrawlRunService.Run regular = service.runAll("schedule", company -> !CrawlRunService.hasOwnLoop(company));
        assertThat(regular.outcomes()).extracting(CrawlRunService.RunOutcome::company).containsExactly("fast");

        release.countDown();
        verify(crawlService, times(1)).crawl(slow);
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
                        java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(30), 3, true, "opus", 10, 2), shutdown);
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
    void aCompanyWithoutNewOrChangedJobsGetsNoGapFill() {
        Company quiet = company(1, "quiet");
        CrawlService crawlService = mock(CrawlService.class);
        GapFillRunner gapFillRunner = mock(GapFillRunner.class);
        when(crawlService.crawl(quiet)).thenReturn(new CrawlResult("quiet", "fake", true, 3, 0, 3, 0, 0, 0, 0, 3, 0, Map.of(),
                List.of(), 10, Instant.now(), 0, 0));

        CrawlRunService.Run run = service(crawlService, gapFillRunner, new ShutdownSignal(), quiet).runAll("manual");

        assertThat(run.gapFills()).isEmpty();
        verify(gapFillRunner, never()).fillCompany(anyLong(), any(), anyInt());
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
