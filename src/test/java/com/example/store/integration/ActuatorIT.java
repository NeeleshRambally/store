package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void livenessAndReadinessProbesAreExposed() {
        assertThat(restTemplate
                        .getForEntity("/actuator/health/liveness", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate
                        .getForEntity("/actuator/health/readiness", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void healthReportsUpWithoutDetails() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"").doesNotContain("db");
    }

    @Test
    void prometheusMetricsAreScrapable() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes").contains("http_server_requests_seconds");
    }

    @Test
    void sensitiveEndpointsAreNotExposed() {
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/beans", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
