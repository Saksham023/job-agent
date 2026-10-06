package io.github.saksham023.jobagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Which requests the API key filter lets through, without a server. */
class ApiKeyFilterTest {

    private static final String KEY = "s3cret-key";

    /** Runs one request through the filter; returns the response (200 = passed on to the controller). */
    private static MockHttpServletResponse call(ApiKeyFilter filter, String path, String header, String value)
            throws Exception {
        return call(filter, path, header, value, new MockHttpServletRequest("POST", path));
    }

    private static MockHttpServletResponse call(ApiKeyFilter filter, String path, String header, String value,
                                                MockHttpServletRequest request) throws Exception {
        request.setRequestURI(path);
        if (header != null) {
            request.addHeader(header, value);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void adminAndMcpNeedTheKey() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(KEY);
        assertThat(call(filter, "/admin/crawl", null, null).getStatus()).isEqualTo(401);
        assertThat(call(filter, "/mcp", null, null).getStatus()).isEqualTo(401);
        assertThat(call(filter, "/admin/crawl", "X-API-Key", "wrong").getStatus()).isEqualTo(401);
        assertThat(call(filter, "/admin/crawl", "Authorization", "Bearer wrong").getStatus()).isEqualTo(401);
        assertThat(call(filter, "/admin/crawl", "Authorization", "Basic " + KEY).getStatus()).isEqualTo(401);
    }

    @Test
    void theKeyInEitherHeaderPasses() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(KEY);
        assertThat(call(filter, "/admin/crawl", "X-API-Key", KEY).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/mcp", "Authorization", "Bearer " + KEY).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/admin/profiles/p-abc", "authorization", "bearer " + KEY).getStatus()).isEqualTo(200);
    }

    @Test
    void thePublicApiAndTheHealthCheckStayOpen() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(KEY);
        assertThat(call(filter, "/api/v1/jobs", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/v1/meta", null, null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "/actuator/health", null, null).getStatus()).isEqualTo(200);
    }

    @Test
    void aRequestWithTheKeyIsMarkedTrustedEvenOnTheOpenApi() throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(KEY);
        MockHttpServletRequest withKey = new MockHttpServletRequest("GET", "/api/v1/jobs");
        call(filter, "/api/v1/jobs", "X-API-Key", KEY, withKey);
        assertThat(ApiKeyFilter.isTrusted(withKey)).isTrue();

        MockHttpServletRequest wrongKey = new MockHttpServletRequest("GET", "/api/v1/jobs");
        assertThat(call(filter, "/api/v1/jobs", "X-API-Key", "wrong", wrongKey).getStatus()).isEqualTo(200);   // still open
        assertThat(ApiKeyFilter.isTrusted(wrongKey)).isFalse();

        MockHttpServletRequest noKey = new MockHttpServletRequest("GET", "/api/v1/jobs");
        call(filter, "/api/v1/jobs", null, null, noKey);
        assertThat(ApiKeyFilter.isTrusted(noKey)).isFalse();
    }

    @Test
    void lookAlikePathsAreNotMistakenForProtectedOnes() {
        assertThat(ApiKeyFilter.protectedPath("/admin")).isTrue();
        assertThat(ApiKeyFilter.protectedPath("/admin/search/1/more")).isTrue();
        assertThat(ApiKeyFilter.protectedPath("/mcp/")).isTrue();
        assertThat(ApiKeyFilter.protectedPath("/administrator")).isFalse();
        assertThat(ApiKeyFilter.protectedPath("/mcpx")).isFalse();
    }

    @Test
    void withoutAKeyTheProtectionIsOff() throws Exception {
        assertThat(call(new ApiKeyFilter(""), "/admin/crawl", null, null).getStatus()).isEqualTo(200);
        assertThat(call(new ApiKeyFilter(null), "/mcp", null, null).getStatus()).isEqualTo(200);
    }
}
