package com.example.store.integration;

import com.example.store.dto.OrderDTO;
import com.example.store.dto.PageResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsOrderForExistingCustomer() {
        ResponseEntity<OrderDTO> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Analytical Engine", "customerId", 1, "productIds", List.of(8, 2)),
                OrderDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isGreaterThan(10000L);
        assertThat(response.getBody().description()).isEqualTo("Analytical Engine");
        assertThat(response.getBody().customer().id()).isEqualTo(1L);
        assertThat(response.getBody().customer().name()).isEqualTo("Muriel Donnelly");
        assertThat(response.getBody().products()).extracting(p -> p.id()).containsExactly(2L, 8L);
        ResponseEntity<OrderDTO> fetched =
                restTemplate.getForEntity("/order/" + response.getBody().id(), OrderDTO.class);
        assertThat(fetched.getBody().products()).extracting(p -> p.id()).containsExactly(2L, 8L);
    }

    @Test
    void returnsNotFoundForUnknownProduct() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Analytical Engine", "customerId", 1, "productIds", List.of(2, 999999)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getDetail()).isEqualTo("Product with id 999999 was not found");
    }

    @Test
    void rejectsOrderWithoutProducts() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Analytical Engine", "customerId", 1, "productIds", List.of()),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsNotFoundForUnknownCustomer() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Analytical Engine", "customerId", 999999, "productIds", List.of(2)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getDetail()).isEqualTo("Customer with id 999999 was not found");
    }

    @Test
    void rejectsOrderWithoutCustomer() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/order", Map.of("description", "Orphan", "productIds", List.of(2)), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getsOrderByIdWithItsCustomer() {
        ResponseEntity<OrderDTO> response = restTemplate.getForEntity("/order/1", OrderDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().description()).isEqualTo("Handcrafted Soft Chair");
        assertThat(response.getBody().customer().name()).isEqualTo("Vicki Kutch");
        assertThat(response.getBody().products()).extracting(p -> p.id()).containsExactly(2L, 8L);
    }

    @Test
    void returnsNotFoundForUnknownOrder() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/order/999999", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getDetail()).isEqualTo("Order with id 999999 was not found");
    }

    @Test
    void listsOrdersPageWithTheirCustomer() {
        ResponseEntity<PageResponse<OrderDTO>> response =
                restTemplate.exchange("/order", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageResponse<OrderDTO> page = response.getBody();
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(10000);
        assertThat(page.content()).hasSize(20);
        assertThat(page.content().get(0).id()).isEqualTo(1L);
        assertThat(page.content().get(0).customer().name()).isEqualTo("Vicki Kutch");
        assertThat(page.content())
                .allSatisfy(order -> assertThat(order.products()).isNotEmpty());
    }

    @Test
    void rejectsSortByNestedProperty() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/order?sort=customer.name", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("Unknown sort property: customer.name");
    }
}
