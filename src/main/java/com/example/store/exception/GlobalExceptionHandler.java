package com.example.store.exception;

import com.fasterxml.jackson.core.exc.InputCoercionException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

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

import java.util.Collection;
import java.util.List;
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

    @ExceptionHandler(RepeatedParameterException.class)
    ProblemDetail handleRepeatedParameter(RepeatedParameterException ex) {
        return fieldProblem(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.getParameter(), "must not be repeated");
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
        return ResponseEntity.status(status).headers(headers).body(unreadableBody(ex.getCause(), status));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
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
        problem.setProperty(
                "errors",
                ex.getParameterValidationResults().stream()
                        .flatMap(result -> result.getResolvableErrors().stream()
                                .map(error -> fieldError(
                                        parameterName(result.getMethodParameter()), error.getDefaultMessage())))
                        .toList());
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static ProblemDetail unreadableBody(Throwable cause, HttpStatusCode status) {
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return fieldProblem(
                    status,
                    "Unknown property '" + unknown.getPropertyName() + "'",
                    path(unknown),
                    "is not a known property");
        }
        if (cause instanceof MismatchedInputException mismatch
                && !mismatch.getPath().isEmpty()) {
            return fieldProblem(
                    status, "Invalid request content.", path(mismatch), expectation(mismatch.getTargetType()));
        }
        if (cause instanceof JsonMappingException mapping && mapping.getCause() instanceof InputCoercionException) {
            return fieldProblem(status, "Invalid request content.", path(mapping), "must be a 64-bit integer");
        }
        return ProblemDetail.forStatusAndDetail(status, "Failed to read request");
    }

    private static ProblemDetail fieldProblem(HttpStatusCode status, String detail, String field, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
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
