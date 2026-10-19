package com.example.store.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateOrderRequestTest {

    @Test
    void keepsItsProductIdsWhenTheListItWasBuiltFromChanges() {
        List<Long> productIds = new ArrayList<>(List.of(2L));
        CreateOrderRequest request = new CreateOrderRequest("Test Order", 1L, productIds);

        productIds.add(8L);

        assertThat(request.productIds()).containsExactly(2L);
    }

    @Test
    void rejectsChangesToItsProductIds() {
        CreateOrderRequest request = new CreateOrderRequest("Test Order", 1L, new ArrayList<>(List.of(2L)));

        assertThatThrownBy(() -> request.productIds().add(8L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsAMissingProductListForValidation() {
        assertThat(new CreateOrderRequest("Test Order", 1L, null).productIds()).isNull();
    }

    @Test
    void keepsANullProductIdForValidation() {
        assertThat(new CreateOrderRequest("Test Order", 1L, Arrays.asList(2L, null)).productIds())
                .containsExactly(2L, null);
    }
}
