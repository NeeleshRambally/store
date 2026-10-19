package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static com.example.store.entity.EntityIds.withId;
import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void toStringDoesNotTraverseCustomer() {
        Customer customer = withId(new Customer(), 7L);
        Order order = withId(new Order(), 3L);
        order.setCustomer(customer);
        customer.getOrders().add(order);

        assertThat(order.toString()).contains("3").doesNotContain("Customer");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Order first = withId(new Order(), 1L);
        first.setDescription("Widget");
        Order second = withId(new Order(), 1L);
        second.setDescription("Gadget");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void transientInstancesAreNeverEqual() {
        Order first = new Order();
        Order second = new Order();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashCodeIsStableAcrossIdAssignment() {
        Order order = new Order();
        int before = order.hashCode();
        withId(order, 42L);

        assertThat(order.hashCode()).isEqualTo(before);
    }
}
