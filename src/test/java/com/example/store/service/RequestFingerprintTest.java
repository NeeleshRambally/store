package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CreateOrderRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestFingerprintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void isTheSha256OfThePathAndTheCanonicalJsonOfTheRequest() throws Exception {
        String expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                        .digest("/customer\n{\"name\":\"Ada Lovelace\"}".getBytes(StandardCharsets.UTF_8)));

        assertThat(RequestFingerprint.of(objectMapper, "/customer", new CreateCustomerRequest("Ada Lovelace")))
                .hasSize(64)
                .isEqualTo(expected);
    }

    @Test
    void ignoresTheWhitespaceAndPropertyOrderOfTheBodyAsSent() throws Exception {
        CreateOrderRequest compact = objectMapper.readValue(
                "{\"description\":\"Engine\",\"customerId\":1,\"productIds\":[2,8]}", CreateOrderRequest.class);
        CreateOrderRequest spaced = objectMapper.readValue(
                "{ \"productIds\" : [ 2, 8 ],\n  \"customerId\" : 1,\n  \"description\" : \"Engine\" }",
                CreateOrderRequest.class);

        assertThat(RequestFingerprint.of(objectMapper, "/order", spaced))
                .isEqualTo(RequestFingerprint.of(objectMapper, "/order", compact));
    }

    @Test
    void changesWithTheBody() {
        CreateOrderRequest first = new CreateOrderRequest("Engine", 1L, List.of(2L, 8L));
        CreateOrderRequest reordered = new CreateOrderRequest("Engine", 1L, List.of(8L, 2L));
        CreateOrderRequest renamed = new CreateOrderRequest("Engines", 1L, List.of(2L, 8L));

        assertThat(RequestFingerprint.of(objectMapper, "/order", first))
                .isNotEqualTo(RequestFingerprint.of(objectMapper, "/order", reordered))
                .isNotEqualTo(RequestFingerprint.of(objectMapper, "/order", renamed));
    }

    @Test
    void changesWithThePath() {
        CreateCustomerRequest request = new CreateCustomerRequest("Ada Lovelace");

        assertThat(RequestFingerprint.of(objectMapper, "/customer", request))
                .isNotEqualTo(RequestFingerprint.of(objectMapper, "/products", request));
    }

    @Test
    void failsWhenTheRequestCannotBeWrittenAsJson() {
        assertThatThrownBy(() -> RequestFingerprint.of(objectMapper, "/customer", new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }
}
