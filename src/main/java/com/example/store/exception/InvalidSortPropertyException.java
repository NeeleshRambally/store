package com.example.store.exception;

public class InvalidSortPropertyException extends RuntimeException {

    public InvalidSortPropertyException(String property) {
        super("Unknown sort property: " + property);
    }
}
