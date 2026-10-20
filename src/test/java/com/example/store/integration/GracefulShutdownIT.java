package com.example.store.integration;

import com.example.store.StoreApplication;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

// Starts a second application against the shared container and closes it while a request waits for a lock on order.
class GracefulShutdownIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Autowired
    private PostgreSQLContainer<?> container;

    @Test
    void aRequestInFlightWhenTheApplicationClosesIsAnsweredWhileNewConnectionsAreRefused() throws Exception {
        try (ConfigurableApplicationContext application = start()) {
            int port = application.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            int managementPort =
                    application.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
            CompletableFuture<HttpResponse<String>> held;
            CompletableFuture<Void> closed;
            try (TableLock lock = TableLock.on(container, "\"order\"")) {
                held = lock.holdWaiting(() -> get(port, "/order?size=5"));
                closed = CompletableFuture.runAsync(application::close);
                awaitRefusedConnection(port);

                assertThat(held).isNotDone();
                assertThat(get(managementPort, "/actuator/health/readiness")).satisfies(readiness -> {
                    assertThat(readiness.statusCode()).isEqualTo(503);
                    assertThat(readiness.body()).contains("\"status\":\"OUT_OF_SERVICE\"");
                });
                assertThat(get(managementPort, "/actuator/health/liveness").statusCode())
                        .isEqualTo(200);
            }

            assertThat(held).succeedsWithin(TIMEOUT).satisfies(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains("\"content\":[");
            });
            assertThat(closed).succeedsWithin(TIMEOUT);
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(StoreApplication.class)
                .run(
                        "--spring.datasource.url=" + container.getJdbcUrl(),
                        "--spring.datasource.username=" + container.getUsername(),
                        "--spring.datasource.password=" + container.getPassword(),
                        "--spring.liquibase.enabled=false",
                        "--server.port=0",
                        "--management.server.port=0");
    }

    private HttpResponse<String> get(int port, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(TIMEOUT)
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();
    }

    private static void awaitRefusedConnection(int port) throws IOException, InterruptedException {
        for (int poll = 0; poll < 3000; poll++) {
            try {
                new Socket("localhost", port).close();
            } catch (ConnectException refused) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Port " + port + " still accepted connections after the shutdown began");
    }
}
