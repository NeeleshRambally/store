package com.example.store.integration;

import com.example.store.dto.CustomerDTO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
        ResponseEntity<CustomerDTO[]> customers = restTemplate.getForEntity("/customer", CustomerDTO[].class);
        assertThat(customers.getBody())
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
    void listsCustomersWithTheirOrders() {
        ResponseEntity<CustomerDTO[]> response = restTemplate.getForEntity("/customer", CustomerDTO[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSizeGreaterThanOrEqualTo(100);
        assertThat(response.getBody()[0].orders()).isNotEmpty();
    }
}
