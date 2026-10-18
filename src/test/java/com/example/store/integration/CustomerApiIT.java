package com.example.store.integration;

import com.example.store.dto.CustomerDTO;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerApiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void createsCustomerWithGeneratedId() {
        ResponseEntity<CustomerDTO> response =
                restTemplate.postForEntity("/customer", Map.of("name", "Ada Lovelace"), CustomerDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isGreaterThan(100L);
        assertThat(response.getBody().name()).isEqualTo("Ada Lovelace");
        assertThat(response.getBody().orders()).isEmpty();
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
    void rejectsAnEmptyNameQuery() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/customer?name=", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
