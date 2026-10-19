package com.example.store.entity;

import org.junit.jupiter.api.Test;

import static com.example.store.entity.EntityIds.withId;
import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void constructorSetsTheDescriptionAndCustomerAndStartsWithoutIdOrProducts() {
        Customer customer = new Customer("Ada Lovelace");

        Order order = new Order("Analytical Engine", customer);

        assertThat(order.getDescription()).isEqualTo("Analytical Engine");
        assertThat(order.getCustomer()).isSameAs(customer);
        assertThat(order.getId()).isNull();
        assertThat(order.getProducts()).isEmpty();
    }

    @Test
    void toStringDoesNotTraverseCustomer() {
        Customer customer = withId(new Customer("Ada Lovelace"), 7L);
        Order order = withId(new Order("Analytical Engine", customer), 3L);
        customer.getOrders().add(order);

        assertThat(order.toString()).contains("3").doesNotContain("Customer");
    }

    @Test
    void equalityIsBasedOnIdOnly() {
        Customer customer = new Customer("Ada Lovelace");
        Order first = withId(new Order("Widget", customer), 1L);
        Order second = withId(new Order("Gadget", customer), 1L);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void transientInstancesAreNeverEqual() {
        Customer customer = new Customer("Ada Lovelace");
        Order first = new Order("Widget", customer);
        Order second = new Order("Widget", customer);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashCodeIsStableAcrossIdAssignment() {
        Order order = new Order("Widget", new Customer("Ada Lovelace"));
        int before = order.hashCode();
        withId(order, 42L);

        assertThat(order.hashCode()).isEqualTo(before);
    }
}
