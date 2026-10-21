package com.example.store.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnonymousClientIdentityTest {

    @Test
    void everyCallerIsTheAnonymousClient() {
        ClientIdentity identity = new AnonymousClientIdentity();

        assertThat(identity.id()).isEqualTo("anonymous").isEqualTo(AnonymousClientIdentity.ID);
    }
}
