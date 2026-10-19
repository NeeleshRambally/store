package com.example.store.integration;

import com.example.store.dto.OrderDTO;
import com.example.store.dto.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrderApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void rejectsRepeatedProductIds() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Twice", "customerId", 1, "productIds", List.of(2, 2)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("productIds");
    }

    @Test
    void aFractionalCustomerIdIsRejectedWithoutCreatingAnOrder() {
        Long before = ordersDescribed("Fractional customer id");

        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Fractional customer id", "customerId", 5.7, "productIds", List.of(2)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("customerId");
        assertThat(ordersDescribed("Fractional customer id")).isEqualTo(before);
    }

    @Test
    void aLineBreakInTheDescriptionIsRejectedWithoutCreatingAnOrder() {
        Long before = ordersDescribed("Line one\nLine two");

        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Line one\nLine two", "customerId", 5, "productIds", List.of(2)),
                ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("description");
        assertThat(ordersDescribed("Line one\nLine two")).isEqualTo(before);
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
    void rejectsNonNumericOrderId() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/order/abc", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("Failed to convert 'id' with value: 'abc'");
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
    void sortsOrdersByDescriptionDescending() {
        ResponseEntity<PageResponse<OrderDTO>> response = restTemplate.exchange(
                "/order?sort=description,desc&size=5", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content())
                .hasSize(5)
                .extracting(OrderDTO::description)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void sortedPagesHaveDeterministicBoundaries() {
        List<Long> expected = jdbcTemplate.queryForList(
                "select id from \"order\" order by description desc, id desc limit 100", Long.class);

        List<Long> collected = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            ResponseEntity<JsonNode> response =
                    restTemplate.getForEntity("/order?sort=description,desc&size=50&page=" + page, JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            collected.addAll(ids(response.getBody()));
        }

        assertThat(collected).hasSize(100).containsExactlyElementsOf(expected);
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
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("after");
    }

    @Test
    void rejectsANegativePage() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/order?page=-1", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("page");
    }

    @Test
    void filtersOrdersByCustomer() {
        Long expected = jdbcTemplate.queryForObject("select count(*) from \"order\" where customer_id = 1", Long.class);

        ResponseEntity<PageResponse<OrderDTO>> response = restTemplate.exchange(
                "/order?customerId=1&size=100", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().totalElements()).isEqualTo(expected);
        assertThat(response.getBody().content())
                .isNotEmpty()
                .allSatisfy(order -> assertThat(order.customer().id()).isEqualTo(1L));
        assertThat(response.getBody().content())
                .filteredOn(order -> order.id() <= 10000L)
                .isNotEmpty()
                .allSatisfy(order -> assertThat(order.products()).isNotEmpty());
    }

    @Test
    void filtersOrdersByProduct() {
        Long expected =
                jdbcTemplate.queryForObject("select count(*) from order_product where product_id = 2", Long.class);

        ResponseEntity<PageResponse<OrderDTO>> response = restTemplate.exchange(
                "/order?productId=2&size=50&sort=description,desc",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().totalElements()).isEqualTo(expected);
        assertThat(response.getBody().content()).hasSize(50).allSatisfy(order -> assertThat(order.products())
                .extracting(p -> p.id())
                .contains(2L));
        assertThat(response.getBody().content())
                .extracting(OrderDTO::description)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void walksACustomersOrdersWithACursor() {
        List<Long> expected = jdbcTemplate.queryForList(
                "select id from \"order\" where customer_id = 1 order by id limit 10", Long.class);

        ResponseEntity<JsonNode> first =
                restTemplate.getForEntity("/order?customerId=1&after=0&size=5", JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(first.getBody())).isEqualTo(expected.subList(0, 5));
        assertThat(first.getBody().get("nextCursor").asLong()).isEqualTo(expected.get(4));
        first.getBody().get("content").forEach(order -> assertThat(
                        order.get("customer").get("id").asLong())
                .isEqualTo(1L));

        ResponseEntity<JsonNode> second = restTemplate.getForEntity(
                "/order?customerId=1&after=" + first.getBody().get("nextCursor").asLong() + "&size=5", JsonNode.class);

        assertThat(ids(second.getBody())).isEqualTo(expected.subList(5, 10));
        assertThat(second.getBody().get("nextCursor").asLong()).isEqualTo(expected.get(9));
    }

    @Test
    void walksAProductsOrdersWithACursor() {
        List<Long> expected = jdbcTemplate.queryForList(
                "select order_id from order_product where product_id = 2 order by order_id limit 3", Long.class);

        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/order?productId=2&after=0&size=3", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(response.getBody())).isEqualTo(expected);
        assertThat(response.getBody().get("nextCursor").asLong()).isEqualTo(expected.get(2));
    }

    @Test
    void rejectsCustomerAndProductFiltersTogether() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/order?customerId=1&productId=2", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("customerId and productId cannot be combined");
    }

    @Test
    void returnsAnEmptyPageForAnUnknownCustomer() {
        ResponseEntity<PageResponse<OrderDTO>> response = restTemplate.exchange(
                "/order?customerId=999999", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).isEmpty();
        assertThat(response.getBody().totalElements()).isZero();
    }

    @Test
    void returnsAnEmptyCursorPageForAnUnknownProduct() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/order?productId=999999&after=0", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content")).isEmpty();
        assertThat(response.getBody().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void rejectsAZeroProductId() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/order?productId=0", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("productId");
    }

    private Long ordersDescribed(String description) {
        return jdbcTemplate.queryForObject(
                "select count(*) from \"order\" where description = ?", Long.class, description);
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }
}
