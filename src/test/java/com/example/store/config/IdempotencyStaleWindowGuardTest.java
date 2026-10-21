package com.example.store.config;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.transaction.TransactionProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyStaleWindowGuardTest {

    private static final Duration CONNECTION_WAIT = Duration.ofSeconds(5);
    private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(15);
    private static final String STATEMENT_TIMEOUT = "-c statement_timeout=10s";

    private final Logger logger = (Logger) LoggerFactory.getLogger(IdempotencyStaleWindowGuard.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void acceptsAStaleWindowThatCoversTheConnectionWaitTheTransactionAndOneStatement() {
        IdempotencyStaleWindowGuard guard =
                guard(Duration.ofSeconds(30), TRANSACTION_TIMEOUT, CONNECTION_WAIT, STATEMENT_TIMEOUT);

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void refusesAStaleWindowShorterThanThatTime() {
        IdempotencyStaleWindowGuard guard =
                guard(Duration.ofSeconds(29), TRANSACTION_TIMEOUT, CONNECTION_WAIT, STATEMENT_TIMEOUT);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("store.idempotency.stale-after PT29S is shorter than the PT30S a keyed POST can hold its"
                        + " claim (the connection wait PT5S, TX_TIMEOUT PT15S and DB_STATEMENT_TIMEOUT PT10S"
                        + " together): set IDEMPOTENCY_STALE_AFTER to at least PT30S");
    }

    @Test
    void warnsInsteadWhenTheTransactionTimeoutIsOff() {
        IdempotencyStaleWindowGuard guard =
                guard(Duration.ofSeconds(1), Duration.ofSeconds(-1), CONNECTION_WAIT, STATEMENT_TIMEOUT);

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(warning())
                .isEqualTo("TX_TIMEOUT PT-1S and DB_STATEMENT_TIMEOUT PT10S do not bound a keyed POST: one that"
                        + " runs longer than the stale window PT1S is taken over and fails without creating anything");
    }

    @Test
    void warnsInsteadWhenNoTransactionTimeoutIsSet() {
        IdempotencyStaleWindowGuard guard = guard(Duration.ofSeconds(30), null, CONNECTION_WAIT, STATEMENT_TIMEOUT);

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(warning()).startsWith("TX_TIMEOUT unset and DB_STATEMENT_TIMEOUT PT10S do not bound");
    }

    @Test
    void warnsInsteadWhenTheStatementTimeoutIsOff() {
        IdempotencyStaleWindowGuard guard =
                guard(Duration.ofSeconds(30), TRANSACTION_TIMEOUT, CONNECTION_WAIT, "-c statement_timeout=0");

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(warning()).startsWith("TX_TIMEOUT PT15S and DB_STATEMENT_TIMEOUT PT0S do not bound");
    }

    @Test
    void followsARaisedTransactionTimeout() {
        IdempotencyStaleWindowGuard guard =
                guard(Duration.ofSeconds(30), Duration.ofSeconds(40), CONNECTION_WAIT, STATEMENT_TIMEOUT);

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PT30S is shorter than the PT55S");
    }

    @ParameterizedTest
    @CsvSource({
        "-c statement_timeout=10s, PT10S",
        "-c statement_timeout=500ms, PT0.5S",
        "-c statement_timeout=10000, PT10S",
        "-c statement_timeout=2min, PT2M",
        "-c statement_timeout=1h, PT1H",
        "-c statement_timeout=1d, PT24H",
        "-c statement_timeout=1500us, PT0.0015S",
        "-c statement_timeout=1.5s, PT1.5S",
        "-c statement_timeout=0, PT0S",
        "'', PT0S"
    })
    void readsTheStatementTimeoutAsAPostgresqlTime(String options, Duration statementTimeout) {
        assertThat(IdempotencyStaleWindowGuard.statementTimeout(options)).isEqualTo(statementTimeout);
    }

    @Test
    void refusesAStatementTimeoutItCannotRead() {
        IdempotencyStaleWindowGuard guard =
                guard(Duration.ofSeconds(30), TRANSACTION_TIMEOUT, CONNECTION_WAIT, "-c statement_timeout=ten");

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB_STATEMENT_TIMEOUT ten is not a PostgreSQL time such as 10s, 500ms"
                        + " or a number of milliseconds");
    }

    private static IdempotencyStaleWindowGuard guard(
            Duration staleAfter, Duration transactionTimeout, Duration connectionWait, String dataSourceOptions) {
        IdempotencyProperties properties =
                new IdempotencyProperties(Duration.ofHours(24), staleAfter, Duration.ofMinutes(10));
        TransactionProperties transaction = new TransactionProperties();
        transaction.setDefaultTimeout(transactionTimeout);
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setConnectionTimeout(connectionWait.toMillis());
        dataSource.addDataSourceProperty("options", dataSourceOptions);
        return new IdempotencyStaleWindowGuard(properties, transaction, dataSource);
    }

    private String warning() {
        assertThat(appender.list)
                .singleElement()
                .extracting(ILoggingEvent::getLevel)
                .isEqualTo(Level.WARN);
        return appender.list.get(0).getFormattedMessage();
    }
}
