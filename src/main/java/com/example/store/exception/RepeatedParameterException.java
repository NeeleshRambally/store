package com.example.store.exception;

import lombok.Getter;

@Getter
public class RepeatedParameterException extends RuntimeException {

    private final String parameter;

    public RepeatedParameterException(String parameter) {
        super("'" + parameter + "' must not be repeated");
        this.parameter = parameter;
    }
}
