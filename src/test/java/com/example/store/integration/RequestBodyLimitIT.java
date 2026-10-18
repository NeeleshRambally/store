package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyLimitIT extends AbstractIntegrationTest {

    private static final int LIMIT = 65536;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aDeclaredLengthOverTheBoundIsAPayloadTooLargeProblemDetail() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/order", HttpMethod.POST, new HttpEntity<>(orderJsonOfLength(LIMIT + 1), jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(response.getBody()).contains("\"detail\":\"Request body exceeds 65536 bytes\"");
    }

    @Test
    void aChunkedBodyOverTheBoundIsRejectedWhileItIsRead() {
        byte[] bytes = orderJsonOfLength(LIMIT + 1).getBytes(StandardCharsets.UTF_8);
        HttpEntity<InputStreamResource> chunked =
                new HttpEntity<>(new InputStreamResource(new ByteArrayInputStream(bytes)), jsonHeaders());

        ResponseEntity<String> response = restTemplate.exchange("/order", HttpMethod.POST, chunked, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).contains("\"detail\":\"Request body exceeds 65536 bytes\"");
    }

    @Test
    void aNormalChunkedOrderIsStillCreated() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Bounded order", "customerId", 1, "productIds", List.of(1)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String orderJsonOfLength(int length) {
        String prefix = "{\"description\":\"";
        String suffix = "\",\"customerId\":1,\"productIds\":[1]}";
        return prefix + "a".repeat(length - prefix.length() - suffix.length()) + suffix;
    }
}
