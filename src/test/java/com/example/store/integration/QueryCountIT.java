package com.example.store.integration;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Pins the number of database round trips per request so an N+1 regression fails the build; caches start cold.
class QueryCountIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ApplicationContext applicationContext;

    private Statistics statistics;

    @BeforeEach
    void resetStatistics() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void orderPageWithCustomerAndProductsUsesThreeStatements() {
        ResponseEntity<String> response = restTemplate.getForEntity("/order?size=100", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void productPageWithOrderIdsUsesThreeStatements() {
        ResponseEntity<String> response = restTemplate.getForEntity("/products?size=20", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void productByIdUsesTwoStatementsAndLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/products/2", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void customerByIdUsesTwoStatementsAndLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer/1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void unknownCustomerByIdUsesOneStatement() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer/999999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void secondReadOfACustomerByIdIsNotCached() {
        restTemplate.getForEntity("/customer/1", String.class);
        statistics.clear();

        ResponseEntity<String> response = restTemplate.getForEntity("/customer/1", String.class);

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
    void secondReadOfAnOrderByIdOpensNoSessionAndRunsNoStatement() {
        restTemplate.getForEntity("/order/1", String.class);
        statistics.clear();

        ResponseEntity<String> response = restTemplate.getForEntity("/order/1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isZero();
        assertThat(statistics.getSessionOpenCount()).isZero();
    }

    @Test
    void secondReadOfAProductByIdOpensNoSessionAndRunsNoStatement() {
        restTemplate.getForEntity("/products/2", String.class);
        statistics.clear();

        ResponseEntity<String> response = restTemplate.getForEntity("/products/2", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isZero();
        assertThat(statistics.getSessionOpenCount()).isZero();
    }

    @Test
    void customerSearchWithOrdersUsesThreeStatements() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?name=a&size=5", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void shortFirstPageSkipsTheCountQuery() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/customer?name=onne&size=5", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content").size()).isBetween(1, 4);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void noSessionIsHeldOpenForTheView() {
        assertThat(applicationContext.getBeansOfType(OpenEntityManagerInViewInterceptor.class))
                .isEmpty();
    }

    @Test
    void customerPageWithOrdersUsesThreeStatements() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?size=50", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void orderPageLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/order?size=100", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void customerPageLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?size=50", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void customerSearchLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?name=a&size=5", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void productPageLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/products?size=20", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void orderKeysetPageUsesTwoStatementsAndLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/order?after=0&size=100", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void customerKeysetPageUsesTwoStatementsAndLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?after=0&size=50", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void customerKeysetSearchUsesTwoStatementsAndLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/customer?name=a&after=0&size=5", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void productKeysetPageUsesTwoStatementsAndLoadsNoEntities() {
        ResponseEntity<String> response = restTemplate.getForEntity("/products?after=0&size=20", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void orderPageFilteredByCustomerUsesThreeStatements() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/order?customerId=1&size=20", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content")).hasSize(20).allSatisfy(order -> assertThat(
                        order.get("customer").get("id").asLong())
                .isEqualTo(1L));
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void orderPageFilteredByProductUsesThreeStatements() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/order?productId=2&size=20", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content")).hasSize(20).allSatisfy(order -> assertThat(
                        order.get("products").findValues("id").stream()
                                .map(JsonNode::asLong)
                                .toList())
                .contains(2L));
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    void orderKeysetPageFilteredByCustomerUsesTwoStatements() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/order?customerId=1&after=0&size=20", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content")).hasSize(20).allSatisfy(order -> assertThat(
                        order.get("customer").get("id").asLong())
                .isEqualTo(1L));
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void orderKeysetPageFilteredByProductUsesTwoStatements() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/order?productId=2&after=0&size=20", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content")).hasSize(20).allSatisfy(order -> assertThat(
                        order.get("products").findValues("id").stream()
                                .map(JsonNode::asLong)
                                .toList())
                .contains(2L));
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void emptyOrderPageFilteredByCustomerUsesOneStatement() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/order?customerId=999999", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content")).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void emptyOrderKeysetPageFilteredByCustomerUsesOneStatement() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/order?customerId=999999&after=0", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("content")).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void createOrderWithThreeProductsUsesFourStatementsPlusTheBlockAllocation() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Batched", "customerId", 1, "productIds", List.of(1, 2, 3)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(statistics.getPrepareStatementCount()).isBetween(4L, 5L);
    }
}
