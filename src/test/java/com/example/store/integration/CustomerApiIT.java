package com.example.store.integration;

import com.example.store.dto.CustomerDTO;
import com.example.store.dto.PageResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

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
        ResponseEntity<PageResponse<CustomerDTO>> response = restTemplate.exchange(
                "/customer?name=%25", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).isEmpty();
    }

    @Test
    void capsPageSizeAtOneHundred() {
        ResponseEntity<PageResponse<CustomerDTO>> response = restTemplate.exchange(
                "/customer?size=1000", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertThat(response.getBody().size()).isEqualTo(100);
        assertThat(response.getBody().content()).hasSize(100);
    }

    @Test
    void rejectsUnknownSortProperty() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity("/customer?sort=nope", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
