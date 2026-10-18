package com.example.store.integration;

import com.example.store.dto.OrderDTO;
import com.example.store.dto.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
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

    @Test
    void walksOrdersWithACursor() {
        ResponseEntity<JsonNode> first = restTemplate.getForEntity("/order?after=0&size=5", JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(first.getBody())).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(first.getBody().get("size").asInt()).isEqualTo(5);
        assertThat(first.getBody().get("nextCursor").asLong()).isEqualTo(5L);
        assertThat(first.getBody()
                        .get("content")
                        .get(0)
                        .get("customer")
                        .get("name")
                        .asText())
                .isEqualTo("Vicki Kutch");
        assertThat(first.getBody().get("content").get(0).get("products")).isNotEmpty();
        assertThat(first.getBody().has("totalElements")).isFalse();

        ResponseEntity<JsonNode> second = restTemplate.getForEntity(
                "/order?after=" + first.getBody().get("nextCursor").asLong() + "&size=5", JsonNode.class);

        assertThat(ids(second.getBody())).containsExactly(6L, 7L, 8L, 9L, 10L);
        assertThat(second.getBody().get("nextCursor").asLong()).isEqualTo(10L);
    }

    @Test
    void cursorWalkEndsWithANullCursor() {
        ResponseEntity<JsonNode> last = restTemplate.getForEntity("/order?size=1&sort=id,desc", JsonNode.class);
        long maxId = last.getBody().get("content").get(0).get("id").asLong();

        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/order?after=" + (maxId - 1) + "&size=5", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(response.getBody())).containsExactly(maxId);
        assertThat(response.getBody().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void rejectsCursorCombinedWithPage() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/order?after=0&page=1", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("after cannot be combined with page or sort");
    }

    @Test
    void rejectsCursorCombinedWithSort() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/order?after=0&sort=description", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("after cannot be combined with page or sort");
    }

    @Test
    void rejectsNegativeCursor() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/order?after=-1", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }
}
