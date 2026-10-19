package com.example.store.integration;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.CreateProductRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.dto.ProductDTO;
import com.example.store.service.CustomerService;
import com.example.store.service.OrderService;
import com.example.store.service.ProductService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreationLogIT extends AbstractIntegrationTest {

    private final Logger store = (Logger) LoggerFactory.getLogger("com.example.store");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderService orderService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ProductService productService;

    @BeforeEach
    void attachAppender() {
        appender.start();
        store.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        store.detachAppender(appender);
    }

    @Test
    void anOrderCreatedThroughTheApiIsLoggedWithItsCustomerAndProductCount() {
        String description = "Creation log " + UUID.randomUUID();
        ResponseEntity<OrderDTO> response = post(
                "/order",
                Map.of("description", description, "customerId", 2, "productIds", List.of(3, 5)),
                "creation-order",
                OrderDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ILoggingEvent line = creationLineOf("creation-order");
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getLoggerName()).isEqualTo("com.example.store.service.CreationEventLogger");
        assertThat(line.getFormattedMessage())
                .isEqualTo("Created order " + response.getBody().id() + " for customer 2 with 2 products");
    }

    @Test
    void aCustomerCreatedThroughTheApiIsLoggedByItsId() {
        ResponseEntity<CustomerDTO> response = post(
                "/customer",
                Map.of("name", "Creation Log " + UUID.randomUUID()),
                "creation-customer",
                CustomerDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(creationLineOf("creation-customer").getFormattedMessage())
                .isEqualTo("Created customer " + response.getBody().id());
    }

    @Test
    void aProductCreatedThroughTheApiIsLoggedByItsId() {
        ResponseEntity<ProductDTO> response = post(
                "/products",
                Map.of("description", "Creation log " + UUID.randomUUID()),
                "creation-product",
                ProductDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(creationLineOf("creation-product").getFormattedMessage())
                .isEqualTo("Created product " + response.getBody().id());
    }

    @Test
    void creationsWhoseCommitFailsLogNothing() {
        String tooLongForTheColumn = "x".repeat(256);

        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(tooLongForTheColumn, 2L, List.of(3L))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> customerService.create(new CreateCustomerRequest(tooLongForTheColumn)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> productService.create(new CreateProductRequest(tooLongForTheColumn)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(creationLines()).isEmpty();
    }

    private <T> ResponseEntity<T> post(String path, Object body, String requestId, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", requestId);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), type);
    }

    private ILoggingEvent creationLineOf(String requestId) {
        List<ILoggingEvent> lines = creationLines().stream()
                .filter(event -> requestId.equals(event.getMDCPropertyMap().get("requestId")))
                .toList();
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }

    private List<ILoggingEvent> creationLines() {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("Created "))
                    .toList();
        }
    }
}
