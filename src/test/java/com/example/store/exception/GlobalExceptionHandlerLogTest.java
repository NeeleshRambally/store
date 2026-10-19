package com.example.store.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.ServletWebRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerLogTest {

    private static final String SERVER_MESSAGE = "ERROR: insert or update on table \"order\" violates foreign key"
            + " constraint \"fk_order_customer\"\n  Detail: Key (customer_id)=(5) is not present in table \"customer\".";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void attachAppender() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void aConstraintViolationIsLoggedBySqlStateAndConstraintNameWithoutTheValues() {
        ConstraintViolationException violation = new ConstraintViolationException(
                "could not execute statement [" + SERVER_MESSAGE + "]",
                new SQLException(SERVER_MESSAGE, "23503"),
                "fk_order_customer");
        DataIntegrityViolationException ex = new DataIntegrityViolationException(violation.getMessage(), violation);

        ProblemDetail problem = handler.handleDataIntegrity(ex);

        assertThat(problem.getDetail()).isEqualTo("The request conflicts with existing data");
        assertThat(event(Level.WARN).getFormattedMessage())
                .contains("23503")
                .contains("fk_order_customer")
                .doesNotContain("customer_id)=(5");
        assertThat(event(Level.DEBUG).getThrowableProxy().getMessage()).contains("customer_id)=(5");
    }

    @Test
    void aViolationWithoutAConstraintIsLoggedByItsCauseClass() {
        DataIntegrityViolationException ex =
                new DataIntegrityViolationException("duplicate", new SQLException("Key (name)=(x) exists", "23505"));

        handler.handleDataIntegrity(ex);

        assertThat(event(Level.WARN).getFormattedMessage())
                .contains("java.sql.SQLException")
                .doesNotContain("Key (name)=(x)");
        assertThat(event(Level.DEBUG).getThrowableProxy().getMessage()).contains("Key (name)=(x)");
    }

    @Test
    void anUnobtainableConnectionIsLoggedByItsCauseClassWithoutTheDriverMessage() {
        CannotCreateTransactionException ex = new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException(
                        "HikariPool-1 - Connection is not available, request timed out after 5000ms."));

        ResponseEntity<ProblemDetail> response = handler.handleDatabaseUnavailable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(response.getBody().getDetail()).isEqualTo("The service cannot obtain a database connection");
        assertThat(event(Level.WARN).getFormattedMessage())
                .contains("java.sql.SQLTransientConnectionException")
                .doesNotContain("HikariPool");
        assertThat(event(Level.DEBUG).getThrowableProxy().getMessage()).contains("EntityManager");
    }

    @Test
    void aDatabaseTimeoutIsLoggedByItsCauseClassWithoutTheStatement() {
        QueryTimeoutException ex = new QueryTimeoutException(
                "JDBC exception executing SQL [select o1_0.id from \"order\" o1_0 fetch first ? rows only]"
                        + " [ERROR: canceling statement due to statement timeout]",
                new SQLException("ERROR: canceling statement due to statement timeout", "57014"));

        ResponseEntity<ProblemDetail> response = handler.handleDatabaseTimeout(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(response.getBody().getDetail()).isEqualTo("The database did not answer in time");
        assertThat(event(Level.WARN).getFormattedMessage())
                .contains("java.sql.SQLException")
                .doesNotContain("select");
        assertThat(event(Level.DEBUG).getThrowableProxy().getMessage()).contains("select o1_0.id");
    }

    @Test
    void aFailureAfterTheResponseIsCommittedIsLoggedAndAnswersNothing() throws Exception {
        MockHttpServletResponse committed = new MockHttpServletResponse();
        committed.setCommitted(true);

        ResponseEntity<Object> response = handler.handleException(
                new HttpMessageNotWritableException("Could not write JSON"),
                new ServletWebRequest(new MockHttpServletRequest(), committed));

        assertThat(response).isNull();
        assertThat(event(Level.WARN).getFormattedMessage()).contains("Response already committed");
    }

    private ILoggingEvent event(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + level + " event; got " + appender.list));
    }
}
