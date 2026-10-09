package org.adancau.doneapi.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.adancau.doneapi.common.ApiErrors;
import org.adancau.doneapi.persistence.AuthSessionRepository;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfiguration {
  @Bean
  SecretKey jwtKey(AppProperties p) {
    try {
      byte[] key = Base64.getDecoder().decode(p.auth().secret());
      if (key.length < 32) throw new IllegalArgumentException();
      return new SecretKeySpec(key, "HmacSHA256");
    } catch (Exception e) {
      throw new IllegalStateException("JWT_SECRET must be base64 with at least 32 random bytes.");
    }
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(
      SecretKey key, AppProperties props, AuthSessionRepository sessions, Clock clock) {
    var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    var timestamp = new JwtTimestampValidator(java.time.Duration.ofSeconds(10));
    timestamp.setClock(clock);
    OAuth2TokenValidator<Jwt> sessionValidator =
        jwt -> {
          try {
            if (jwt.getExpiresAt() == null
                || jwt.getIssuedAt() == null
                || !jwt.getAudience().contains(props.auth().audience())) return failure();
            var s = sessions.findById(UUID.fromString(jwt.getClaimAsString("sid"))).orElse(null);
            if (s == null
                || !s.getUserId().toString().equals(jwt.getSubject())
                || s.getRevokedAt() != null
                || !s.getExpiresAt().isAfter(clock.instant())) return failure();
            return OAuth2TokenValidatorResult.success();
          } catch (IllegalArgumentException e) {
            return failure();
          }
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamp, new JwtIssuerValidator(props.auth().issuer()), sessionValidator));
    return decoder;
  }

  private static OAuth2TokenValidatorResult failure() {
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "Invalid or revoked token", null));
  }

  @Bean
  @org.springframework.core.annotation.Order(1)
  SecurityFilterChain sharingSecurity(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/shares", "/api/shares/**").csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
        .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  @org.springframework.core.annotation.Order(2)
  SecurityFilterChain security(HttpSecurity http, JsonMapper json) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/apple/challenge",
                        "/api/v1/auth/apple",
                        "/api/v1/auth/apple/delete-login",
                        "/api/v1/public-config", "/api/v1/localizations/**",
                        "/", "/privacy", "/privacy-policy", "/support", "/contact", "/delete-account", "/account-deletion",
                        "/share/*", "/share.js", "/share.css", "/localizations.js", "/model.mjs", "/site/**",
                        "/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (request, response, error) -> {
                          response.setStatus(401);
                          response.setCharacterEncoding("UTF-8");
                          response.setContentType("application/problem+json");
                          response.setHeader("WWW-Authenticate", "Bearer");
                          response
                              .getWriter()
                              .write(
                                  json.writeValueAsString(
                                      ApiErrors.problem(
                                          HttpStatus.UNAUTHORIZED,
                                          "unauthorized",
                                          "Autentificare necesară sau expirată.")));
                        }))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (request, response, error) -> {
                      response.setStatus(403);
                      response.setCharacterEncoding("UTF-8");
                      response.setContentType("application/problem+json");
                      response
                          .getWriter()
                          .write(
                              json.writeValueAsString(
                                  ApiErrors.problem(
                                      HttpStatus.FORBIDDEN, "forbidden", "Acces interzis.")));
                    }));
    http.headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(
        "default-src 'self'; script-src 'self' https://appleid.cdn-apple.com; style-src 'self'; img-src 'self' data: https://appleid.cdn-apple.com; connect-src 'self' https://appleid.apple.com; frame-src https://appleid.apple.com; frame-ancestors 'none'; base-uri 'none'; form-action 'self' https://appleid.apple.com"))
        .referrerPolicy(p -> p.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
    return http.build();
  }
}
