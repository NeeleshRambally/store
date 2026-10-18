package com.example.store.exception;

public class InvalidCursorRequestException extends RuntimeException {

    public InvalidCursorRequestException() {
        super("after cannot be combined with page or sort");
    }
}
