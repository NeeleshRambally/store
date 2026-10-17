package com.example.store.service;

import com.example.store.dto.CreateCustomerRequest;
import com.example.store.dto.CustomerDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.mapper.CustomerMapper;
import com.example.store.repository.CustomerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;

    private CustomerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerServiceImpl(customerRepository, Mappers.getMapper(CustomerMapper.class));
    }

    @Test
    void createPersistsNameAndReturnsAssignedId() {
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> {
            Customer saved = invocation.getArgument(0);
            assertThat(saved.getId()).isNull();
            saved.setId(101L);
            return saved;
        });

        CustomerDTO result = service.create(new CreateCustomerRequest("Ada Lovelace"));

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Ada Lovelace");
        assertThat(result.id()).isEqualTo(101L);
        assertThat(result.name()).isEqualTo("Ada Lovelace");
        assertThat(result.orders()).isEmpty();
    }

    @Test
    void findAllPagesCustomersAndFetchesTheirOrdersInOneQuery() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setName("Ada Lovelace");
        Order order = new Order();
        order.setId(5L);
        order.setDescription("Analytical Engine");
        order.setCustomer(customer);
        customer.getOrders().add(order);
        Pageable pageable = PageRequest.of(0, 20);
        when(customerRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(customer), pageable, 100));
        when(customerRepository.findAllWithOrdersByIdIn(List.of(1L))).thenReturn(List.of(customer));

        Page<CustomerDTO> result = service.findAll(pageable);

        verify(customerRepository).findAllWithOrdersByIdIn(List.of(1L));
        assertThat(result.getTotalElements()).isEqualTo(100);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(1L);
        assertThat(result.getContent().get(0).orders()).hasSize(1);
        assertThat(result.getContent().get(0).orders().get(0).description()).isEqualTo("Analytical Engine");
    }
}
