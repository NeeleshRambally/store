package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

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
    void readinessIncludesTheDatabaseWhileHealthDoesNot() {
        ResponseEntity<String> readiness =
                restTemplate.getForEntity(management("/actuator/health/readiness"), String.class);
        ResponseEntity<String> health = restTemplate.getForEntity(management("/actuator/health"), String.class);

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody())
                .contains("\"status\":\"UP\"")
                .contains("\"db\":{\"status\":\"UP\"}")
                .contains("\"readinessState\"");
        assertThat(health.getBody()).doesNotContain("db");
    }

    @Test
    void infoReportsTheBuildArtifactAndVersion() {
        ResponseEntity<String> response = restTemplate.getForEntity(management("/actuator/info"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"artifact\":\"store\"").contains("\"version\":\"1.0.0-SNAPSHOT\"");
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
    void requestLatencyIsPublishedInTheServiceLevelBuckets() {
        assertThat(restTemplate
                        .getForEntity(application("/order/1"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String scrape = restTemplate
                .getForEntity(management("/actuator/prometheus"), String.class)
                .getBody();

        List<String> buckets = scrape.lines()
                .filter(line -> line.startsWith("http_server_requests_seconds_bucket{"))
                .filter(line -> line.contains("method=\"GET\""))
                .filter(line -> line.contains("status=\"200\""))
                .filter(line -> line.contains("uri=\"/order/{id}\""))
                .toList();
        assertThat(buckets)
                .extracting(line -> line.replaceAll(".*le=\"([^\"]+)\".*", "$1"))
                .containsExactly(
                        "0.005", "0.01", "0.025", "0.05", "0.1", "0.25", "0.5", "1.0", "2.5", "5.0", "10.0", "+Inf");
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
