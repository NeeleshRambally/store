package com.example.store.entity;

import org.springframework.test.util.ReflectionTestUtils;

// Assigns the id Hibernate would generate on persist, for tests that work with detached entities.
public final class EntityIds {

    private EntityIds() {}

    public static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
