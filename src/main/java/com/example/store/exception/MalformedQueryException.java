package com.example.store.exception;

public class MalformedQueryException extends RuntimeException {

    public MalformedQueryException() {
        super("The query string could not be parsed");
    }
}
