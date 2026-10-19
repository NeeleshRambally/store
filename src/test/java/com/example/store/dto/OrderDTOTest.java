package com.example.store.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDTOTest {

    private static final OrderCustomerDTO CUSTOMER = new OrderCustomerDTO(1L, "John Doe");
    private static final OrderProductDTO CHAIR = new OrderProductDTO(2L, "Rustic Chair");
    private static final OrderProductDTO LAMP = new OrderProductDTO(8L, "Sleek Lamp");

    @Test
    void keepsItsProductsWhenTheListItWasBuiltFromChanges() {
        List<OrderProductDTO> products = new ArrayList<>(List.of(CHAIR));
        OrderDTO order = new OrderDTO(1L, "Test Order", CUSTOMER, products);

        products.add(LAMP);

        assertThat(order.products()).containsExactly(CHAIR);
    }

    @Test
    void rejectsChangesToItsProducts() {
        OrderDTO order = new OrderDTO(1L, "Test Order", CUSTOMER, new ArrayList<>(List.of(CHAIR)));

        assertThatThrownBy(() -> order.products().add(LAMP)).isInstanceOf(UnsupportedOperationException.class);
    }
}
