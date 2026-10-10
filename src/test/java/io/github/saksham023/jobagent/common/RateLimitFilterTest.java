package io.github.saksham023.jobagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The token bucket per client, with a clock the test controls. */
class RateLimitFilterTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private void advanceSeconds(long seconds) {
        nanos.addAndGet(seconds * 1_000_000_000L);
    }

    private MockHttpServletResponse call(RateLimitFilter filter, String path, String remoteAddr, String header, String value)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.setRemoteAddr(remoteAddr);
        if (header != null) {
            request.addHeader(header, value);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void aClientMayBurstThenIsStoppedWithRetryAfter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(60, 3, "", nanos::get);       // 1 token per second, bursts of 3
        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse refused = call(filter, "/api/v1/jobs", "1.1.1.1", null, null);
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void tokensComeBackOverTime() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(60, 2, "", nanos::get);
        call(filter, "/api/v1/jobs", "1.1.1.1", null, null);
        call(filter, "/api/v1/jobs", "1.1.1.1", null, null);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(429);
        advanceSeconds(1);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(429);
        advanceSeconds(3600);                                                      // never more than the burst
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(429);
    }

    @Test
    void everyClientHasItsOwnBucket() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(60, 1, "", nanos::get);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(429);
        assertThat(call(filter, "/api/v1/jobs", "2.2.2.2", null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void behindAProxyTheConfiguredHeaderIdentifiesTheClient() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(60, 1, "CF-Connecting-IP", nanos::get);
        // every request comes from the proxy's address; the header tells the visitors apart
        assertThat(call(filter, "/api/v1/jobs", "10.0.0.1", "CF-Connecting-IP", "5.5.5.5").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/jobs", "10.0.0.1", "CF-Connecting-IP", "5.5.5.5").getStatus()).isEqualTo(429);
        assertThat(call(filter, "/api/v1/jobs", "10.0.0.1", "CF-Connecting-IP", "6.6.6.6").getStatus()).isEqualTo(200);
        // a list: the last entry is the one our own proxy added (earlier ones can be forged by the caller)
        assertThat(call(filter, "/api/v1/jobs", "10.0.0.1", "CF-Connecting-IP", "9.9.9.9, 5.5.5.5").getStatus()).isEqualTo(429);
        assertThat(call(filter, "/api/v1/jobs", "10.0.0.1", "CF-Connecting-IP", "5.5.5.5, 6.6.6.7").getStatus()).isEqualTo(200);
    }

    @Test
    void withoutTheConfiguredHeaderForwardedHeadersAreIgnored() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(60, 1, "", nanos::get);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", "X-Forwarded-For", "7.7.7.7").getStatus()).isEqualTo(200);
        // a caller cannot escape the limit by inventing an address
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", "X-Forwarded-For", "8.8.8.8").getStatus()).isEqualTo(429);
    }

    @Test
    void onlyThePublicApiIsLimited() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(60, 1, "", nanos::get);
        call(filter, "/api/v1/jobs", "1.1.1.1", null, null);
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(429);
        assertThat(call(filter, "/admin/crawl", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/assets/index.js", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/apiary", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void aTrustedRequestIsNeverLimited() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(60, 1, "", nanos::get);
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/jobs");
            request.setRequestURI("/api/v1/jobs");
            request.setRemoteAddr("1.1.1.1");
            request.setAttribute(ApiKeyFilter.TRUSTED, Boolean.TRUE);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        // and it did not use up the client's own tokens
        assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void zeroTurnsTheLimitOff() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(0, 30, "", nanos::get);
        for (int i = 0; i < 100; i++) {
            assertThat(call(filter, "/api/v1/jobs", "1.1.1.1", null, null).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void signInHasItsOwnSmallBucket() {
        long[] now = {0};
        RateLimitFilter filter = new RateLimitFilter(120, 30, null, 10, 5, () -> now[0]);
        for (int i = 0; i < 5; i++) {
            assertThat(filter.tryTakeAuth("1.2.3.4")).isZero();
        }
        assertThat(filter.tryTakeAuth("1.2.3.4")).isPositive();      // the 6th sign-in attempt at once waits
        assertThat(filter.tryTake("1.2.3.4")).isZero();              // the ordinary API is not affected
        now[0] += 6_000_000_000L;                                    // 10 per minute = one every 6 s
        assertThat(filter.tryTakeAuth("1.2.3.4")).isZero();
    }
}
