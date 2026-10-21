package com.example.store.exception;

import com.fasterxml.jackson.core.exc.InputCoercionException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import lombok.extern.slf4j.Slf4j;

import org.hibernate.TransactionException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.MethodParameter;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Collection;
import java.util.List;
import java.util.Map;

// Maps errors raised in Spring MVC request handling to RFC 7807 problem details with a stable code and no internals.
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Map<Integer, String> CODES_BY_STATUS = Map.of(
            400, "invalid_parameter",
            404, "not_found",
            405, "method_not_allowed",
            406, "not_acceptable",
            413, "payload_too_large",
            415, "unsupported_media_type",
            503, "service_unavailable");

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ExceptionHandler(InvalidSortPropertyException.class)
    ProblemDetail handleInvalidSort(InvalidSortPropertyException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_sort", ex.getMessage());
    }

    @ExceptionHandler(InvalidCursorRequestException.class)
    ProblemDetail handleInvalidCursor(InvalidCursorRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_cursor", ex.getMessage());
    }

    @ExceptionHandler(InvalidOrderFilterException.class)
    ProblemDetail handleConflictingFilters(InvalidOrderFilterException ex) {
        return problem(HttpStatus.BAD_REQUEST, "conflicting_filters", ex.getMessage());
    }

    @ExceptionHandler(MalformedQueryException.class)
    ProblemDetail handleMalformedQuery(MalformedQueryException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_parameter", ex.getMessage());
    }

    @ExceptionHandler(RepeatedParameterException.class)
    ProblemDetail handleRepeatedParameter(RepeatedParameterException ex) {
        return fieldProblem(
                HttpStatus.BAD_REQUEST,
                "invalid_parameter",
                ex.getMessage(),
                ex.getParameter(),
                "must not be repeated");
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail handleInvalidIdempotencyKey(InvalidIdempotencyKeyException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_idempotency_key", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyRequestInProgressException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyRequestInProgress(IdempotencyRequestInProgressException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.CONFLICT, "idempotency_request_in_progress", ex.getMessage()));
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
        return problem(HttpStatus.CONFLICT, "conflict", "The request conflicts with existing data");
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(NestedRuntimeException ex) {
        log.warn(
                "Database unavailable: {}", ex.getMostSpecificCause().getClass().getName());
        log.debug("Database unavailable", ex);
        return serviceUnavailable("service_unavailable", "The service cannot obtain a database connection");
    }

    @ExceptionHandler(JpaSystemException.class)
    ResponseEntity<ProblemDetail> handleJpaSystemFailure(JpaSystemException ex) {
        if (ex.getCause() instanceof TransactionException) {
            return handleDatabaseUnavailable(ex);
        }
        return ResponseEntity.internalServerError().body(handleUnexpected(ex));
    }

    @ExceptionHandler({QueryTimeoutException.class, TransactionTimedOutException.class})
    ResponseEntity<ProblemDetail> handleDatabaseTimeout(NestedRuntimeException ex) {
        log.warn("Database timeout: {}", ex.getMostSpecificCause().getClass().getName());
        log.debug("Database timeout", ex);
        return serviceUnavailable("database_timeout", "The database did not answer in time");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", null);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex.getRootCause() instanceof RequestBodyTooLargeException tooLarge) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .headers(headers)
                    .body(problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", tooLarge.getMessage()));
        }
        return ResponseEntity.status(status).headers(headers).body(unreadableBody(ex.getCause(), status));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!StringUtils.hasLength(request.getHeader(HttpHeaders.CONTENT_TYPE))) {
            ex.getBody().setDetail("Content-Type is missing.");
        }
        return super.handleHttpMediaTypeNotSupported(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setProperty("code", "validation_failed");
        problem.setProperty(
                "errors",
                ex.getFieldErrors().stream()
                        .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                        .toList());
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setProperty("code", "validation_failed");
        problem.setProperty(
                "errors",
                ex.getParameterValidationResults().stream()
                        .flatMap(result -> result.getResolvableErrors().stream()
                                .map(error -> fieldError(
                                        parameterName(result.getMethodParameter()), error.getDefaultMessage())))
                        .toList());
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            problem.setProperty("code", CODES_BY_STATUS.getOrDefault(statusCode.value(), "internal_error"));
        }
        return response;
    }

    private static ProblemDetail unreadableBody(Throwable cause, HttpStatusCode status) {
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return fieldProblem(
                    status,
                    "unknown_property",
                    "Unknown property '" + unknown.getPropertyName() + "'",
                    path(unknown),
                    "is not a known property");
        }
        if (cause instanceof MismatchedInputException mismatch
                && !mismatch.getPath().isEmpty()) {
            return fieldProblem(
                    status,
                    "validation_failed",
                    "Invalid request content.",
                    path(mismatch),
                    expectation(mismatch.getTargetType()));
        }
        if (cause instanceof JsonMappingException mapping && mapping.getCause() instanceof InputCoercionException) {
            return fieldProblem(
                    status, "validation_failed", "Invalid request content.", path(mapping), "must be a 64-bit integer");
        }
        return problem(status, "malformed_body", "Failed to read request");
    }

    private static ResponseEntity<ProblemDetail> serviceUnavailable(String code, String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, code, detail));
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    private static ProblemDetail fieldProblem(
            HttpStatusCode status, String code, String detail, String field, String message) {
        ProblemDetail problem = problem(status, code, detail);
        problem.setProperty("errors", List.of(fieldError(field, message)));
        return problem;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message);
    }

    private static String path(JsonMappingException ex) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : ex.getPath()) {
            if (reference.getFieldName() == null) {
                path.append('[').append(reference.getIndex()).append(']');
            } else {
                path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
            }
        }
        return path.toString();
    }

    private static String expectation(Class<?> targetType) {
        if (Number.class.isAssignableFrom(targetType)) {
            return "must be an integer";
        }
        if (Collection.class.isAssignableFrom(targetType)) {
            return "must be an array";
        }
        return "must be a string";
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        return requestParam != null && !requestParam.name().isEmpty()
                ? requestParam.name()
                : parameter.getParameterName();
    }
}
