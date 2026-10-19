package com.example.store.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerDTOTest {

    private static final CustomerOrderDTO ENGINE = new CustomerOrderDTO(43L, "Engine");
    private static final CustomerOrderDTO LOOM = new CustomerOrderDTO(44L, "Loom");

    @Test
    void keepsItsOrdersWhenTheListItWasBuiltFromChanges() {
        List<CustomerOrderDTO> orders = new ArrayList<>(List.of(ENGINE));
        CustomerDTO customer = new CustomerDTO(1L, "John Doe", orders, 1);

        orders.add(LOOM);

        assertThat(customer.orders()).containsExactly(ENGINE);
    }

    @Test
    void rejectsChangesToItsOrders() {
        CustomerDTO customer = new CustomerDTO(1L, "John Doe", new ArrayList<>(List.of(ENGINE)), 1);

        assertThatThrownBy(() -> customer.orders().add(LOOM)).isInstanceOf(UnsupportedOperationException.class);
    }
}
