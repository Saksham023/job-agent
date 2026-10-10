package io.github.saksham023.jobagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * The shared secret for scripts and the MCP connection. It runs inside the Spring Security chain (SecurityConfig).
 *
 * A request that carries the key as "X-API-Key: key" or "Authorization: Bearer key" is signed in as an ADMIN (for
 * curl, scripts and the deploy) and marked TRUSTED (request attribute TRUSTED: no rate limit, no size caps on /api/**).
 * /mcp still needs the key itself (401 without it), exactly as before accounts existed. Every other path is decided by
 * SecurityConfig: /api/** needs a signed-in user, /admin/** an admin (a signed-in admin or this key).
 * The key is jobagent.security.api-key (env JOBAGENT_SECURITY_API_KEY, never in git). When it is blank the
 * protection is off, so local development works as before; a warning is logged at startup then.
 * Comparison is constant-time, so the key cannot be guessed from response times.
 *
 * Shortcomings: one key for everybody, no lockout after wrong keys; it must be used over HTTPS when the app is
 * reachable from the internet (the key travels in a header).
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    /** Request attribute set when the request carried the right key. */
    public static final String TRUSTED = "jobagent.trusted";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final byte[] key;

    public ApiKeyFilter(@Value("${jobagent.security.api-key:}") String apiKey) {
        this.key = apiKey == null || apiKey.isBlank() ? null : apiKey.strip().getBytes(StandardCharsets.UTF_8);
        if (key == null) {
            log.warn("jobagent.security.api-key is not set: /mcp is OPEN and no script key works. Set it before exposing the app.");
        } else {
            log.info("API key: required for /mcp, accepted as an admin everywhere");
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

    /** Paths that need the key itself, whatever else the request carries. */
    static boolean protectedPath(String path) {
        return path.equals("/mcp") || path.startsWith("/mcp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(request.getHeader("X-API-Key")) || matches(bearer(request.getHeader("Authorization")))) {
            request.setAttribute(TRUSTED, Boolean.TRUE);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    "api-key", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            chain.doFilter(request, response);
            return;
        }
        if (!protectedPath(request.getRequestURI())) {
            chain.doFilter(request, response);                 // no (or a wrong) key: SecurityConfig decides
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
