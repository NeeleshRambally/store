package com.example.store.exception;

public class InvalidOrderFilterException extends RuntimeException {

    public InvalidOrderFilterException() {
        super("customerId and productId cannot be combined");
    }
}
