package io.github.saksham023.jobagent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sign-up, sign-in and refresh rotation against in-memory stand-ins for the two tables. */
class AuthServiceTest {

    /** users in memory. */
    static final class Users extends UserRepository {
        final List<UserAccount> rows = new ArrayList<>();
        Users() { super(null); }
        @Override public Optional<UserAccount> create(String email, String hash) {
            if (rows.stream().anyMatch(u -> u.email().equals(email))) return Optional.empty();
            UserAccount u = new UserAccount(rows.size() + 1, email, hash, UserAccount.Role.USER, Instant.now(), null);
            rows.add(u);
            return Optional.of(u);
        }
        @Override public Optional<UserAccount> findByEmail(String email) { return rows.stream().filter(u -> u.email().equals(email)).findFirst(); }
        @Override public Optional<UserAccount> findById(long id) { return rows.stream().filter(u -> u.id() == id).findFirst(); }
        @Override public void touchLogin(long id) { }
    }

    /** refresh_tokens in memory. */
    static final class Tokens extends RefreshTokenRepository {
        record Row(long id, long userId, String hash, UUID family, Instant expiresAt, Instant[] revokedAt) {}
        final List<Row> rows = new ArrayList<>();
        Instant now = Instant.parse("2026-10-10T10:00:00Z");
        Tokens() { super(null); }
        @Override public long insert(long userId, String hash, UUID family, Instant expiresAt) {
            rows.add(new Row(rows.size() + 1, userId, hash, family, expiresAt, new Instant[1]));
            return rows.size();
        }
        @Override public Optional<Stored> find(String hash) {
            return rows.stream().filter(r -> r.hash().equals(hash)).findFirst()
                    .map(r -> new Stored(r.id(), r.userId(), r.family(), r.expiresAt(), r.revokedAt()[0]));
        }
        @Override public boolean claim(long id) {
            Row r = rows.get((int) id - 1);
            if (r.revokedAt()[0] != null) return false;
            r.revokedAt()[0] = now;
            return true;
        }
        @Override public void linkReplacement(long id, long next) { }
        @Override public void revokeFamily(UUID family) { rows.stream().filter(r -> r.family().equals(family) && r.revokedAt()[0] == null).forEach(r -> r.revokedAt()[0] = now); }
        @Override public void revokeAllForUser(long userId) { rows.stream().filter(r -> r.userId() == userId && r.revokedAt()[0] == null).forEach(r -> r.revokedAt()[0] = now); }
        long active(long userId) { return rows.stream().filter(r -> r.userId() == userId && r.revokedAt()[0] == null).count(); }
    }

    private final Users users = new Users();
    private final Tokens tokens = new Tokens();
    private final Clock[] clock = {Clock.fixed(tokens.now, ZoneOffset.UTC)};

    private AuthService service(boolean signupEnabled) {
        AuthProperties props = new AuthProperties("a-test-secret-that-is-long-enough-1234", Duration.ofMinutes(15),
                Duration.ofDays(7), signupEnabled, true);
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clock[0].instant(); }
        };
        return new AuthService(users, tokens, new TokenService(props), new BCryptPasswordEncoder(4), props, moving);
    }

    private void advance(Duration d) {
        Instant next = clock[0].instant().plus(d);
        clock[0] = Clock.fixed(next, ZoneOffset.UTC);
        tokens.now = next;
    }

    @Test
    void signUpThenSignInWithTheSamePasswordOnly() {
        AuthService auth = service(true);
        AuthService.Session s = auth.signUp("  Me@Example.COM ", "correct horse");
        assertThat(s.user().email()).isEqualTo("me@example.com");
        assertThat(s.user().role()).isEqualTo(UserAccount.Role.USER);
        assertThat(users.rows.getFirst().passwordHash()).doesNotContain("correct horse");

        assertThat(auth.signIn("ME@example.com", "correct horse").user().id()).isEqualTo(s.user().id());
        assertThatThrownBy(() -> auth.signIn("me@example.com", "wrong password")).hasMessage("Wrong email or password");
        assertThatThrownBy(() -> auth.signIn("nobody@example.com", "correct horse")).hasMessage("Wrong email or password");
    }

    @Test
    void signUpChecksTheInputAndTheSwitch() {
        AuthService auth = service(true);
        assertThatThrownBy(() -> auth.signUp("not-an-email", "longenough1")).satisfies(e -> assertThat(((AuthException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> auth.signUp("a@b.co", "short")).hasMessageContaining("at least 8");
        assertThatThrownBy(() -> auth.signUp("a@b.co", "x".repeat(73))).hasMessageContaining("too long");
        auth.signUp("a@b.co", "longenough1");
        assertThatThrownBy(() -> auth.signUp("A@B.CO", "longenough2")).satisfies(e -> assertThat(((AuthException) e).status()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service(false).signUp("c@d.co", "longenough1")).satisfies(e -> assertThat(((AuthException) e).status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void aRefreshReplacesTheTokenAndTheOldOneStopsWorking() {
        AuthService auth = service(true);
        AuthService.Session first = auth.signUp("a@b.co", "longenough1");
        AuthService.Session second = auth.refresh(first.refreshToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.accessToken()).isNotBlank();
        assertThat(tokens.active(1)).isEqualTo(1);

        // the old token again a moment later (a second tab racing): just a 401, the new session survives
        assertThatThrownBy(() -> auth.refresh(first.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(tokens.active(1)).isEqualTo(1);
    }

    @Test
    void aUsedTokenPresentedAgainLaterEndsEverySessionOfTheUser() {
        AuthService auth = service(true);
        AuthService.Session laptop = auth.signUp("a@b.co", "longenough1");
        auth.signIn("a@b.co", "longenough1");                       // a second device
        AuthService.Session rotated = auth.refresh(laptop.refreshToken());
        assertThat(tokens.active(1)).isEqualTo(2);

        advance(Duration.ofMinutes(5));                              // well past the race window: a stolen copy
        assertThatThrownBy(() -> auth.refresh(laptop.refreshToken())).isInstanceOf(AuthException.class);
        assertThat(tokens.active(1)).isZero();
        assertThatThrownBy(() -> auth.refresh(rotated.refreshToken())).isInstanceOf(AuthException.class);
    }

    @Test
    void expiredTokensAndSignOutEndTheSession() {
        AuthService auth = service(true);
        AuthService.Session s = auth.signUp("a@b.co", "longenough1");
        advance(Duration.ofDays(8));
        assertThatThrownBy(() -> auth.refresh(s.refreshToken())).isInstanceOf(AuthException.class);

        AuthService.Session fresh = auth.signIn("a@b.co", "longenough1");
        auth.signOut(fresh.refreshToken());
        assertThatThrownBy(() -> auth.refresh(fresh.refreshToken())).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> auth.refresh(null)).isInstanceOf(AuthException.class);
    }
}
