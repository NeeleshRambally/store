package com.example.store.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be 1 to 255 visible ASCII characters");
    }
}
