package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void livenessAndReadinessProbesAreExposedOnTheManagementPort() {
        assertThat(restTemplate
                        .getForEntity(management("/actuator/health/liveness"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate
                        .getForEntity(management("/actuator/health/readiness"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthReportsUpWithoutDetails() {
        ResponseEntity<String> response = restTemplate.getForEntity(management("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"").doesNotContain("db");
    }

    @Test
    void prometheusMetricsAreScrapableOnTheManagementPort() {
        assertThat(restTemplate
                        .getForEntity(application("/order/1"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = restTemplate.getForEntity(management("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("jvm_memory_used_bytes")
                .contains("http_server_requests_seconds")
                .contains("cache_gets_total");
    }

    @Test
    void sensitiveEndpointsAreNotExposed() {
        assertThat(restTemplate
                        .getForEntity(management("/actuator/env"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate
                        .getForEntity(management("/actuator/beans"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void actuatorIsAbsentFromTheApplicationPort() {
        assertThat(restTemplate
                        .getForEntity(application("/actuator/health"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String application(String path) {
        return "http://localhost:" + serverPort + path;
    }

    private String management(String path) {
        return "http://localhost:" + managementPort + path;
    }
}
