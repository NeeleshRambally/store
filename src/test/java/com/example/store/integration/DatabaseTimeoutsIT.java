package com.example.store.integration;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

// Reads the database timeouts back from the application's own pool, connections and transaction manager.
class DatabaseTimeoutsIT extends AbstractIntegrationTest {

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JpaTransactionManager transactionManager;

    @Test
    void everyConnectionCancelsAStatementAfterTenSeconds() {
        assertThat(jdbcTemplate.queryForObject("show statement_timeout", String.class))
                .isEqualTo("10s");
    }

    @Test
    void everyConnectionGivesUpOnASilentSocketAfterFifteenSeconds() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getNetworkTimeout()).isEqualTo(15_000);
        }
    }

    @Test
    void theDriverKeepsIdleConnectionsAliveAndBoundsItsCancelRequests() {
        assertThat(dataSource.getDataSourceProperties())
                .containsEntry("tcpKeepAlive", "true")
                .containsEntry("cancelSignalTimeout", "3");
    }

    @Test
    void everyTransactionTimesOutAfterFifteenSeconds() {
        assertThat(transactionManager.getDefaultTimeout()).isEqualTo(15);
    }
}
