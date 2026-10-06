package io.github.saksham023.jobagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * A shared secret in front of the endpoints that spend money or change data: /admin/** (crawls, Opus runs, profiles)
 * and /mcp (the AI search). Everything else stays open: the read-only web API /api/v1/** and the health check.
 *
 * A request passes when it carries the key as "X-API-Key: key" or "Authorization: Bearer key"; otherwise it gets 401.
 * A request with the right key is also marked TRUSTED on every path (request attribute TRUSTED): on the open API
 * /api/** that lifts the rate limit and the size caps, so your own scripts are never throttled.
 * The key is jobagent.security.api-key (env JOBAGENT_SECURITY_API_KEY, never in git). When it is blank the
 * protection is off, so local development works as before; a warning is logged at startup then.
 * Comparison is constant-time, so the key cannot be guessed from response times.
 *
 * Shortcomings: one key for everybody (no users, no roles), no rate limiting, no lockout after wrong keys; it
 * must be used over HTTPS when the app is reachable from the internet (the key travels in a header).
 */
@Component
@Order(1)                                       // before the rate limit, which looks at the TRUSTED mark
public class ApiKeyFilter extends OncePerRequestFilter {

    /** Request attribute set when the request carried the right key. */
    public static final String TRUSTED = "jobagent.trusted";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final byte[] key;

    public ApiKeyFilter(@Value("${jobagent.security.api-key:}") String apiKey) {
        this.key = apiKey == null || apiKey.isBlank() ? null : apiKey.strip().getBytes(StandardCharsets.UTF_8);
        if (key == null) {
            log.warn("jobagent.security.api-key is not set: /admin and /mcp are OPEN. Set it before exposing the app.");
        } else {
            log.info("API key protection is on for /admin/** and /mcp");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return key == null;
    }

    /** True when the request carried the right key (always false while no key is configured). */
    public static boolean isTrusted(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(TRUSTED));
    }

    static boolean protectedPath(String path) {
        return path.equals("/admin") || path.startsWith("/admin/") || path.equals("/mcp") || path.startsWith("/mcp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(request.getHeader("X-API-Key")) || matches(bearer(request.getHeader("Authorization")))) {
            request.setAttribute(TRUSTED, Boolean.TRUE);
            chain.doFilter(request, response);
            return;
        }
        if (!protectedPath(request.getRequestURI())) {
            chain.doFilter(request, response);                 // open path, no (or a wrong) key: an ordinary visitor
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"missing or wrong API key\"}");
    }

    private boolean matches(String given) {
        return given != null && MessageDigest.isEqual(key, given.strip().getBytes(StandardCharsets.UTF_8));
    }

    private static String bearer(String authorization) {
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7) : null;
    }
}
