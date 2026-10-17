package com.example.store.integration;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

// Pins the number of database round trips per request so an N+1 regression fails the build.
class QueryCountIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void resetStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void orderPageUsesTwoStatements() {
        ResponseEntity<String> response = restTemplate.getForEntity("/order?size=100", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void orderByIdUsesOneStatement() {
        ResponseEntity<String> response = restTemplate.getForEntity("/order/1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void customerSearchWithOrdersUsesThreeStatements() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?name=a&size=5", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void customerPageWithOrdersUsesThreeStatements() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?size=50", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }
}
