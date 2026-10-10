package io.github.saksham023.jobagent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-1234";
    private static final UserAccount ADMIN = new UserAccount(7, "a@b.co", "x", UserAccount.Role.ADMIN, Instant.now(), null);

    private static AuthProperties props(String secret) {
        return new AuthProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7), true, true);
    }

    @Test
    void anAccessTokenCarriesTheUserAndTheRoleAndIsCheckedBySignature() {
        TokenService tokens = new TokenService(props(SECRET));
        Jwt jwt = tokens.decoder().decode(tokens.accessToken(ADMIN));
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("a@b.co");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));

        TokenService other = new TokenService(props("another-secret-that-is-also-long-enough"));
        assertThatThrownBy(() -> other.decoder().decode(tokens.accessToken(ADMIN))).isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredAccessTokenIsRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String old = new TokenService(props(SECRET), past).accessToken(ADMIN);
        assertThatThrownBy(() -> new TokenService(props(SECRET)).decoder().decode(old)).isInstanceOf(JwtException.class);
    }

    @Test
    void aShortSecretIsRefusedAndABlankOneGetsARandomKey() {
        assertThatThrownBy(() -> new TokenService(props("short"))).isInstanceOf(IllegalStateException.class);
        TokenService random = new TokenService(props(""));
        assertThat(random.decoder().decode(random.accessToken(ADMIN)).getSubject()).isEqualTo("7");
    }

    @Test
    void refreshTokensAreRandomAndOnlyTheirHashIsStored() {
        TokenService tokens = new TokenService(props(SECRET));
        String a = tokens.newRefreshToken();
        assertThat(a).isNotEqualTo(tokens.newRefreshToken()).hasSize(43);
        assertThat(TokenService.hash(a)).hasSize(64).isEqualTo(TokenService.hash(a)).isNotEqualTo(a);
    }
}
