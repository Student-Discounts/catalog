package org.studentdiscounts.catalog.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class CatalogErrorHandler {
  private static final URI ABOUT_BLANK = URI.create("about:blank");
  private static final String CORRELATION_HEADER = "X-Correlation-ID";
  private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> status(
      ResponseStatusException failure, HttpServletRequest request) {
    int status = failure.getStatusCode().value();
    return problem(status, codeFor(status), request);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> unknownPath(HttpServletRequest request) {
    return problem(404, "NOT_FOUND", request);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> method(HttpServletRequest request) {
    return problem(405, "METHOD_NOT_ALLOWED", request);
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ProblemDetail> notAcceptable(HttpServletRequest request) {
    return problem(406, "NOT_ACCEPTABLE", request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> malformed(HttpServletRequest request) {
    return problem(400, "MALFORMED_REQUEST", request);
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ProblemDetail> dependency(HttpServletRequest request) {
    return problem(503, "DEPENDENCY_UNAVAILABLE", request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> unexpected(HttpServletRequest request) {
    return problem(500, "INTERNAL_ERROR", request);
  }

  private static ResponseEntity<ProblemDetail> problem(
      int status, String code, HttpServletRequest request) {
    ProblemDetail body = ProblemDetail.forStatus(status);
    body.setType(ABOUT_BLANK);
    body.setProperty("code", code);
    String correlationId = request.getHeader(CORRELATION_HEADER);
    if (correlationId != null && CORRELATION_ID.matcher(correlationId).matches()) {
      body.setProperty("correlationId", correlationId);
    }
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }

  private static String codeFor(int status) {
    return switch (status) {
      case 400 -> "MALFORMED_REQUEST";
      case 404 -> "NOT_FOUND";
      case 409 -> "CONFLICT";
      case 503 -> "DEPENDENCY_UNAVAILABLE";
      default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
    };
  }
}
