package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static com.example.store.entity.EntityIds.withId;
import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    @Test
    void toStringDoesNotTraverseOrders() {
        Product product = withId(new Product(), 7L);
        Order order = withId(new Order(), 3L);
        order.getProducts().add(product);
        product.getOrders().add(order);

        assertThat(product.toString()).contains("7").doesNotContain("Order");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Product first = withId(new Product(), 1L);
        first.setDescription("Chair");
        Product second = withId(new Product(), 1L);
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
        withId(product, 42L);

        assertThat(product.hashCode()).isEqualTo(before);
    }
}
