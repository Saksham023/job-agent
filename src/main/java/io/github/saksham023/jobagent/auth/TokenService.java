package io.github.saksham023.jobagent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The two kinds of token.
 *
 * Access token: a JWT signed with HMAC-SHA256 (jobagent.auth.jwt-secret), valid for jobagent.auth.access-token-ttl. It
 * carries the user id (subject), the email and the role, so a request is checked without a database lookup. Short-lived
 * on purpose: it cannot be revoked before it expires.
 *
 * Refresh token: 32 random bytes (base64url), not a JWT. Only its SHA-256 hash is stored, so a copy of the database
 * does not hand out sessions. Being random and long, a fast hash is enough (no BCrypt needed, unlike passwords).
 */
@Component
public class TokenService {

    static final String ISSUER = "jobagent";
    static final String ROLE_CLAIM = "role";
    static final String EMAIL_CLAIM = "email";
    private static final int MIN_SECRET_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final SecureRandom random = new SecureRandom();
    private final AuthProperties properties;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    @Autowired
    public TokenService(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    TokenService(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        SecretKey key = new SecretKeySpec(secret(properties.jwtSecret()), "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).build();
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = nimbus;
    }

    /** A signed access token for this account. */
    public String accessToken(UserAccount user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(Long.toString(user.id()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim(EMAIL_CLAIM, user.email())
                .claim(ROLE_CLAIM, user.role().name())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /** Checks access tokens: signature, expiry, issuer. Used by Spring Security for every request with a bearer token. */
    public JwtDecoder decoder() {
        return decoder;
    }

    /** A new random refresh token (what goes into the cookie). */
    public String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** What is stored for a refresh token: its SHA-256 in hex. */
    public static String hash(String refreshToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] secret(String configured) {
        if (configured == null || configured.isBlank()) {
            log.warn("jobagent.auth.jwt-secret is not set: using a random key until the next restart (set JOBAGENT_AUTH_JWT_SECRET)");
            byte[] bytes = new byte[MIN_SECRET_BYTES];
            random.nextBytes(bytes);
            return bytes;
        }
        byte[] bytes = configured.strip().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jobagent.auth.jwt-secret must be at least " + MIN_SECRET_BYTES + " characters");
        }
        return bytes;
    }
}
