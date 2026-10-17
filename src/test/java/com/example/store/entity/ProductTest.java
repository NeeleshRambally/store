package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    @Test
    void toStringDoesNotTraverseOrders() {
        Product product = new Product();
        product.setId(7L);
        Order order = new Order();
        order.setId(3L);
        order.getProducts().add(product);
        product.getOrders().add(order);

        assertThat(product.toString()).contains("7").doesNotContain("Order");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Product first = new Product();
        first.setId(1L);
        first.setDescription("Chair");
        Product second = new Product();
        second.setId(1L);
        second.setDescription("Table");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void transientInstancesAreNeverEqual() {
        assertThat(new Product()).isNotEqualTo(new Product());
    }

    @Test
    void hashCodeIsStableAcrossIdAssignment() {
        Product product = new Product();
        int before = product.hashCode();
        product.setId(42L);

        assertThat(product.hashCode()).isEqualTo(before);
    }
}
