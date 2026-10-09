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
                        || jwt.getSubject() == null || jwt.getAudience() == null
                        || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                        || jwt.getIssuedAt().isAfter(clock.instant().plusSeconds(10))
                        || java.time.Duration.between(jwt.getIssuedAt(),jwt.getExpiresAt()).compareTo(props.auth().accessTtl())>0
                        || !jwt.getAudience().contains(props.auth().audience())) return failure();
                    var s = sessions.findById(UUID.fromString(jwt.getClaimAsString("sid"))).orElse(null);
                    if (s == null
                        || !s.getUserId().toString().equals(jwt.getSubject())
                        || s.getRevokedAt() != null
                        || !s.getExpiresAt().isAfter(clock.instant())) return failure();
                    return OAuth2TokenValidatorResult.success();
                } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
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

    // Filter beans are installed explicitly in the security chains, not again by the servlet container.
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<org.adancau.doneapi.auth.AuthRateFilter> rateRegistration(org.adancau.doneapi.auth.AuthRateFilter filter) {
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);return registration;
    }
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<org.adancau.doneapi.security.AccountRateFilter> accountRateRegistration(org.adancau.doneapi.security.AccountRateFilter filter) {
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);return registration;
    }

    private void common(HttpSecurity http, JsonMapper json, org.adancau.doneapi.auth.AuthRateFilter admission) throws Exception {
        http.csrf(c -> c.disable()) // No cookie authentication; native clients send explicit bearer/capability tokens.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c -> c.disable())
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .addFilterAfter(admission,org.springframework.security.web.header.HeaderWriterFilter.class)
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req,res,error) -> problem(json,res,401,"unauthorized"))
                .accessDeniedHandler((req,res,error) -> problem(json,res,403,"forbidden")))
            .headers(h -> h.referrerPolicy(p -> p.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .contentTypeOptions(c -> {}).frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(t -> t.maxAgeInSeconds(31536000).includeSubDomains(true))
                .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter("Permissions-Policy","camera=(), microphone=(), geolocation=()")));
    }

    private static void problem(JsonMapper json,jakarta.servlet.http.HttpServletResponse response,int status,String code) throws java.io.IOException {
        response.setStatus(status);response.setCharacterEncoding("UTF-8");response.setContentType("application/problem+json");
        response.setHeader("Cache-Control","no-store");
        if(status==401)response.setHeader("WWW-Authenticate","Bearer");
        response.getWriter().write(json.writeValueAsString(ApiErrors.problem(HttpStatus.valueOf(status),code,
            status==401 ? "Authentication required or expired." : "Access denied.")));
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    SecurityFilterChain sharingSecurity(HttpSecurity http,JsonMapper json,org.adancau.doneapi.auth.AuthRateFilter admission) throws Exception {
        http.securityMatcher("/api/shares", "/api/shares/**");
        common(http,json,admission);
        http.headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
            .authorizeHttpRequests(a -> a
                // Read capabilities are public links. Writes are authorized by SharingService using the separate private writer key.
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/shares/{token}").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.HEAD,"/api/shares/{token}").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/shares").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.PUT,"/api/shares/{token}").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.DELETE,"/api/shares/{token}","/api/shares/by-command/{id}").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    SecurityFilterChain security(HttpSecurity http, JsonMapper json,org.adancau.doneapi.auth.AuthRateFilter admission,
        org.adancau.doneapi.security.AccountRateFilter accounts) throws Exception {
        common(http,json,admission);
        String[] publicReads={"/", "/privacy", "/privacy-policy", "/support", "/contact", "/delete-account", "/account-deletion",
            "/forgot-password", "/reset-password", "/share/{token}", "/share.js", "/share.css", "/localizations.js", "/model.mjs",
            "/site/site.js", "/site/site.css", "/site/copy.js", "/site/recovery.js", "/site/recovery-copy.js",
            "/api/v1/public-config", "/api/v1/localizations/{language}",
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"};
        String[] privateReads={"/api/v1/me", "/api/v1/me/export", "/api/v1/state", "/api/v1/catalog",
            "/api/v1/households", "/api/v1/households/{id}", "/api/v1/appliances", "/api/v1/appliances/{id}",
            "/api/v1/appliances/{id}/sessions", "/api/v1/sessions/{id}", "/api/v1/activity",
            "/api/v1/statistics", "/api/v1/statistics/rolling", "/api/v1/session-shares"};
        http.authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,publicReads).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.HEAD,publicReads).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/apple/challenge",
                    "/api/v1/auth/apple", "/api/v1/auth/apple/delete-login", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,privateReads).authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.HEAD,privateReads).authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    "/api/v1/auth/logout", "/api/v1/auth/logout-all", "/api/v1/me/password", "/api/v1/me/identities/apple",
                    "/api/v1/households", "/api/v1/appliances", "/api/v1/appliances/{id}/programs", "/api/v1/appliances/{id}/sessions",
                    "/api/v1/sessions/{id}/measured-program", "/api/v1/sessions/{id}/repeat", "/api/v1/sessions/{id}/extend",
                    "/api/v1/sessions/{id}/complete", "/api/v1/sessions/{id}/collect", "/api/v1/sessions/{id}/cancel",
                    "/api/v1/sessions/{id}/share", "/api/v1/activity/{id}/read", "/api/v1/activity/read-all").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.PATCH,
                    "/api/v1/me", "/api/v1/households/{id}", "/api/v1/appliances/{id}", "/api/v1/appliances/{id}/household",
                    "/api/v1/appliances/{id}/notifications", "/api/v1/appliances/{id}/programs/{programId}").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                    "/api/v1/me", "/api/v1/households/{id}", "/api/v1/appliances/{id}", "/api/v1/appliances/{id}/programs/{programId}",
                    "/api/v1/session-shares/{token}").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint((req,res,error) -> problem(json,res,401,"unauthorized")))
            .addFilterAfter(accounts,org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        http.headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(
            "default-src 'self'; script-src 'self' https://appleid.cdn-apple.com; style-src 'self'; img-src 'self' data: https://appleid.cdn-apple.com; connect-src 'self' https://appleid.apple.com; frame-src https://appleid.apple.com; frame-ancestors 'none'; base-uri 'none'; form-action 'self' https://appleid.apple.com")));
        return http.build();
    }
}
