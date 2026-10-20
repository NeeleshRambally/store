package com.example.store.integration;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyLimitIT extends AbstractIntegrationTest {

    private static final int LIMIT = 65536;
    private static final String FORM = "application/x-www-form-urlencoded";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ServletWebServerApplicationContext context;

    @Test
    void aDeclaredLengthOverTheBoundIsAPayloadTooLargeProblemDetail() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/order", HttpMethod.POST, new HttpEntity<>(orderJsonOfLength(LIMIT + 1), jsonHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(response.getBody()).contains("\"detail\":\"Request body exceeds 65536 bytes\"");
    }

    @Test
    void aChunkedBodyOverTheBoundIsRejectedWhileItIsRead() {
        byte[] bytes = orderJsonOfLength(LIMIT + 1).getBytes(StandardCharsets.UTF_8);
        HttpEntity<InputStreamResource> chunked =
                new HttpEntity<>(new InputStreamResource(new ByteArrayInputStream(bytes)), jsonHeaders());

        ResponseEntity<String> response = restTemplate.exchange("/order", HttpMethod.POST, chunked, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).contains("\"detail\":\"Request body exceeds 65536 bytes\"");
    }

    @Test
    void aNormalChunkedOrderIsStillCreated() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/order",
                Map.of("description", "Bounded order", "customerId", 1, "productIds", List.of(1)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/order", "/customer", "/products"})
    void aFormEncodedBodyIsUnsupportedWithoutWaitingForTheRestOfIt(String path) throws IOException {
        assertThat(answerBeforeTheRestIsSent("POST", path, FORM, 1_900_000, "description=" + "a".repeat(8192), 415))
                .contains("\r\nAccept: application/json\r\n", "\"code\":\"unsupported_media_type\"");
    }

    @ParameterizedTest
    @CsvSource({"/order, multipart/form-data", "/customer, multipart/mixed", "/products, multipart/form-data"})
    void aMultipartBodyIsUnsupportedWithoutWaitingForTheRestOfIt(String path, String type) throws IOException {
        assertThat(answerBeforeTheRestIsSent(
                        "POST",
                        path,
                        type + "; boundary=order-part",
                        9_000_000,
                        "--order-part\r\nContent-Disposition: form-data; name=\"file\"; filename=\"order.bin\"\r\n\r\n"
                                + "a".repeat(8192),
                        415))
                .contains("\r\nAccept: application/json\r\n", "\"code\":\"unsupported_media_type\"");
    }

    @ParameterizedTest
    @CsvSource({
        "PUT, /customer, 1900000, 405, method_not_allowed",
        "PATCH, /order/1, 1900000, 405, method_not_allowed",
        "DELETE, /order/1, 1900000, 405, method_not_allowed",
        "PUT, /no-such-path, 1900000, 404, not_found",
        "PUT, /customer, 60000, 405, method_not_allowed",
        "PATCH, /order/1, 60000, 405, method_not_allowed",
        "DELETE, /order/1, 60000, 405, method_not_allowed",
        "PUT, /no-such-path, 60000, 404, not_found"
    })
    void aFormBodySentWithAMethodNoEndpointTakesIsAnsweredWithoutWaitingForTheRestOfIt(
            String method, String path, int declaredLength, int status, String code) throws IOException {
        assertThat(answerBeforeTheRestIsSent(method, path, FORM, declaredLength, "name=" + "a".repeat(8192), status))
                .contains("\"code\":\"" + code + "\"");
    }

    @Test
    void aChunkedFormBodyOverTheBoundSentWithAMethodNoEndpointTakesIsAMethodNotAllowed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        byte[] bytes = ("name=" + "a".repeat(70_000)).getBytes(StandardCharsets.US_ASCII);

        ResponseEntity<String> response = restTemplate.exchange(
                "/customer",
                HttpMethod.PUT,
                new HttpEntity<>(new InputStreamResource(new ByteArrayInputStream(bytes)), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("\"code\":\"method_not_allowed\"");
    }

    @Test
    void tomcatRefusesToParseAFormBodyOverTheBoundOnBothConnectors() {
        assertThat(List.of(connector(context), connector(managementContext())))
                .extracting(Connector::getMaxPostSize)
                .containsExactly(LIMIT, LIMIT);
    }

    private WebServerApplicationContext managementContext() {
        return ReflectionTestUtils.invokeMethod(
                context.getBean("childManagementContextInitializer"), "getManagementContext");
    }

    private static Connector connector(WebServerApplicationContext application) {
        return ((TomcatWebServer) application.getWebServer()).getTomcat().getConnector();
    }

    private String answerBeforeTheRestIsSent(
            String method, String path, String contentType, int declaredLength, String firstBytes, int status)
            throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream()
                    .write((method + " " + path + " HTTP/1.1\r\n"
                                    + "Host: localhost\r\n"
                                    + "Content-Type: " + contentType + "\r\n"
                                    + "Content-Length: " + declaredLength + "\r\n\r\n"
                                    + firstBytes)
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream response = socket.getInputStream();

            String head = readUntil(response, "\r\n\r\n");
            assertThat(head)
                    .startsWith("HTTP/1.1 " + status + " ")
                    .contains("\r\nContent-Type: application/problem+json\r\n");
            return head + body(head, response);
        }
    }

    private static String body(String head, InputStream response) throws IOException {
        if (!head.contains("\r\nTransfer-Encoding: chunked\r\n")) {
            int length = Integer.parseInt(head.replaceAll("(?s).*\r\nContent-Length: (\\d+)\r\n.*", "$1"));
            return new String(response.readNBytes(length), StandardCharsets.UTF_8);
        }
        StringBuilder body = new StringBuilder();
        int size;
        while ((size = Integer.parseInt(readUntil(response, "\r\n").trim(), 16)) > 0) {
            body.append(new String(response.readNBytes(size + 2), StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    private static String readUntil(InputStream response, String end) throws IOException {
        StringBuilder read = new StringBuilder();
        while (!read.toString().endsWith(end)) {
            int next = response.read();
            if (next < 0) {
                throw new EOFException("Connection closed after " + read);
            }
            read.append((char) next);
        }
        return read.toString();
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String orderJsonOfLength(int length) {
        String prefix = "{\"description\":\"";
        String suffix = "\",\"customerId\":1,\"productIds\":[1]}";
        return prefix + "a".repeat(length - prefix.length() - suffix.length()) + suffix;
    }
}
