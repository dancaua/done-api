package org.adancau.doneapi.auth;

import java.time.*;
import java.util.UUID;
import org.adancau.doneapi.account.*;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.config.AppProperties;
import org.adancau.doneapi.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppleAuthService {
  private final AppleChallengeRepository challenges;
  private final AppleIdentityRepository identities;
  private final UserRepository users;
  private final org.adancau.doneapi.household.HouseholdService households;
  private final AuthSessionRepository sessions;
  private final AppleGateway gateway;
  private final AppleTokenCipher cipher;
  private final TokenService tokens;
  private final UserViews views;
  private final Clock clock;
  private final AppProperties props;

  public AppleAuthService(
      AppleChallengeRepository challenges,
      AppleIdentityRepository identities,
      UserRepository users,
      org.adancau.doneapi.household.HouseholdService households,
      AuthSessionRepository sessions,
      AppleGateway gateway,
      AppleTokenCipher cipher,
      TokenService tokens,
      UserViews views,
      Clock clock,
      AppProperties props) {
    this.challenges = challenges;
    this.identities = identities;
    this.users = users;
    this.households = households;
    this.sessions = sessions;
    this.gateway = gateway;
    this.cipher = cipher;
    this.tokens = tokens;
    this.views = views;
    this.clock = clock;
    this.props = props;
  }

  private void enabled() {
    if (!props.apple().enabled())
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "apple_not_configured",
          "Sign in with Apple nu este configurat încă.");
  }

  @Transactional
  public AuthDtos.AppleChallenge challenge() {
    enabled();
    var c = new AppleChallengeEntity();
    c.setNonceHash(Crypto.hash(Crypto.randomToken()));
    c.setExpiresAt(clock.instant().plusSeconds(300));
    challenges.save(c);
    return new AuthDtos.AppleChallenge(c.getId(), c.getNonceHash(), c.getExpiresAt());
  }

  private AppleGateway.Identity authenticate(AuthDtos.AppleLogin request) {
    enabled();
    var c = challenges.lockById(request.challengeId()).orElseThrow(ApiException::unauthorized);
    if (c.getUsedAt() != null || !c.getExpiresAt().isAfter(clock.instant()))
      throw ApiException.unauthorized();
    var identity = gateway.authenticate(request, c.getNonceHash());
    c.setUsedAt(clock.instant());
    return identity;
  }

  @Transactional
  public AuthDtos.Tokens login(AuthDtos.AppleLogin request) {
    var verified = authenticate(request);
    var existing = identities.findBySubject(verified.subject());
    UserEntity user;
    if (existing.isPresent()) {
      user = users.lockById(existing.get().getUserId()).orElseThrow(ApiException::unauthorized);
      existing.get().setEncryptedRefreshToken(cipher.encrypt(verified.refreshToken()));
      existing.get().setClientId(verified.clientId());
    } else {
      String email = verified.email() == null ? null : AuthService.email(verified.email());
      if (email != null && users.findByEmail(email).isPresent())
        throw ApiException.conflict(
            "apple_link_required",
            "Intră în contul existent și leagă identitatea Apple din setări.");
      user = new UserEntity();
      user.setEmail(email);
      user.setDisplayName(
          request.displayName() == null || request.displayName().isBlank()
              ? "DONE."
              : Names.clean(request.displayName(), 40, true));
      user.setTimezone("Europe/Bucharest");
      if (request.language() != null) user.setLanguage(request.language());
      user.setNotificationsEnabled(true);
      user.setCreatedAt(clock.instant());
      users.saveAndFlush(user);
      households.createDefault(user);
      saveIdentity(user, verified);
    }
    return tokens.create(user, "apple");
  }

  @Transactional
  public AccountDtos.UserView link(UUID owner, UUID sid, AuthDtos.AppleLogin request) {
    var verified = authenticate(request);
    var user = users.lockById(owner).orElseThrow(ApiException::unauthorized);
    requireRecent(owner, sid);
    var other = identities.findBySubject(verified.subject());
    if (other.isPresent() && !other.get().getUserId().equals(owner))
      throw ApiException.conflict(
          "apple_in_use", "Această identitate Apple este legată de alt cont.");
    var current = identities.findByUserId(owner);
    if (current.isPresent() && !current.get().getSubject().equals(verified.subject()))
      throw ApiException.conflict("apple_already_linked", "Contul are deja o identitate Apple.");
    if (current.isPresent()) {
      current.get().setEncryptedRefreshToken(cipher.encrypt(verified.refreshToken()));
      current.get().setClientId(verified.clientId());
    } else saveIdentity(user, verified);
    user.setRevision(user.getRevision() + 1);
    return views.view(user);
  }

  private void saveIdentity(UserEntity user, AppleGateway.Identity verified) {
    var i = new AppleIdentityEntity();
    i.setUserId(user.getId());
    i.setSubject(verified.subject());
    i.setClientId(verified.clientId());
    i.setEncryptedRefreshToken(cipher.encrypt(verified.refreshToken()));
    i.setCreatedAt(clock.instant());
    identities.saveAndFlush(i);
  }

  public void requireRecent(UUID owner, UUID sid) {
    var s =
        sessions
            .findById(sid)
            .filter(session -> session.getUserId().equals(owner))
            .orElseThrow(ApiException::unauthorized);
    if (s.getCreatedAt().isBefore(clock.instant().minusSeconds(300)))
      throw ApiException.conflict(
          "recent_login_required", "Autentifică-te din nou pentru această acțiune.");
  }

  @Transactional
  public AuthDtos.Tokens loginExisting(AuthDtos.AppleLogin request) {
    var verified=authenticate(request);
    var i=identities.findBySubject(verified.subject()).orElseThrow(ApiException::unauthorized);
    var user=users.lockById(i.getUserId()).orElseThrow(ApiException::unauthorized);
    i.setEncryptedRefreshToken(cipher.encrypt(verified.refreshToken())); i.setClientId(verified.clientId());
    return tokens.create(user,"apple");
  }

  public void requireRecentApple(UUID owner, UUID sid) {
    requireRecent(owner,sid);
    if (!sessions.findById(sid).map(s -> s.getAuthenticationMethod().equals("apple")).orElse(false))
      throw ApiException.unauthorized();
  }

  public void revokeFor(UUID owner) {
    identities
        .findByUserId(owner)
        .ifPresent(i -> {
          String token=cipher.decrypt(i.getEncryptedRefreshToken());
          if (i.getClientId()==null || i.getClientId().equals(props.apple().clientId())) gateway.revoke(token);
          else gateway.revoke(token,i.getClientId());
        });
  }
}
