package io.github.saksham023.jobagent.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Accounts over HTTP.
 *
 * POST /api/v1/auth/signup       {email, password} -> 201, signed in
 * POST /api/v1/auth/login        {email, password} -> signed in
 * POST /api/v1/auth/refresh      (refresh cookie)  -> a new access token and a new cookie
 * POST /api/v1/auth/logout       (refresh cookie)  -> this browser signed out
 * POST /api/v1/auth/logout-all   (access token)    -> every session of the user ended
 * GET  /api/v1/me                (access token)    -> who is signed in
 *
 * "Signed in" = body {accessToken, tokenType, expiresIn, user} and the refresh token in an HttpOnly cookie, which only
 * the /api/v1/auth paths receive (the browser never sends it anywhere else, and scripts cannot read it).
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    static final String COOKIE = "jobagent_refresh";
    static final String COOKIE_PATH = "/api/v1/auth";

    /** Sign-up / sign-in form. */
    public record Credentials(String email, String password) {
    }

    /** What a successful sign-in returns in the body. */
    public record SignedIn(String accessToken, String tokenType, long expiresIn, UserAccount.View user) {
    }

    private final AuthService auth;
    private final AuthProperties properties;

    public AuthController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @PostMapping("/auth/signup")
    public ResponseEntity<SignedIn> signUp(@RequestBody Credentials credentials) {
        return signedIn(HttpStatus.CREATED, auth.signUp(credentials.email(), credentials.password()));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<SignedIn> login(@RequestBody Credentials credentials) {
        return signedIn(HttpStatus.OK, auth.signIn(credentials.email(), credentials.password()));
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<SignedIn> refresh(@CookieValue(name = COOKIE, required = false) String refreshToken) {
        return signedIn(HttpStatus.OK, auth.refresh(refreshToken));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String refreshToken) {
        auth.signOut(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @PostMapping("/auth/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
        auth.signOutEverywhere(Long.parseLong(jwt.getSubject()));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/me")
    public UserAccount.View me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(Long.parseLong(jwt.getSubject()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> failed(AuthException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e.status() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());   // a dead cookie is no use
        }
        return response.body(Map.of("error", e.getMessage()));
    }

    private ResponseEntity<SignedIn> signedIn(HttpStatus status, AuthService.Session session) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), properties.refreshTokenTtl()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SignedIn(session.accessToken(), "Bearer", session.expiresInSeconds(), session.user()));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
