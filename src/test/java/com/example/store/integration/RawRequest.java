package com.example.store.integration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// Sends a GET with its request target exactly as written; java.net.URI refuses a % that starts no escape.
public final class RawRequest {

    private RawRequest() {}

    public static ResponseEntity<String> get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty(HttpHeaders.ACCEPT, "*/*");
        try {
            int status = connection.getResponseCode();
            HttpHeaders headers = new HttpHeaders();
            connection.getHeaderFields().forEach((name, values) -> {
                if (name != null) {
                    headers.addAll(name, values);
                }
            });
            try (InputStream body = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                return ResponseEntity.status(status)
                        .headers(headers)
                        .body(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
