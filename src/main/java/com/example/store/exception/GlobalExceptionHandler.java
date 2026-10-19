package com.example.store.exception;

import lombok.extern.slf4j.Slf4j;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.MethodParameter;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

// Maps every error to an RFC 7807 ProblemDetail without leaking internal details.
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({
        InvalidSortPropertyException.class,
        InvalidCursorRequestException.class,
        InvalidOrderFilterException.class
    })
    ProblemDetail handleBadListRequest(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (ex.getCause() instanceof ConstraintViolationException violation) {
            log.warn(
                    "Data integrity violation: SQL state {}, constraint {}",
                    violation.getSQLState(),
                    violation.getConstraintName());
        } else {
            log.warn("Data integrity violation: {}", cause.getClass().getName());
        }
        log.debug("Data integrity violation", cause);
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data");
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(NestedRuntimeException ex) {
        log.warn(
                "Database unavailable: {}", ex.getMostSpecificCause().getClass().getName());
        log.debug("Database unavailable", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "The service cannot obtain a database connection"));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getRootCause() instanceof RequestBodyTooLargeException tooLarge) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .headers(headers)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge.getMessage()));
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setProperty(
                "errors",
                ex.getFieldErrors().stream()
                        .map(error -> Map.of("field", error.getField(), "message", error.getDefaultMessage()))
                        .toList());
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setProperty(
                "errors",
                ex.getParameterValidationResults().stream()
                        .flatMap(result -> result.getResolvableErrors().stream()
                                .map(error -> Map.of(
                                        "field",
                                        parameterName(result.getMethodParameter()),
                                        "message",
                                        error.getDefaultMessage())))
                        .toList());
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        return requestParam != null && !requestParam.name().isEmpty()
                ? requestParam.name()
                : parameter.getParameterName();
    }
}
