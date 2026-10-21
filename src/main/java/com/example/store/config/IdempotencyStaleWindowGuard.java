package com.example.store.config;

import com.zaxxer.hikari.HikariDataSource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.transaction.TransactionProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Refuses a stale window shorter than the time a keyed POST can hold its claim: the wait for a connection, the
// transaction timeout and the statement timeout that bounds a statement started before the deadline.
@Slf4j
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IdempotencyStaleWindowGuard implements InitializingBean {

    private static final Pattern STATEMENT_TIMEOUT = Pattern.compile("statement_timeout=(\\S*)");
    private static final Pattern POSTGRESQL_TIME = Pattern.compile("(\\d+(?:\\.\\d+)?)(us|ms|s|min|h|d)?");
    private static final Map<String, Duration> UNITS = Map.of(
            "us", Duration.ofNanos(1_000),
            "ms", Duration.ofMillis(1),
            "s", Duration.ofSeconds(1),
            "min", Duration.ofMinutes(1),
            "h", Duration.ofHours(1),
            "d", Duration.ofDays(1));

    private final Duration staleAfter;
    private final Duration transactionTimeout;
    private final Duration connectionWait;
    private final String dataSourceOptions;

    public IdempotencyStaleWindowGuard(
            IdempotencyProperties properties, TransactionProperties transaction, HikariDataSource dataSource) {
        this.staleAfter = properties.staleAfter();
        this.transactionTimeout = transaction.getDefaultTimeout();
        this.connectionWait = Duration.ofMillis(dataSource.getConnectionTimeout());
        this.dataSourceOptions = dataSource.getDataSourceProperties().getProperty("options", "");
    }

    @Override
    public void afterPropertiesSet() {
        Duration statementTimeout = statementTimeout(dataSourceOptions);
        if (transactionTimeout == null || transactionTimeout.isNegative() || statementTimeout.isZero()) {
            log.warn(
                    "TX_TIMEOUT {} and DB_STATEMENT_TIMEOUT {} do not bound a keyed POST: one that runs longer than"
                            + " the stale window {} is taken over and fails without creating anything",
                    Objects.toString(transactionTimeout, "unset"),
                    statementTimeout,
                    staleAfter);
            return;
        }
        Duration bound = connectionWait.plus(transactionTimeout).plus(statementTimeout);
        if (staleAfter.compareTo(bound) < 0) {
            throw new IllegalStateException("store.idempotency.stale-after " + staleAfter + " is shorter than the "
                    + bound + " a keyed POST can hold its claim (the connection wait " + connectionWait
                    + ", TX_TIMEOUT " + transactionTimeout + " and DB_STATEMENT_TIMEOUT " + statementTimeout
                    + " together): set IDEMPOTENCY_STALE_AFTER to at least " + bound);
        }
    }

    static Duration statementTimeout(String options) {
        Matcher setting = STATEMENT_TIMEOUT.matcher(options);
        if (!setting.find()) {
            return Duration.ZERO;
        }
        Matcher time = POSTGRESQL_TIME.matcher(setting.group(1));
        if (!time.matches()) {
            throw new IllegalStateException("DB_STATEMENT_TIMEOUT " + setting.group(1)
                    + " is not a PostgreSQL time such as 10s, 500ms or a number of milliseconds");
        }
        Duration unit = time.group(2) == null ? Duration.ofMillis(1) : UNITS.get(time.group(2));
        return Duration.ofNanos(new BigDecimal(time.group(1))
                .multiply(BigDecimal.valueOf(unit.toNanos()))
                .longValue());
    }
}
