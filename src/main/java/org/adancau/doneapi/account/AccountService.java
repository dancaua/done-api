package org.adancau.doneapi.account;

import java.time.*;
import java.util.*;
import org.adancau.doneapi.auth.*;
import org.adancau.doneapi.common.*;
import org.adancau.doneapi.persistence.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final UserRepository users;
  private final AuthSessionRepository sessions;
  private final MutationService mutations;
  private final PasswordEncoder passwords;
  private final AppleAuthService apple;
  private final UserViews views;
  private final Clock clock;

  public AccountService(
      UserRepository users,
      AuthSessionRepository sessions,
      MutationService mutations,
      PasswordEncoder passwords,
      AppleAuthService apple,
      UserViews views,
      Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.mutations = mutations;
    this.passwords = passwords;
    this.apple = apple;
    this.views = views;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AccountDtos.UserView get(UUID owner) {
    return views.view(users.findById(owner).orElseThrow(ApiException::unauthorized));
  }

  public AccountDtos.UserView update(UUID owner, UUID key, AccountDtos.UpdateProfile r) {
    return mutations.execute(
        owner,
        key,
        "account.update",
        r,
        AccountDtos.UserView.class,
        u -> {
          if (r.displayName() == null
              && r.timezone() == null
              && r.notificationsEnabled() == null
              && r.language() == null && r.onboarded() == null)
            throw ApiException.invalid("Trimite cel puțin o preferință.");
          if (r.timezone() != null) {
            try {
              ZoneId.of(r.timezone());
            } catch (DateTimeException e) {
              throw ApiException.invalid("Fus orar invalid.");
            }
            u.setTimezone(r.timezone());
          }
          if (r.displayName() != null) {
            u.setDisplayName(Names.clean(r.displayName(), 40, false));
          }
          if (r.notificationsEnabled() != null) u.setNotificationsEnabled(r.notificationsEnabled());
          if (r.language() != null) u.setLanguage(r.language());
          if (r.onboarded() != null) u.setOnboarded(r.onboarded());
          return views.view(u);
        });
  }

  @Transactional
  public void changePassword(UUID owner, AccountDtos.ChangePassword r) {
    var u = users.lockById(owner).orElseThrow(ApiException::unauthorized);
    AuthService.validPassword(r.newPassword());
    AuthService.validPassword(r.currentPassword());
    if (u.getPasswordHash() == null || !passwords.matches(r.currentPassword(), u.getPasswordHash()))
      throw ApiException.unauthorized();
    u.setPasswordHash(passwords.encode(r.newPassword()));
    u.setRevision(u.getRevision() + 1);
    sessions.revokeAll(owner, clock.instant());
  }

  @Transactional
  public void delete(UUID owner, UUID sid, AccountDtos.DeleteAccount r) {
    var u = users.lockById(owner).orElseThrow(ApiException::unauthorized);
    if (r != null && r.password() != null && u.getPasswordHash() != null) {
      AuthService.validPassword(r.password());
      if (!passwords.matches(r.password(),u.getPasswordHash())) throw ApiException.unauthorized();
    } else apple.requireRecentApple(owner,sid);
    apple.revokeFor(owner);
    users.delete(u);
    users.flush();
  }
}
