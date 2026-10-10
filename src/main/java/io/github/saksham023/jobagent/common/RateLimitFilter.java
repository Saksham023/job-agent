package io.github.saksham023.jobagent.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Limits how fast one client may call the public API (/api/**), so a stranger cannot hammer the database.
 *
 * Token bucket per client: a client may send `burst` requests at once, and gets requestsPerMinute / 60 tokens back
 * every second. An empty bucket answers 429 with a Retry-After header. /admin and /mcp are not limited here (they
 * need the API key), neither are the web UI's files, and neither are requests that carry the API key (trusted).
 *
 * Who is "one client": the connection's address, or, behind a tunnel or proxy (where every request comes from the
 * proxy), the header named in jobagent.security.rate-limit.client-ip-header (Cloudflare: CF-Connecting-IP; Tailscale
 * Funnel: X-Forwarded-For, where the last entry is the one the proxy added). Set that header ONLY when all traffic
 * really comes through that proxy, otherwise a caller could invent a new address in every request and never be
 * limited.
 *
 * Sign-in and sign-up (/api/v1/auth/login, /api/v1/auth/signup) have their own, much smaller bucket per client
 * (jobagent.security.rate-limit.auth-requests-per-minute / auth-burst), so passwords cannot be guessed at speed.
 *
 * In memory, for one app instance (a restart resets the counters); several instances would need a shared store such
 * as Redis. Idle clients are dropped so the map cannot grow without bound.
 */
@Component
@Order(2)                                       // after the API key filter, which marks trusted requests
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long IDLE_NANOS = 10L * 60 * 1_000_000_000L;
    private static final int SWEEP_ABOVE = 10_000;

    /** Tokens as a double so a slow refill rate (a fraction of a token per call) still adds up. */
    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long nowNanos) {
            this.tokens = tokens;
            this.lastNanos = nowNanos;
        }
    }

    /** Paths with the small sign-in bucket. */
    static final java.util.Set<String> AUTH_PATHS = java.util.Set.of("/api/v1/auth/login", "/api/v1/auth/signup");

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> authBuckets = new ConcurrentHashMap<>();
    private final double refillPerNano;
    private final int burst;
    private final double authRefillPerNano;
    private final int authBurst;
    private final boolean enabled;
    private final String clientIpHeader;
    private final LongSupplier nanoTime;

    @Autowired
    public RateLimitFilter(@Value("${jobagent.security.rate-limit.requests-per-minute:120}") int requestsPerMinute,
                           @Value("${jobagent.security.rate-limit.burst:30}") int burst,
                           @Value("${jobagent.security.rate-limit.client-ip-header:}") String clientIpHeader,
                           @Value("${jobagent.security.rate-limit.auth-requests-per-minute:10}") int authRequestsPerMinute,
                           @Value("${jobagent.security.rate-limit.auth-burst:5}") int authBurst) {
        this(requestsPerMinute, burst, clientIpHeader, authRequestsPerMinute, authBurst, System::nanoTime);
    }

    RateLimitFilter(int requestsPerMinute, int burst, String clientIpHeader, LongSupplier nanoTime) {
        this(requestsPerMinute, burst, clientIpHeader, 10, 5, nanoTime);
    }

    RateLimitFilter(int requestsPerMinute, int burst, String clientIpHeader, int authRequestsPerMinute, int authBurst,
                    LongSupplier nanoTime) {
        this.enabled = requestsPerMinute > 0 && burst > 0;
        this.refillPerNano = requestsPerMinute / 60.0 / 1_000_000_000.0;
        this.burst = burst;
        this.authRefillPerNano = Math.max(1, authRequestsPerMinute) / 60.0 / 1_000_000_000.0;
        this.authBurst = Math.max(1, authBurst);
        this.clientIpHeader = clientIpHeader == null || clientIpHeader.isBlank() ? null : clientIpHeader.strip();
        this.nanoTime = nanoTime;
        if (enabled) {
            log.info("Rate limit on /api/**: {} requests per minute per client, bursts of {}; sign-in and sign-up {} per minute, bursts of {}{}",
                    requestsPerMinute, burst, authRequestsPerMinute, authBurst,
                    this.clientIpHeader == null ? "" : ", client address from header " + this.clientIpHeader);
        } else {
            log.warn("Rate limit is off (jobagent.security.rate-limit.requests-per-minute <= 0)");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled || ApiKeyFilter.isTrusted(request) || !(path.equals("/api") || path.startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientOf(request);
        long waitSeconds = AUTH_PATHS.contains(request.getRequestURI())
                ? take(authBuckets, client, authRefillPerNano, authBurst)
                : tryTake(client);
        if (waitSeconds == 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(waitSeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"too many requests, try again in " + waitSeconds + " s\"}");
    }

    /** @return 0 when a token was taken, else the seconds until one is available */
    long tryTake(String client) {
        return take(buckets, client, refillPerNano, burst);
    }

    /** The sign-in bucket (tests). */
    long tryTakeAuth(String client) {
        return take(authBuckets, client, authRefillPerNano, authBurst);
    }

    private long take(Map<String, Bucket> map, String client, double refill, int size) {
        long now = nanoTime.getAsLong();
        if (map.size() > SWEEP_ABOVE) {
            map.values().removeIf(b -> now - b.lastNanos > IDLE_NANOS);
        }
        Bucket bucket = map.computeIfAbsent(client, c -> new Bucket(size, now));
        synchronized (bucket) {
            bucket.tokens = Math.min(size, bucket.tokens + (now - bucket.lastNanos) * refill);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - bucket.tokens) / refill / 1_000_000_000.0));
        }
    }

    private String clientOf(HttpServletRequest request) {
        if (clientIpHeader != null) {
            String value = request.getHeader(clientIpHeader);
            if (value != null && !value.isBlank()) {
                // X-Forwarded-For style lists: every proxy appends the address it saw, so the LAST entry is the one our
                // own proxy added; earlier entries can be forged by the caller
                String[] parts = value.split(",");
                return parts[parts.length - 1].strip();
            }
        }
        return request.getRemoteAddr();
    }
}
