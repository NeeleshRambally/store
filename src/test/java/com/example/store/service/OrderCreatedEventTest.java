package com.example.store.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCreatedEventTest {

    @Test
    void keepsItsProductIdsWhenTheListItWasBuiltFromChanges() {
        List<Long> productIds = new ArrayList<>(List.of(2L, 8L));
        OrderCreatedEvent event = new OrderCreatedEvent(10001L, 1L, productIds);

        productIds.add(9L);

        assertThat(event.productIds()).containsExactly(2L, 8L);
    }

    @Test
    void rejectsChangesToItsProductIds() {
        OrderCreatedEvent event = new OrderCreatedEvent(10001L, 1L, new ArrayList<>(List.of(2L, 8L)));

        assertThatThrownBy(() -> event.productIds().add(9L)).isInstanceOf(UnsupportedOperationException.class);
    }
}
