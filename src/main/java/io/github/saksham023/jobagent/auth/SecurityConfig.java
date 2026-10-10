package io.github.saksham023.jobagent.auth;

import io.github.saksham023.jobagent.common.ApiKeyFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.util.Map;

/**
 * Who may call what. Every request is stateless: no server session, the access token (JWT) in the Authorization header
 * says who the caller is.
 *
 * | Path                                         | Who                                                          |
 * |----------------------------------------------|--------------------------------------------------------------|
 * | /api/v1/auth/signup, login, refresh, logout  | anyone (that is how one signs in)                            |
 * | /api/** (jobs, filters, me, ...)             | any signed-in user (USER or ADMIN)                           |
 * | /admin/**, /actuator/** except health        | ADMIN (a signed-in admin, or a request with the shared API key) |
 * | /actuator/health                             | anyone (the deploy's health check; it only says UP)          |
 * | /mcp                                         | unchanged: the shared API key (ApiKeyFilter)                 |
 * | everything else (the web UI's files)         | anyone: the UI itself shows the sign-in screen               |
 *
 * CSRF protection is off: the API is authorized by a header a browser never adds by itself, and the refresh cookie is
 * SameSite=Strict and only sent to /api/v1/auth.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyFilter apiKeyFilter, JwtDecoder jwtDecoder) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.cacheControl(c -> c.disable()))          // the UI's static files may be cached as before
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/admin", "/admin/**", "/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/mcp", "/mcp/**").permitAll()  // guarded by ApiKeyFilter, as before
                        .anyRequest().permitAll())
                .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(jwtOnly())
                        .authenticationEntryPoint(json(HttpServletResponse.SC_UNAUTHORIZED, "Please sign in"))
                        .jwt(j -> j.decoder(jwtDecoder).jwtAuthenticationConverter(roles())))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(json(HttpServletResponse.SC_UNAUTHORIZED, "Please sign in"))
                        .accessDeniedHandler(denied()));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(TokenService tokens) {
        return tokens.decoder();
    }

    /** BCrypt with cost 12 (~250 ms per check); the "{bcrypt}" prefix in the stored hash lets the algorithm change later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    /** ApiKeyFilter runs inside the security chain only (see above), not a second time as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilterRegistration(ApiKeyFilter filter) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /** The JWT's "role" claim ("ADMIN") becomes the authority ROLE_ADMIN. */
    private static JwtAuthenticationConverter roles() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Only bearer values shaped like a JWT (three dot-separated parts) are read as access tokens. The shared API key may
     * also come as "Authorization: Bearer <key>" (ApiKeyFilter handles it); it must not be mistaken for a broken JWT.
     */
    static BearerTokenResolver jwtOnly() {
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return request -> {
            String token = standard.resolve(request);
            return token != null && token.chars().filter(c -> c == '.').count() == 2 ? token : null;
        };
    }

    private static AuthenticationEntryPoint json(int status, String message) {
        return (request, response, e) -> write(response, status, message);
    }

    private static AccessDeniedHandler denied() {
        return (request, response, e) -> write(response, HttpServletResponse.SC_FORBIDDEN, "Admins only");
    }

    private static void write(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
