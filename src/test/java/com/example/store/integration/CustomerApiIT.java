package com.example.store.integration;

import com.example.store.dto.CustomerDTO;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsCustomerWithGeneratedId() {
        ResponseEntity<CustomerDTO> response =
                restTemplate.postForEntity("/customer", Map.of("name", "Ada Lovelace"), CustomerDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isGreaterThan(100L);
        assertThat(response.getBody().name()).isEqualTo("Ada Lovelace");
        assertThat(response.getBody().orders()).isEmpty();
        assertThat(response.getBody().orderCount()).isZero();
    }

    @Test
    void listsTheNewestTwentyOrdersAndTheTotalOfEachCustomer() {
        Long expectedCount =
                jdbcTemplate.queryForObject("select count(*) from \"order\" where customer_id = 1", Long.class);
        List<Long> expectedIds = jdbcTemplate
                .queryForList("select id from \"order\" where customer_id = 1 order by id desc limit 20", Long.class)
                .stream()
                .sorted()
                .toList();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/customer?size=5", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content")).hasSize(5).allSatisfy(customer -> {
            assertThat(customer.get("orders").size()).isLessThanOrEqualTo(20);
            assertThat(customer.get("orderCount").asLong())
                    .isGreaterThanOrEqualTo(customer.get("orders").size());
        });
        JsonNode first = response.getBody().get("content").get(0);
        assertThat(first.get("id").asLong()).isEqualTo(1L);
        assertThat(expectedCount).isGreaterThan(20L);
        assertThat(first.get("orderCount").asLong()).isEqualTo(expectedCount);
        assertThat(first.get("orders").findValues("id").stream()
                        .map(JsonNode::asLong)
                        .toList())
                .hasSize(20)
                .isEqualTo(expectedIds);
    }

    @Test
    void countsTheOrdersOfACreatedCustomer() {
        CustomerDTO created = restTemplate
                .postForEntity("/customer", Map.of("name", "Counted"), CustomerDTO.class)
                .getBody();
        assertThat(created.orderCount()).isZero();
        Long orderId = restTemplate
                .postForEntity(
                        "/order",
                        Map.of("description", "First order", "customerId", created.id(), "productIds", List.of(1)),
                        OrderDTO.class)
                .getBody()
                .id();

        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/customer?after=" + (created.id() - 1) + "&size=1", JsonNode.class);

        JsonNode customer = response.getBody().get("content").get(0);
        assertThat(customer.get("id").asLong()).isEqualTo(created.id());
        assertThat(customer.get("orderCount").asLong()).isEqualTo(1L);
        assertThat(customer.get("orders").findValues("id").stream()
                        .map(JsonNode::asLong)
                        .toList())
                .containsExactly(orderId);
    }

    @Test
    void getsCustomerByIdWithItsNewestOrdersAndTheTotal() {
        Long expectedCount =
                jdbcTemplate.queryForObject("select count(*) from \"order\" where customer_id = 1", Long.class);
        List<Long> expectedIds = jdbcTemplate
                .queryForList("select id from \"order\" where customer_id = 1 order by id desc limit 20", Long.class)
                .stream()
                .sorted()
                .toList();

        ResponseEntity<CustomerDTO> response = restTemplate.getForEntity("/customer/1", CustomerDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(1L);
        assertThat(response.getBody().name()).isEqualTo("Muriel Donnelly");
        assertThat(response.getBody().orderCount()).isEqualTo(expectedCount);
        assertThat(response.getBody().orders()).extracting(order -> order.id()).isEqualTo(expectedIds);
    }

    @Test
    void returnsNotFoundForUnknownCustomer() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/customer/999999", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getDetail()).isEqualTo("Customer with id 999999 was not found");
    }

    @Test
    void rejectsNonNumericCustomerId() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/customer/abc", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("Failed to convert 'id' with value: 'abc'");
    }

    @Test
    void rejectsAHexadecimalCustomerId() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/customer/0x1", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("Failed to convert 'id' with value: '0x1'");
    }

    @Test
    void rejectsACustomerIdWithALeadingZero() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/customer/01", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("Failed to convert 'id' with value: '01'");
    }

    @Test
    void rejectsClientSuppliedId() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.postForEntity("/customer", Map.of("id", 5, "name", "Overwrite"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<PageResponse<CustomerDTO>> customers =
                restTemplate.exchange("/customer?size=10", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(customers.getBody().content())
                .filteredOn(customer -> customer.id() == 5L)
                .extracting(CustomerDTO::name)
                .containsExactly("Jean Daniel");
    }

    @Test
    void rejectsBlankName() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.postForEntity("/customer", Map.of("name", ""), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsKey("errors");
    }

    @Test
    void listsCustomersPageWithTheirOrders() {
        ResponseEntity<PageResponse<CustomerDTO>> response = restTemplate.exchange(
                "/customer?page=1&size=5", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageResponse<CustomerDTO> page = response.getBody();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(5);
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(100);
        assertThat(page.content()).hasSize(5);
        assertThat(page.content().get(0).id()).isEqualTo(6L);
        assertThat(page.content().get(0).orders()).isNotEmpty();
    }

    @Test
    void searchesByWordSubstringIgnoringCase() {
        ResponseEntity<PageResponse<CustomerDTO>> response = restTemplate.exchange(
                "/customer?name=DONN", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).isNotEmpty();
        assertThat(response.getBody().content())
                .allSatisfy(
                        customer -> assertThat(customer.name().toLowerCase()).contains("donn"));
        assertThat(response.getBody().content()).extracting(CustomerDTO::name).contains("Muriel Donnelly");
        assertThat(response.getBody().content().get(0).orders()).isNotEmpty();
    }

    @Test
    void searchTotalsAndTheSecondPageMatchTheDatabase() {
        List<Long> matches = jdbcTemplate.queryForList(
                "select id from customer where strpos(lower(name), 'a') > 0 order by id", Long.class);
        assertThat(matches).hasSizeGreaterThan(10);

        for (int page = 0; page < 2; page++) {
            ResponseEntity<PageResponse<CustomerDTO>> response = restTemplate.exchange(
                    "/customer?name=a&size=5&page=" + page,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {});

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().totalElements()).isEqualTo(matches.size());
            assertThat(response.getBody().totalPages()).isEqualTo((matches.size() + 4) / 5);
            assertThat(response.getBody().content())
                    .extracting(CustomerDTO::id)
                    .containsExactlyElementsOf(matches.subList(page * 5, page * 5 + 5));
        }
    }

    @Test
    void searchTreatsWildcardsLiterally() {
        restTemplate.postForEntity("/customer", Map.of("name", "Hundred%Percent"), CustomerDTO.class);
        URI uri = URI.create(restTemplate.getRootUri() + "/customer?name=%25&size=100");

        ResponseEntity<PageResponse<CustomerDTO>> response =
                restTemplate.exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).isNotEmpty().allSatisfy(customer -> assertThat(customer.name())
                .contains("%"));
    }

    @Test
    void searchMatchesInsideAHyphenatedOrApostrophisedWord() {
        restTemplate.postForEntity("/customer", Map.of("name", "Jean-Pierre O'Neil"), CustomerDTO.class);

        assertThat(search("n-P")).contains("Jean-Pierre O'Neil").allSatisfy(name -> assertThat(name)
                .contains("n-P"));
        assertThat(search("O'N")).contains("Jean-Pierre O'Neil").allSatisfy(name -> assertThat(name)
                .contains("O'N"));
    }

    @Test
    void searchTreatsAnUnderscoreLiterally() {
        restTemplate.postForEntity("/customer", Map.of("name", "Under_Score"), CustomerDTO.class);
        restTemplate.postForEntity("/customer", Map.of("name", "Under Score"), CustomerDTO.class);

        assertThat(search("r_S"))
                .contains("Under_Score")
                .doesNotContain("Under Score")
                .allSatisfy(name -> assertThat(name).contains("_"));
    }

    @Test
    void searchTreatsTheEscapeCharacterLiterally() {
        restTemplate.postForEntity("/customer", Map.of("name", "Wow!Factor"), CustomerDTO.class);
        restTemplate.postForEntity("/customer", Map.of("name", "WowFactor"), CustomerDTO.class);

        assertThat(search("w!F"))
                .contains("Wow!Factor")
                .doesNotContain("WowFactor")
                .allSatisfy(name -> assertThat(name).contains("!"));
        assertThat(search("w%21F")).isEqualTo(search("w!F"));
    }

    private List<String> search(String word) {
        URI uri = URI.create(restTemplate.getRootUri() + "/customer?name=" + word + "&size=100");
        ResponseEntity<PageResponse<CustomerDTO>> response =
                restTemplate.exchange(uri, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().content().stream().map(CustomerDTO::name).toList();
    }

    @Test
    void rejectsAnEmptyNameQuery() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/customer?name=", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("name");
    }

    @Test
    void rejectsANulCharacterInTheNameQuery() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                URI.create(restTemplate.getRootUri() + "/customer?name=a%00b"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("name");
    }

    @Test
    void rejectsARepeatedName() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/customer?name=onne&name=onne", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("'name' must not be repeated");
        assertThat(response.getBody().getProperties().get("errors"))
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .extracting(error -> error.get("field"))
                .containsExactly("name");
    }

    @Test
    void capsPageSizeAtOneHundred() {
        ResponseEntity<PageResponse<CustomerDTO>> response = restTemplate.exchange(
                "/customer?size=1000", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getBody().size()).isEqualTo(100);
        assertThat(response.getBody().content()).hasSize(100);
    }

    @Test
    void sortsANameSearchByNameDescending() {
        ResponseEntity<PageResponse<CustomerDTO>> response = restTemplate.exchange(
                "/customer?name=a&sort=name,desc&size=5", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content())
                .hasSize(5)
                .extracting(CustomerDTO::name)
                .isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    void sortedPagesHaveDeterministicBoundaries() {
        restTemplate.postForEntity("/customer", Map.of("name", "Aaron Tie"), CustomerDTO.class);
        restTemplate.postForEntity("/customer", Map.of("name", "Aaron Tie"), CustomerDTO.class);
        List<Long> expected =
                jdbcTemplate.queryForList("select id from customer order by name asc, id asc limit 100", Long.class);

        List<Long> collected = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            ResponseEntity<JsonNode> response =
                    restTemplate.getForEntity("/customer?sort=name,asc&size=50&page=" + page, JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            collected.addAll(ids(response.getBody()));
        }

        assertThat(collected).hasSize(100).containsExactlyElementsOf(expected);
    }

    @Test
    void rejectsUnknownSortProperty() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/customer?sort=nope", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("Unknown sort property: nope");
    }

    @Test
    void walksCustomersWithACursor() {
        ResponseEntity<JsonNode> first = restTemplate.getForEntity("/customer?after=0&size=5", JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(first.getBody())).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(first.getBody().get("size").asInt()).isEqualTo(5);
        assertThat(first.getBody().get("nextCursor").asLong()).isEqualTo(5L);
        assertThat(first.getBody().get("content").get(0).get("orders")).isNotEmpty();
        assertThat(first.getBody().has("totalElements")).isFalse();

        ResponseEntity<JsonNode> second = restTemplate.getForEntity("/customer?after=5&size=5", JsonNode.class);

        assertThat(ids(second.getBody())).containsExactly(6L, 7L, 8L, 9L, 10L);
        assertThat(second.getBody().get("nextCursor").asLong()).isEqualTo(10L);
    }

    @Test
    void cursorWalkEndsWithANullCursor() {
        ResponseEntity<JsonNode> last = restTemplate.getForEntity("/customer?size=1&sort=id,desc", JsonNode.class);
        long maxId = last.getBody().get("content").get(0).get("id").asLong();

        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/customer?after=" + (maxId - 1) + "&size=5", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(response.getBody())).containsExactly(maxId);
        assertThat(response.getBody().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void walksANameSearchWithACursor() {
        ResponseEntity<JsonNode> first = restTemplate.getForEntity("/customer?name=a&after=0&size=3", JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(first.getBody())).hasSize(3).isSorted();
        assertThat(first.getBody().get("content").findValuesAsText("name"))
                .allSatisfy(name -> assertThat(name.toLowerCase()).contains("a"));
        long cursor = first.getBody().get("nextCursor").asLong();
        assertThat(cursor).isEqualTo(ids(first.getBody()).get(2));

        ResponseEntity<JsonNode> second =
                restTemplate.getForEntity("/customer?name=a&after=" + cursor + "&size=3", JsonNode.class);

        assertThat(ids(second.getBody())).hasSize(3).allSatisfy(id -> assertThat(id)
                .isGreaterThan(cursor));
        assertThat(second.getBody().get("content").get(0).get("orders")).isNotEmpty();
    }

    @Test
    void rejectsCursorCombinedWithPage() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/customer?after=0&page=1", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("after cannot be combined with page or sort");
    }

    @Test
    void rejectsCursorCombinedWithSortOnANameSearch() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/customer?name=a&after=0&sort=id,desc", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).isEqualTo("after cannot be combined with page or sort");
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }
}
