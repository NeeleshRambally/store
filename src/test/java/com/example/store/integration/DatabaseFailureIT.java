package com.example.store.integration;

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
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

// Blocks a real GET /order on a lock held by a second connection, then cancels or terminates the waiting backend.
class DatabaseFailureIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PostgreSQLContainer<?> container;

    private final Logger transactionLogger = (Logger) LoggerFactory.getLogger(TransactionInterceptor.class);
    private final ListAppender<ILoggingEvent> transactionLog = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        transactionLog.start();
        transactionLogger.addAppender(transactionLog);
    }

    @AfterEach
    void detachAppender() {
        transactionLogger.detachAppender(transactionLog);
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
    void aConnectionLostInsideTheTransactionIsARetryableServiceUnavailable() throws Exception {
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
}
