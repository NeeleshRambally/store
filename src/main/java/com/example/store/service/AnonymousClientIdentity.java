package com.example.store.service;

import org.springframework.stereotype.Component;

// Every caller is one client until authentication supplies the token subject, so clients must use random keys.
@Component
public class AnonymousClientIdentity implements ClientIdentity {

    public static final String ID = "anonymous";

    @Override
    public String id() {
        return ID;
    }
}
