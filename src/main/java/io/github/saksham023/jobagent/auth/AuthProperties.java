package io.github.saksham023.jobagent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * jobagent.auth.*: accounts and tokens.
 *
 * @param jwtSecret        HMAC key that signs the access tokens (env JOBAGENT_AUTH_JWT_SECRET, at least 32 characters, never in
 *                         git). Blank = a random key per start (fine locally: access tokens then die on a restart, and the
 *                         refresh cookie simply gets a new one)
 * @param accessTokenTtl   how long an access token (JWT) is valid
 * @param refreshTokenTtl  how long a refresh token (the cookie) is valid; every refresh starts a new period
 * @param signupEnabled    false = nobody can create an account any more (existing ones still sign in)
 * @param cookieSecure     the refresh cookie is sent over HTTPS only; browsers treat http://localhost as secure, so it can
 *                         stay true locally
 */
@ConfigurationProperties("jobagent.auth")
public record AuthProperties(
        @DefaultValue("") String jwtSecret,
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P7D") Duration refreshTokenTtl,
        @DefaultValue("true") boolean signupEnabled,
        @DefaultValue("true") boolean cookieSecure) {
}
