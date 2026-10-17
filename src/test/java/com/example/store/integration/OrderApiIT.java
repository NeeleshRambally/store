package com.example.store.integration;

import com.example.store.dto.OrderDTO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsOrderForExistingCustomer() {
        ResponseEntity<OrderDTO> response = restTemplate.postForEntity(
                "/order", Map.of("description", "Analytical Engine", "customerId", 1), OrderDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isGreaterThan(10000L);
        assertThat(response.getBody().description()).isEqualTo("Analytical Engine");
        assertThat(response.getBody().customer().id()).isEqualTo(1L);
        assertThat(response.getBody().customer().name()).isEqualTo("Muriel Donnelly");
    }

    @Test
    void returnsNotFoundForUnknownCustomer() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/order", Map.of("description", "Analytical Engine", "customerId", 999999), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getDetail()).isEqualTo("Customer with id 999999 was not found");
    }

    @Test
    void rejectsOrderWithoutCustomer() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.postForEntity("/order", Map.of("description", "Orphan"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsOrdersWithTheirCustomer() {
        ResponseEntity<OrderDTO[]> response = restTemplate.getForEntity("/order", OrderDTO[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSizeGreaterThanOrEqualTo(10000);
        assertThat(response.getBody()[0].customer().name()).isNotBlank();
    }
}
