package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static com.example.store.entity.EntityIds.withId;
import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    @Test
    void constructorSetsTheDescriptionAndStartsWithoutIdOrOrders() {
        Product product = new Product("Rustic Chair");

        assertThat(product.getDescription()).isEqualTo("Rustic Chair");
        assertThat(product.getId()).isNull();
        assertThat(product.getOrders()).isEmpty();
    }

    @Test
    void toStringDoesNotTraverseOrders() {
        Product product = withId(new Product("Rustic Chair"), 7L);
        Order order = withId(new Order("Analytical Engine", new Customer("Ada Lovelace")), 3L);
        order.getProducts().add(product);
        product.getOrders().add(order);

        assertThat(product.toString()).contains("7").doesNotContain("Order");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Product first = withId(new Product("Chair"), 1L);
        Product second = withId(new Product("Table"), 1L);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void transientInstancesAreNeverEqual() {
        assertThat(new Product("Chair")).isNotEqualTo(new Product("Chair"));
    }

    @Test
    void hashCodeIsStableAcrossIdAssignment() {
        Product product = new Product("Chair");
        int before = product.hashCode();
        withId(product, 42L);

        assertThat(product.hashCode()).isEqualTo(before);
    }
}
