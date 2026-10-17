package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void toStringDoesNotTraverseCustomer() {
        Customer customer = new Customer();
        customer.setId(7L);
        Order order = new Order();
        order.setId(3L);
        order.setCustomer(customer);
        customer.getOrders().add(order);

        assertThat(order.toString()).contains("3").doesNotContain("Customer");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Order first = new Order();
        first.setId(1L);
        first.setDescription("Widget");
        Order second = new Order();
        second.setId(1L);
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
        order.setId(42L);

        assertThat(order.hashCode()).isEqualTo(before);
    }
}
