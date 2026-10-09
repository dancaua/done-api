package org.adancau.doneapi.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class AccountDtos {
  private AccountDtos() {}

  public record UserView(
      UUID id,
      String email,
      String displayName,
      String timezone,
      String language,
      boolean notificationsEnabled,
      boolean passwordEnabled,
      boolean appleEnabled,
      long revision, boolean onboarded) {
    // Cached responses from before V4 have no language field; those accounts used Romanian.
    public UserView {
      if (language == null) language = "ro";
    }
  }

  public record UpdateProfile(
      String displayName,
      @Size(max = 64) String timezone,
      Boolean notificationsEnabled,
      @JsonInclude(JsonInclude.Include.NON_NULL)
          @Pattern(regexp = org.adancau.doneapi.common.SupportedLanguages.PATTERN)
          String language, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean onboarded) {}

  public record ChangePassword(
      @NotBlank @Size(max = 64) String currentPassword,
      @NotBlank @Size(min = 12, max = 64) String newPassword) {}

  public record DeleteAccount(@Size(max = 64) String password) {}

  public record MutationResult(long revision) {}
}
