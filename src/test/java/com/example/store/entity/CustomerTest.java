package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static com.example.store.entity.EntityIds.withId;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerTest {

    @Test
    void toStringDoesNotTraverseOrders() {
        Customer customer = withId(new Customer(), 7L);
        Order order = withId(new Order(), 3L);
        order.setCustomer(customer);
        customer.getOrders().add(order);

        assertThat(customer.toString()).contains("7").doesNotContain("Order");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Customer first = withId(new Customer(), 1L);
        first.setName("Ada");
        Customer second = withId(new Customer(), 1L);
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
        withId(customer, 42L);

        assertThat(customer.hashCode()).isEqualTo(before);
    }
}
