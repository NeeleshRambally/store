package com.example.store.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// SHA-256 of the request path and the JSON the application's ObjectMapper writes for the bound request record, so a
// repeat sent with other whitespace or another property order has the same fingerprint.
public final class RequestFingerprint {

    private RequestFingerprint() {}

    public static String of(ObjectWriter writer, String path, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(writer.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("The request could not be fingerprinted", e);
        }
    }
}
