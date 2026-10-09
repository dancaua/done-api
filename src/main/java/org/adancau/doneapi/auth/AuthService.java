package org.adancau.doneapi.auth;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
  private final UserRepository users;
  private final org.adancau.doneapi.household.HouseholdService households;
  private final AuthSessionRepository sessions;
  private final RefreshTokenRepository refresh;
  private final PasswordEncoder passwords;
  private final TokenService tokens;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final EntityManager em;
  private final String dummyHash;
  private final RateLimits rates;

  public AuthService(
      UserRepository users,
      org.adancau.doneapi.household.HouseholdService households,
      AuthSessionRepository sessions,
      RefreshTokenRepository refresh,
      PasswordEncoder passwords,
      TokenService tokens,
      Clock clock,
      PlatformTransactionManager manager,
      EntityManager em,
      RateLimits rates) {
    this.rates = rates;
    this.users = users;
    this.households = households;
    this.sessions = sessions;
    this.refresh = refresh;
    this.passwords = passwords;
    this.tokens = tokens;
    this.clock = clock;
    this.tx = new TransactionTemplate(manager);
    this.em = em;
    this.dummyHash = passwords.encode(Crypto.randomToken());
  }

  public static String email(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }

  public static void validPassword(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw ApiException.invalid("Parola trebuie să aibă maximum 72 de octeți UTF-8.");
  }

  @Transactional
  public AuthDtos.Tokens register(AuthDtos.Register request) {
    validPassword(request.password());
    String email = email(request.email());
    if (users.findByEmail(email).isPresent())
      throw ApiException.conflict("email_in_use", "Există deja un cont cu acest email.");
    var u = new UserEntity();
    u.setEmail(email);
    u.setPasswordHash(passwords.encode(request.password()));
    u.setDisplayName(Names.clean(request.displayName(), 40, true));
    u.setTimezone("Europe/Bucharest");
    if (request.language() != null) u.setLanguage(request.language());
    u.setNotificationsEnabled(true);
    u.setCreatedAt(clock.instant());
    users.saveAndFlush(u);
    households.createDefault(u);
    return tokens.create(u);
  }

  @Transactional
  public AuthDtos.Tokens login(AuthDtos.Login request) {
    if (!rates.consume("account:" + email(request.email()), 10))
      throw new ApiException(
          org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
          "rate_limited",
          "Prea multe încercări. Așteaptă cinci minute.");
    validPassword(request.password());
    var u = users.findByEmail(email(request.email())).orElse(null);
    boolean matches =
        passwords.matches(
            request.password(),
            u == null || u.getPasswordHash() == null ? dummyHash : u.getPasswordHash());
    if (u == null || u.getPasswordHash() == null || !matches) throw ApiException.unauthorized();
    u = users.lockById(u.getId()).orElseThrow(ApiException::unauthorized);
    em.refresh(u);
    if (u.getPasswordHash() == null || !passwords.matches(request.password(), u.getPasswordHash()))
      throw ApiException.unauthorized();
    return tokens.create(u);
  }

  public AuthDtos.Tokens refresh(String raw) {
    var result =
        tx.execute(
            status -> {
              var token = refresh.findByTokenHash(Crypto.hash(raw)).orElse(null);
              if (token == null) return null;
              var session = sessions.findById(token.getSessionId()).orElse(null);
              if (session == null) return null;
              var user = users.lockById(session.getUserId()).orElse(null);
              if (user == null) return null;
              em.refresh(token);
              em.refresh(session);
              if (token.getUsedAt() != null) {
                if (session.getRevokedAt() == null) session.setRevokedAt(clock.instant());
                return null;
              }
              if (session.getRevokedAt() != null
                  || !session.getExpiresAt().isAfter(clock.instant())
                  || !token.getExpiresAt().isAfter(clock.instant())) return null;
              token.setUsedAt(clock.instant());
              return tokens.issue(user, session);
            });
    // A replay must commit its family revocation before returning HTTP 401.
    if (result == null) throw ApiException.unauthorized();
    return result;
  }

  @Transactional
  public void logout(UUID owner, UUID sid, boolean all) {
    users.lockById(owner).orElseThrow(ApiException::unauthorized);
    if (all) sessions.revokeAll(owner, clock.instant());
    else
      sessions
          .findById(sid)
          .filter(s -> s.getUserId().equals(owner))
          .ifPresent(s -> s.setRevokedAt(clock.instant()));
  }
}
