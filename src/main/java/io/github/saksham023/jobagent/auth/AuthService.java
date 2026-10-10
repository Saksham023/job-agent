package io.github.saksham023.jobagent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sign-up, sign-in, refresh and sign-out.
 *
 * A successful sign-up or sign-in returns a Session: a short access token (for the Authorization header) and a refresh
 * token (for the HttpOnly cookie). A refresh trades the refresh token for a new pair; the old one is used up. Using a
 * used-up token again means a copy is in someone else's hands, so every session of that user ends. Two refreshes with
 * the same token at nearly the same moment (two browser tabs) are not treated as theft: the loser just gets a 401.
 */
@Service
public class AuthService {

    /** A refresh token used again within this long after it was replaced is a race between tabs, not a stolen copy. */
    static final Duration RACE_WINDOW = Duration.ofSeconds(10);
    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD_BYTES = 72;                   // BCrypt reads at most 72 bytes
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String BAD_LOGIN = "Wrong email or password";
    private static final String SIGN_IN_AGAIN = "Your session has ended, please sign in again";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** What a sign-in hands to the controller. */
    public record Session(String accessToken, long expiresInSeconds, String refreshToken, UserAccount.View user) {
    }

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyHash;

    @Autowired
    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, TokenService tokens,
                       PasswordEncoder passwords, AuthProperties properties) {
        this(users, refreshTokens, tokens, passwords, properties, Clock.systemUTC());
    }

    AuthService(UserRepository users, RefreshTokenRepository refreshTokens, TokenService tokens, PasswordEncoder passwords,
                AuthProperties properties, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.passwords = passwords;
        this.properties = properties;
        this.clock = clock;
        // checked against when the email is unknown, so a wrong email takes as long as a wrong password
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    @Transactional
    public Session signUp(String email, String password) {
        if (!properties.signupEnabled()) {
            throw new AuthException(HttpStatus.FORBIDDEN, "Sign-up is closed");
        }
        String normalized = normalizeEmail(email);
        checkPassword(password);
        UserAccount user = users.create(normalized, passwords.encode(password))
                .orElseThrow(() -> new AuthException(HttpStatus.CONFLICT, "An account with this email already exists"));
        log.info("New account {} ({})", user.id(), user.role());
        return startSession(user, UUID.randomUUID());
    }

    @Transactional
    public Session signIn(String email, String password) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        UserAccount user = users.findByEmail(normalized).orElse(null);
        String hash = user == null ? dummyHash : user.passwordHash();
        boolean ok = password != null && fitsBcrypt(password) && passwords.matches(password, hash);
        if (user == null || !ok) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, BAD_LOGIN);
        }
        users.touchLogin(user.id());
        return startSession(user, UUID.randomUUID());
    }

    /** Trades a refresh token for a new access token and a new refresh token. */
    @Transactional(noRollbackFor = AuthException.class)          // a detected theft must stay revoked
    public Session refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN);
        }
        RefreshTokenRepository.Stored stored = refreshTokens.find(TokenService.hash(refreshToken))
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN));
        Instant now = clock.instant();
        if (stored.revokedAt() != null) {
            if (stored.revokedAt().plus(RACE_WINDOW).isBefore(now)) {
                refreshTokens.revokeAllForUser(stored.userId());
                log.warn("A used refresh token of user {} was presented again: all of their sessions were ended", stored.userId());
            }
            throw new AuthException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN);
        }
        if (!stored.expiresAt().isAfter(now)) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN);
        }
        if (!refreshTokens.claim(stored.id())) {                   // another tab won the race just now
            throw new AuthException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN);
        }
        UserAccount user = users.findById(stored.userId())
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN));
        String next = tokens.newRefreshToken();
        long nextId = refreshTokens.insert(user.id(), TokenService.hash(next), stored.family(),
                now.plus(properties.refreshTokenTtl()));
        refreshTokens.linkReplacement(stored.id(), nextId);
        return session(user, next);
    }

    /** Ends this browser's session (the token's sign-in and all tokens that came from it). */
    @Transactional
    public void signOut(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokens.find(TokenService.hash(refreshToken)).ifPresent(t -> refreshTokens.revokeFamily(t.family()));
    }

    /** Ends every session of this user, on every device. */
    @Transactional
    public void signOutEverywhere(long userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    public UserAccount.View me(long userId) {
        return users.findById(userId).map(UserAccount::view)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, SIGN_IN_AGAIN));
    }

    /** Daily: drop refresh tokens that expired more than a day ago. */
    @Scheduled(cron = "0 15 4 * * *")
    void deleteExpiredTokens() {
        int deleted = refreshTokens.deleteExpired();
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Session startSession(UserAccount user, UUID family) {
        String refresh = tokens.newRefreshToken();
        refreshTokens.insert(user.id(), TokenService.hash(refresh), family, clock.instant().plus(properties.refreshTokenTtl()));
        return session(user, refresh);
    }

    private Session session(UserAccount user, String refreshToken) {
        return new Session(tokens.accessToken(user), properties.accessTokenTtl().toSeconds(), refreshToken, user.view());
    }

    static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !EMAIL.matcher(normalized).matches()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "Please enter a valid email address");
        }
        return normalized;
    }

    static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "The password needs at least " + MIN_PASSWORD + " characters");
        }
        if (!fitsBcrypt(password)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "The password is too long (at most " + MAX_PASSWORD_BYTES + " bytes)");
        }
    }

    private static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }
}
