package com.example.store.mapper;

import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;

import org.junit.jupiter.api.Test;

import static com.example.store.entity.EntityIds.withId;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerMapperTest {

    private final CustomerMapper mapper = new CustomerMapperImpl();

    @Test
    void orderCountFollowsTheOrdersCollection() {
        Customer customer = withId(new Customer("Vicki Kutch"), 3L);
        customer.getOrders().add(withId(new Order("Handcrafted Soft Chair", customer), 7L));
        customer.getOrders().add(withId(new Order("Rustic Lamp", customer), 5L));

        CustomerDTO dto = mapper.customerToCustomerDTO(customer);

        assertThat(dto.orders()).extracting("id").containsExactly(7L, 5L);
        assertThat(dto.orderCount()).isEqualTo(2L);
    }

    @Test
    void aNewCustomerHasNoOrders() {
        Customer customer = withId(new Customer("Ada"), 101L);

        CustomerDTO dto = mapper.customerToCustomerDTO(customer);

        assertThat(dto.orders()).isEmpty();
        assertThat(dto.orderCount()).isZero();
    }
}
