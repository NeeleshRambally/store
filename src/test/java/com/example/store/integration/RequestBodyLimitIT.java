package com.example.store.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream()
                    .write(("POST " + path + " HTTP/1.1\r\n"
                                    + "Host: localhost\r\n"
                                    + "Content-Type: application/x-www-form-urlencoded\r\n"
                                    + "Content-Length: 1900000\r\n\r\n"
                                    + "description=" + "a".repeat(8192))
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream response = socket.getInputStream();

            String head = head(response);
            assertThat(head)
                    .startsWith("HTTP/1.1 415 ")
                    .contains("\r\nContent-Type: application/problem+json\r\n", "\r\nAccept: application/json\r\n");
            int length = Integer.parseInt(head.replaceAll("(?s).*\r\nContent-Length: (\\d+)\r\n.*", "$1"));
            assertThat(new String(response.readNBytes(length), StandardCharsets.UTF_8))
                    .contains("\"code\":\"unsupported_media_type\"");
        }
    }

    @Test
    void tomcatRefusesToParseAFormBodyOverTheBound() {
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();

        assertThat(server.getTomcat().getConnector().getMaxPostSize()).isEqualTo(LIMIT);
    }

    private static String head(InputStream response) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int next = response.read();
            if (next < 0) {
                throw new EOFException("Connection closed after " + head);
            }
            head.append((char) next);
        }
        return head.toString();
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
