package com.example.store.exception;

import java.io.IOException;

// An IOException so that Spring reports it as an unreadable message instead of a Jackson parse error.
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long maxBodyBytes) {
        super("Request body exceeds " + maxBodyBytes + " bytes");
    }
}
