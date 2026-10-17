package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerTest {

    @Test
    void toStringDoesNotTraverseOrders() {
        Customer customer = new Customer();
        customer.setId(7L);
        Order order = new Order();
        order.setId(3L);
        order.setCustomer(customer);
        customer.getOrders().add(order);

        assertThat(customer.toString()).contains("7").doesNotContain("Order");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Customer first = new Customer();
        first.setId(1L);
        first.setName("Ada");
        Customer second = new Customer();
        second.setId(1L);
        second.setName("Grace");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void transientInstancesAreNeverEqual() {
        Customer first = new Customer();
        first.setName("Ada");
        Customer second = new Customer();
        second.setName("Ada");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashCodeIsStableAcrossIdAssignment() {
        Customer customer = new Customer();
        int before = customer.hashCode();
        customer.setId(42L);

        assertThat(customer.hashCode()).isEqualTo(before);
    }
}
