package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// Swagger UI on the API port renders the checked-in OpenAPI.yaml; no specification is generated from the code.
class SwaggerUiIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void swaggerUiPageIsServed() {
        ResponseEntity<String> page = restTemplate.getForEntity("/swagger-ui.html", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getHeaders().getContentType()).isNotNull();
        assertThat(page.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML))
                .isTrue();
        assertThat(page.getBody()).contains("Swagger UI", "/webjars/swagger-ui/swagger-ui-bundle.js");
    }

    @Test
    void swaggerUiAssetsAreServedWithoutAVersionInThePath() {
        ResponseEntity<String> script =
                restTemplate.getForEntity("/webjars/swagger-ui/swagger-ui-bundle.js", String.class);
        ResponseEntity<String> styles = restTemplate.getForEntity("/webjars/swagger-ui/swagger-ui.css", String.class);

        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(script.getBody()).contains("SwaggerUIBundle");
        assertThat(styles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(styles.getHeaders().getContentType()).isNotNull();
        assertThat(styles.getHeaders().getContentType().isCompatibleWith(MediaType.valueOf("text/css")))
                .isTrue();
    }

    @Test
    void swaggerUiLoadsTheCheckedInContract() {
        ResponseEntity<String> initializer = restTemplate.getForEntity("/swagger-initializer.js", String.class);

        assertThat(initializer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initializer.getBody()).contains("url: '/openapi.yaml'", "validatorUrl: null");
    }

    @Test
    void checkedInContractIsServedAsItIs() throws IOException {
        ResponseEntity<byte[]> contract = restTemplate.getForEntity("/openapi.yaml", byte[].class);

        assertThat(contract.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contract.getBody()).isEqualTo(Files.readAllBytes(Path.of("OpenAPI.yaml")));
    }

    @Test
    void noSpecificationIsGeneratedFromTheCode() {
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
