package com.example.store.integration;

import com.example.store.dto.OrderDTO;
import com.example.store.dto.PageResponse;
import com.example.store.dto.ProductDTO;
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

class ProductApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsProductWithGeneratedId() {
        ResponseEntity<ProductDTO> response =
                restTemplate.postForEntity("/products", Map.of("description", "Brass Astrolabe"), ProductDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isGreaterThan(100L);
        assertThat(response.getBody().description()).isEqualTo("Brass Astrolabe");
        assertThat(response.getBody().orders()).isEmpty();
    }

    @Test
    void getsProductWithTheIdsOfOrdersContainingIt() {
        ResponseEntity<ProductDTO> response = restTemplate.getForEntity("/products/2", ProductDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(2L);
        assertThat(response.getBody().orders())
                .hasSizeGreaterThanOrEqualTo(200)
                .contains(1L, 43L)
                .isSorted();
    }

    @Test
    void productOrdersReflectAnOrderCreatedAfterItWasCached() {
        Long productId = restTemplate
                .postForEntity("/products", Map.of("description", "Cached Lamp"), ProductDTO.class)
                .getBody()
                .id();
        assertThat(restTemplate
                        .getForEntity("/products/" + productId, ProductDTO.class)
                        .getBody()
                        .orders())
                .isEmpty();

        Long orderId = restTemplate
                .postForEntity(
                        "/order",
                        Map.of("description", "Lamp order", "customerId", 1, "productIds", List.of(productId)),
                        OrderDTO.class)
                .getBody()
                .id();

        ResponseEntity<ProductDTO> response = restTemplate.getForEntity("/products/" + productId, ProductDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().orders()).containsExactly(orderId);
    }

    @Test
    void returnsNotFoundForUnknownProduct() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/products/999999", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getDetail()).isEqualTo("Product with id 999999 was not found");
    }

    @Test
    void listsProductsPageWithTheirOrderIds() {
        ResponseEntity<PageResponse<ProductDTO>> response =
                restTemplate.exchange("/products?size=5", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageResponse<ProductDTO> page = response.getBody();
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(100);
        assertThat(page.content()).hasSize(5);
        assertThat(page.content().get(0).id()).isEqualTo(1L);
        assertThat(page.content())
                .allSatisfy(product -> assertThat(product.orders()).isNotEmpty());
    }

    @Test
    void walksProductsWithACursor() {
        ResponseEntity<JsonNode> first = restTemplate.getForEntity("/products?after=0&size=5", JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(first.getBody())).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(first.getBody().get("size").asInt()).isEqualTo(5);
        assertThat(first.getBody().get("nextCursor").asLong()).isEqualTo(5L);
        assertThat(first.getBody().get("content").get(0).get("orders")).isNotEmpty();
        assertThat(first.getBody().has("totalElements")).isFalse();

        ResponseEntity<JsonNode> second = restTemplate.getForEntity("/products?after=5&size=5", JsonNode.class);

        assertThat(ids(second.getBody())).containsExactly(6L, 7L, 8L, 9L, 10L);
        assertThat(second.getBody().get("nextCursor").asLong()).isEqualTo(10L);
    }

    @Test
    void cursorWalkEndsWithANullCursor() {
        ResponseEntity<JsonNode> last = restTemplate.getForEntity("/products?size=1&sort=id,desc", JsonNode.class);
        long maxId = last.getBody().get("content").get(0).get("id").asLong();

        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/products?after=" + (maxId - 1) + "&size=5", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(response.getBody())).containsExactly(maxId);
        assertThat(response.getBody().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void rejectsCursorCombinedWithPage() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/products?after=0&page=1", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("after cannot be combined with page or sort");
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }
}
