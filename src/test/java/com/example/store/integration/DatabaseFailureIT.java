package com.example.store.integration;

import com.example.store.exception.GlobalExceptionHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Blocks a real request on a lock held by a second connection, then cancels or terminates the waiting backend.
class DatabaseFailureIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PostgreSQLContainer<?> container;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Logger transactionLogger = (Logger) LoggerFactory.getLogger(TransactionInterceptor.class);
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> transactionLog = new ListAppender<>();
    private final ListAppender<ILoggingEvent> handlerLog = new ListAppender<>();

    @BeforeEach
    void attachAppenders() {
        transactionLog.start();
        transactionLogger.addAppender(transactionLog);
        handlerLog.start();
        handlerLogger.addAppender(handlerLog);
    }

    @AfterEach
    void detachAppenders() {
        transactionLogger.detachAppender(transactionLog);
        handlerLogger.detachAppender(handlerLog);
    }

    @Test
    void aCancelledStatementIsARetryableDatabaseTimeout() throws Exception {
        ResponseEntity<ProblemDetail> response;
        try (TableLock lock = TableLock.on(container, "\"order\"")) {
            response = lock.cancelWaiting(() -> restTemplate.getForEntity("/order?size=5", ProblemDetail.class));
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody().getProperties()).containsEntry("code", "database_timeout");
        assertThat(transactionLog.list).isEmpty();
    }

    @Test
    void aSessionTerminatedWhileOrdersAreReadIsARetryableServiceUnavailable() throws Exception {
        ResponseEntity<ProblemDetail> response;
        try (TableLock lock = TableLock.on(container, "\"order\"")) {
            response = lock.terminateWaiting(() -> restTemplate.getForEntity("/order?size=5", ProblemDetail.class));
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody().getProperties()).containsEntry("code", "service_unavailable");
        assertThat(transactionLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Application exception overridden by rollback exception");
        });
    }

    @Test
    void aSessionTerminatedWhileAnOrderIsCommittedIsStillAnInternalErrorThatWritesNothing() throws Exception {
        String description = "Terminated at commit " + UUID.randomUUID();
        Long before = ordersDescribed(description);
        ResponseEntity<ProblemDetail> response;
        try (TableLock lock = TableLock.on(container, "\"order\"")) {
            response = lock.terminateWaiting(() -> restTemplate.postForEntity(
                    "/order",
                    Map.of("description", description, "customerId", 2, "productIds", List.of(1)),
                    ProblemDetail.class));
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "internal_error");
        assertThat(transactionLog.list).isEmpty();
        assertThat(handlerLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Unhandled exception");
        });
        assertThat(ordersDescribed(description)).isEqualTo(before);
    }

    private Long ordersDescribed(String description) {
        return jdbcTemplate.queryForObject(
                "select count(*) from \"order\" where description = ?", Long.class, description);
    }
}
