package com.example.store.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AccessLogIT extends AbstractIntegrationTest {

    private final Logger access = (Logger) LoggerFactory.getLogger("com.example.store.access");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalManagementPort
    private int managementPort;

    @BeforeEach
    void attachAppender() {
        appender.start();
        access.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        access.detachAppender(appender);
    }

    @Test
    void aReadIsLoggedOnceAtInfoWithItsRequestIdRouteStatusAndDuration() {
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/order/1", withRequestId("access-read"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ILoggingEvent line = lineOf("access-read");
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        Map<String, Object> members = members(line);
        assertThat(members).containsOnlyKeys("route", "status", "durationMs");
        assertThat(members).containsEntry("route", "/order/{id}").containsEntry("status", 200);
        assertThat(line.getFormattedMessage()).isEqualTo("GET /order/{id} 200 " + members.get("durationMs") + " ms");
    }

    @Test
    void aListRequestIsLoggedByItsRouteWithoutTheQueryString() {
        ResponseEntity<String> response =
                exchange(HttpMethod.GET, "/order?customerId=2&page=0&size=1", withRequestId("access-list"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lineOf("access-list").getFormattedMessage()).matches("GET /order 200 \\d+ ms");
    }

    @Test
    void aRevalidatedReadIsLoggedWithTheNotModifiedStatus() {
        String etag =
                restTemplate.getForEntity("/order/2", String.class).getHeaders().getETag();
        HttpHeaders headers = withRequestId("access-304");
        headers.setIfNoneMatch(etag);

        ResponseEntity<String> response = exchange(HttpMethod.GET, "/order/2", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(lineOf("access-304").getFormattedMessage()).matches("GET /order/\\{id} 304 \\d+ ms");
    }

    @Test
    void aMethodNoHandlerAcceptsIsLoggedWithTheUnknownRoute() {
        ResponseEntity<String> response = exchange(HttpMethod.DELETE, "/order/1", withRequestId("access-405"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        ILoggingEvent line = lineOf("access-405");
        assertThat(line.getFormattedMessage()).matches("DELETE UNKNOWN 405 \\d+ ms");
        assertThat(members(line)).containsEntry("route", "UNKNOWN");
    }

    @Test
    void aBodyTypeNoHandlerConsumesIsLoggedWithTheUnknownRoute() {
        HttpHeaders headers = withRequestId("access-415");
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> response =
                restTemplate.exchange("/order", HttpMethod.POST, new HttpEntity<>("an order", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        ILoggingEvent line = lineOf("access-415");
        assertThat(line.getFormattedMessage()).matches("POST UNKNOWN 415 \\d+ ms");
        assertThat(members(line)).containsEntry("route", "UNKNOWN");
    }

    @Test
    void aResponseTypeNoHandlerProducesIsLoggedWithTheUnknownRoute() {
        HttpHeaders headers = withRequestId("access-406");
        headers.setAccept(List.of(MediaType.APPLICATION_XML));

        ResponseEntity<String> response = exchange(HttpMethod.GET, "/order/1", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(lineOf("access-406").getFormattedMessage()).matches("GET UNKNOWN 406 \\d+ ms");
    }

    @Test
    void anUnknownPathIsLoggedWithTheStaticResourcePatternNotThePath() {
        ResponseEntity<String> response = exchange(HttpMethod.GET, "/no-such-path", withRequestId("access-404"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(lineOf("access-404").getFormattedMessage()).matches("GET /\\*\\* 404 \\d+ ms");
    }

    @Test
    void theManagementPortIsNotLogged() {
        ResponseEntity<String> health =
                restTemplate.getForEntity("http://localhost:" + managementPort + "/actuator/health", String.class);
        exchange(HttpMethod.GET, "/order/1", withRequestId("access-after-management"));

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        lineOf("access-after-management");
        synchronized (appender) {
            assertThat(appender.list)
                    .extracting(event -> event.getMDCPropertyMap().get("requestId"))
                    .containsExactly("access-after-management");
        }
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, HttpHeaders headers) {
        return restTemplate.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    private static HttpHeaders withRequestId(String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", requestId);
        return headers;
    }

    private ILoggingEvent lineOf(String requestId) {
        await().pollDelay(Duration.ZERO)
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(linesOf(requestId)).hasSize(1));
        return linesOf(requestId).get(0);
    }

    private static Map<String, Object> members(ILoggingEvent line) {
        assertThat(line.getKeyValuePairs()).isNotNull();
        return line.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private List<ILoggingEvent> linesOf(String requestId) {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(event -> requestId.equals(event.getMDCPropertyMap().get("requestId")))
                    .toList();
        }
    }
}
