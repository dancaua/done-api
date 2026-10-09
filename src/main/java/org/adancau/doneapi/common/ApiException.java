package org.adancau.doneapi.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Resursa nu există.");
  }

  public static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  public static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", message);
  }

  public static ApiException unauthorized() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "invalid_credentials", "Autentificare invalidă sau expirată.");
  }
}
