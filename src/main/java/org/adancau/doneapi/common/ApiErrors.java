package org.adancau.doneapi.common;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  public static ProblemDetail problem(HttpStatus status, String code, String detail) {
    var p = ProblemDetail.forStatusAndDetail(status, detail);
    p.setProperty("code", code);
    return p;
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handle(ApiException e) {
    return ResponseEntity.status(e.getStatus())
        .headers(headers -> { if(e.getStatus()==HttpStatus.TOO_MANY_REQUESTS) headers.set("Retry-After","300"); if(e.getStatus()==HttpStatus.SERVICE_UNAVAILABLE) headers.set("Retry-After","60"); })
        .body(problem(e.getStatus(), e.getCode(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
    var p = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Verifică datele introduse.");
    p.setProperty(
        "errors",
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    Map.of(
                        "field",
                        f.getField(),
                        "message",
                        f.getDefaultMessage() == null ? "Invalid" : f.getDefaultMessage()))
            .toList());
    return ResponseEntity.badRequest().body(p);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    ConstraintViolationException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class
  })
  ResponseEntity<ProblemDetail> malformed(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            problem(HttpStatus.BAD_REQUEST, "invalid_request", "Cerere invalidă sau incompletă."));
  }

  @ExceptionHandler({org.springframework.dao.QueryTimeoutException.class,org.springframework.dao.CannotAcquireLockException.class})
  ResponseEntity<ProblemDetail> busy(Exception e) {
    return ResponseEntity.status(503).header("Retry-After","1")
        .body(problem(HttpStatus.SERVICE_UNAVAILABLE,"server_busy","Service busy. Please retry."));
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    ObjectOptimisticLockingFailureException.class
  })
  ResponseEntity<ProblemDetail> conflict(Exception e) {
    return ResponseEntity.status(409)
        .body(
            problem(
                HttpStatus.CONFLICT,
                "conflict",
                "Datele s-au modificat sau există deja. Reîncarcă și încearcă din nou."));
  }
}
