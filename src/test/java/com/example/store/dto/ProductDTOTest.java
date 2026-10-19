package com.example.store.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductDTOTest {

    @Test
    void keepsItsOrderIdsWhenTheListItWasBuiltFromChanges() {
        List<Long> orders = new ArrayList<>(List.of(1L, 43L));
        ProductDTO product = new ProductDTO(2L, "Rustic Chair", orders, 2);

        orders.add(99L);

        assertThat(product.orders()).containsExactly(1L, 43L);
    }

    @Test
    void rejectsChangesToItsOrderIds() {
        ProductDTO product = new ProductDTO(2L, "Rustic Chair", new ArrayList<>(List.of(1L, 43L)), 2);

        assertThatThrownBy(() -> product.orders().add(99L)).isInstanceOf(UnsupportedOperationException.class);
    }
}
