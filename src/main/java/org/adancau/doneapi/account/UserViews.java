package org.adancau.doneapi.account;

import org.adancau.doneapi.persistence.*;
import org.springframework.stereotype.Component;

@Component
public class UserViews {
  private final AppleIdentityRepository apple;

  public UserViews(AppleIdentityRepository apple) {
    this.apple = apple;
  }

  public AccountDtos.UserView view(UserEntity u) {
    return new AccountDtos.UserView(
        u.getId(),
        u.getEmail(),
        u.getDisplayName(),
        u.getTimezone(),
        u.getLanguage(),
        u.isNotificationsEnabled(),
        u.getPasswordHash() != null,
        apple.findByUserId(u.getId()).isPresent(),
        u.getRevision(), u.isOnboarded());
  }
}
