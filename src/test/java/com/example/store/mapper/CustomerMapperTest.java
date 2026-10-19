package com.example.store.mapper;

import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerMapperTest {

    private final CustomerMapper mapper = new CustomerMapperImpl();

    @Test
    void orderCountFollowsTheOrdersCollection() {
        Customer customer = new Customer();
        customer.setId(3L);
        customer.setName("Vicki Kutch");
        customer.getOrders().add(order(7L, "Handcrafted Soft Chair"));
        customer.getOrders().add(order(5L, "Rustic Lamp"));

        CustomerDTO dto = mapper.customerToCustomerDTO(customer);

        assertThat(dto.orders()).extracting("id").containsExactly(7L, 5L);
        assertThat(dto.orderCount()).isEqualTo(2L);
    }

    @Test
    void aNewCustomerHasNoOrders() {
        Customer customer = new Customer();
        customer.setId(101L);
        customer.setName("Ada");

        CustomerDTO dto = mapper.customerToCustomerDTO(customer);

        assertThat(dto.orders()).isEmpty();
        assertThat(dto.orderCount()).isZero();
    }

    private static Order order(long id, String description) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        return order;
    }
}
