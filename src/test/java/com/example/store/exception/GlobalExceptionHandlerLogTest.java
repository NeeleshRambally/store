package com.example.store.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.sql.SQLException;

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

    private ILoggingEvent event(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + level + " event; got " + appender.list));
    }
}
