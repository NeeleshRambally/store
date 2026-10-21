package com.example.store.dto;

import com.example.store.exception.InvalidIdempotencyKeyException;

import java.util.regex.Pattern;

public record IdempotencyKey(String value) {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern ACCEPTED = Pattern.compile("[\\x21-\\x7E]{1,255}");

    public IdempotencyKey {
        if (value == null || !ACCEPTED.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    public static IdempotencyKey fromHeader(String header) {
        return header == null ? null : new IdempotencyKey(header);
    }
}
