package com.example.store.exception;

public class IdempotencyRequestInProgressException extends RuntimeException {

    public IdempotencyRequestInProgressException() {
        super("A request with this Idempotency-Key is still in progress");
    }
}
