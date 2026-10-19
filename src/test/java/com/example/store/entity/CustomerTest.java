package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static com.example.store.entity.EntityIds.withId;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerTest {

    @Test
    void constructorSetsTheNameAndStartsWithoutIdOrOrders() {
        Customer customer = new Customer("Ada Lovelace");

        assertThat(customer.getName()).isEqualTo("Ada Lovelace");
        assertThat(customer.getId()).isNull();
        assertThat(customer.getOrders()).isEmpty();
    }

    @Test
    void toStringDoesNotTraverseOrders() {
        Customer customer = withId(new Customer("Ada Lovelace"), 7L);
        Order order = withId(new Order("Analytical Engine", customer), 3L);
        customer.getOrders().add(order);

        assertThat(customer.toString()).contains("7").doesNotContain("Order");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Customer first = withId(new Customer("Ada"), 1L);
        Customer second = withId(new Customer("Grace"), 1L);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void transientInstancesAreNeverEqual() {
        Customer first = new Customer("Ada");
        Customer second = new Customer("Ada");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashCodeIsStableAcrossIdAssignment() {
        Customer customer = new Customer("Ada");
        int before = customer.hashCode();
        withId(customer, 42L);

        assertThat(customer.hashCode()).isEqualTo(before);
    }
}
