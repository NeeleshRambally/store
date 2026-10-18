package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdIT extends AbstractIntegrationTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void anOrderReadEchoesTheInboundRequestId() {
        ResponseEntity<String> response = exchange("/order/1", "X-Request-Id", "gateway-42");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("gateway-42");
    }

    @Test
    void aRequestWithoutAnIdGetsAGeneratedOne() {
        ResponseEntity<String> response = restTemplate.getForEntity("/order/1", String.class);

        assertThat(response.getHeaders().getFirst("X-Request-Id")).matches(UUID_PATTERN);
    }

    @Test
    void aNotModifiedResponseCarriesTheRequestId() {
        String etag =
                restTemplate.getForEntity("/order/1", String.class).getHeaders().getETag();

        ResponseEntity<String> response =
                exchange("/order/1", HttpHeaders.IF_NONE_MATCH, etag, "X-Request-Id", "gateway-304");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo("gateway-304");
    }

    private ResponseEntity<String> exchange(String path, String... headerPairs) {
        HttpHeaders headers = new HttpHeaders();
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.set(headerPairs[i], headerPairs[i + 1]);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
