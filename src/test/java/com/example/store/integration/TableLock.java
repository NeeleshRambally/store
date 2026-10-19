package com.example.store.integration;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// Locks a table from its own connection and cancels or terminates the backend of a request that waits for it.
public final class TableLock implements AutoCloseable {

    private static final String WAITING_BACKEND =
            "select a.pid from pg_stat_activity a join pg_locks l on l.pid = a.pid"
                    + " where a.wait_event_type = 'Lock' and not l.granted and l.relation = ?::regclass";

    private final Connection holder;
    private final Connection monitor;
    private final String table;

    private TableLock(Connection holder, Connection monitor, String table) {
        this.holder = holder;
        this.monitor = monitor;
        this.table = table;
    }

    public static TableLock on(PostgreSQLContainer<?> container, String table) throws SQLException {
        Connection holder = connect(container);
        holder.setAutoCommit(false);
        try (Statement statement = holder.createStatement()) {
            statement.execute("lock table " + table + " in access exclusive mode");
        }
        return new TableLock(holder, connect(container), table);
    }

    public <T> T cancelWaiting(Supplier<T> request) throws Exception {
        return signalWaiting("pg_cancel_backend", request);
    }

    public <T> T terminateWaiting(Supplier<T> request) throws Exception {
        return signalWaiting("pg_terminate_backend", request);
    }

    @Override
    public void close() throws SQLException {
        try (holder;
                monitor) {
            holder.rollback();
        }
    }

    private <T> T signalWaiting(String signal, Supplier<T> request) throws Exception {
        CompletableFuture<T> response = CompletableFuture.supplyAsync(request);
        try (PreparedStatement statement = monitor.prepareStatement("select " + signal + "(?)")) {
            statement.setInt(1, waitingBackend());
            statement.execute();
        }
        return response.get(30, TimeUnit.SECONDS);
    }

    private int waitingBackend() throws SQLException, InterruptedException {
        try (PreparedStatement statement = monitor.prepareStatement(WAITING_BACKEND)) {
            statement.setString(1, table);
            for (int poll = 0; poll < 1000; poll++) {
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return rows.getInt(1);
                    }
                }
                Thread.sleep(10);
            }
        }
        throw new IllegalStateException("No request waited for the lock on " + table);
    }

    private static Connection connect(PostgreSQLContainer<?> container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
