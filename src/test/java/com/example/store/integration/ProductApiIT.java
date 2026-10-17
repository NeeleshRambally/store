package com.example.store.integration;

import com.example.store.dto.PageResponse;
import com.example.store.dto.ProductDTO;

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
}
