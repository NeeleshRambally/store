package com.example.store.integration;

import com.example.store.entity.Order;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;

import org.hibernate.engine.jdbc.spi.SqlExceptionHelper;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Fails the inserts of real writes and checks that no log line, stack traces included, carries the text they were sent.
class FailedInsertLogIT extends AbstractIntegrationTest {

    private static final String CANCELLED_BATCH_ENTRY = "Batch entry 0 <unknown> was aborted: ERROR: canceling"
            + " statement due to user request  Call getNextException to see other errors in the batch.";

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PostgreSQLContainer<?> container;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @BeforeEach
    void attachAppender() {
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        root.detachAppender(appender);
    }

    @Test
    void aCustomerInsertCancelledAtCommitLogsNoneOfTheName() throws Exception {
        String name = "Secret Probe " + UUID.randomUUID();
        Long before = rows("customer", "name", name);

        assertCancelledAtCommit("customer", "/customer", Map.of("name", name));

        assertThat(linesContaining(name)).isEmpty();
        assertThat(failedBatchEntries()).containsExactly(CANCELLED_BATCH_ENTRY);
        assertThat(rows("customer", "name", name)).isEqualTo(before);
    }

    @Test
    void anOrderInsertCancelledAtCommitLogsNoneOfTheDescription() throws Exception {
        String description = "Secret probe order " + UUID.randomUUID();
        Long before = rows("\"order\"", "description", description);

        assertCancelledAtCommit(
                "\"order\"", "/order", Map.of("description", description, "customerId", 2, "productIds", List.of(1)));

        assertThat(linesContaining(description)).isEmpty();
        assertThat(failedBatchEntries()).containsExactly(CANCELLED_BATCH_ENTRY);
        assertThat(rows("\"order\"", "description", description)).isEqualTo(before);
    }

    @Test
    void aProductInsertCancelledAtCommitLogsNoneOfTheDescription() throws Exception {
        String description = "Secret probe product " + UUID.randomUUID();
        Long before = rows("product", "description", description);

        assertCancelledAtCommit("product", "/products", Map.of("description", description));

        assertThat(linesContaining(description)).isEmpty();
        assertThat(failedBatchEntries()).containsExactly(CANCELLED_BATCH_ENTRY);
        assertThat(rows("product", "description", description)).isEqualTo(before);
    }

    @Test
    void anOrderInsertTerminatedAtCommitLogsNoneOfTheDescriptionInTheInternalErrorTrace() throws Exception {
        String description = "Secret probe terminated " + UUID.randomUUID();
        Long before = rows("\"order\"", "description", description);
        ResponseEntity<ProblemDetail> response;
        try (TableLock lock = TableLock.on(container, "\"order\"")) {
            response = lock.terminateWaiting(() -> restTemplate.postForEntity(
                    "/order",
                    Map.of("description", description, "customerId", 2, "productIds", List.of(1)),
                    ProblemDetail.class));
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(linesContaining(description)).isEmpty();
        assertThat(linesContaining("Unhandled exception"))
                .singleElement()
                .asString()
                .contains("<unknown>");
        assertThat(rows("\"order\"", "description", description)).isEqualTo(before);
    }

    @Test
    void aViolatedForeignKeyIsStillNamedWhileTheRowStaysOutOfTheLog() {
        String description = "Secret probe constraint " + UUID.randomUUID();
        Long before = rows("\"order\"", "description", description);

        assertThatThrownBy(() -> orderRepository.saveAndFlush(
                        new Order(description, customerRepository.getReferenceById(Long.MAX_VALUE))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .cause()
                .isInstanceOfSatisfying(ConstraintViolationException.class, violation -> {
                    assertThat(violation.getSQLState()).isEqualTo("23503");
                    assertThat(violation.getConstraintName()).isEqualTo("fk_customer");
                });

        assertThat(linesContaining(description)).isEmpty();
        assertThat(failedBatchEntries())
                .containsExactly("Batch entry 0 <unknown> was aborted: ERROR: insert or update on table \"order\""
                        + " violates foreign key constraint \"fk_customer\"  Call getNextException to see other errors"
                        + " in the batch.");
        assertThat(rows("\"order\"", "description", description)).isEqualTo(before);
    }

    private void assertCancelledAtCommit(String table, String path, Map<String, Object> body) throws Exception {
        ResponseEntity<ProblemDetail> response;
        try (TableLock lock = TableLock.on(container, table)) {
            response = lock.cancelWaiting(() -> restTemplate.postForEntity(path, body, ProblemDetail.class));
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getProperties()).containsEntry("code", "database_timeout");
    }

    private List<String> linesContaining(String text) {
        synchronized (appender) {
            return appender.list.stream()
                    .map(FailedInsertLogIT::rendered)
                    .filter(line -> line.contains(text))
                    .toList();
        }
    }

    private List<String> failedBatchEntries() {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .filter(event -> event.getLoggerName().equals(SqlExceptionHelper.class.getName()))
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("Batch entry"))
                    .toList();
        }
    }

    private static String rendered(ILoggingEvent event) {
        return event.getThrowableProxy() == null
                ? event.getFormattedMessage()
                : event.getFormattedMessage()
                        + System.lineSeparator()
                        + ThrowableProxyUtil.asString(event.getThrowableProxy());
    }

    private Long rows(String table, String column, String value) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Long.class, value);
    }
}
