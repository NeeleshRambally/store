package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCacheIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void getResponsesCarryAWeakETagAndMustBeRevalidated() {
        ResponseEntity<String> response = restTemplate.getForEntity("/order/1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).startsWith("W/\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    }

    @Test
    void conditionalGetWithTheCurrentETagIsNotModifiedAndHasNoBody() {
        String etag =
                restTemplate.getForEntity("/order/1", String.class).getHeaders().getETag();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);

        ResponseEntity<String> response =
                restTemplate.exchange("/order/1", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getETag()).isEqualTo(etag);
    }

    @Test
    void listResponsesCarryAnETagToo() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?name=onne&size=5", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).startsWith("W/\"");
    }

    @Test
    void createdResponsesCarryNoETag() {
        ResponseEntity<String> response =
                restTemplate.postForEntity("/customer", Map.of("name", "Etag Free"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isNull();
    }
}
