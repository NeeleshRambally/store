package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.OrderMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    private OrderServiceImpl service;

    private Customer customer;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orderRepository, customerRepository, Mappers.getMapper(OrderMapper.class));
        customer = new Customer();
        customer.setId(1L);
        customer.setName("Ada Lovelace");
    }

    @Test
    void createLinksOrderToExistingCustomer() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            assertThat(saved.getId()).isNull();
            saved.setId(10001L);
            return saved;
        });

        OrderDTO result = service.create(new CreateOrderRequest("Analytical Engine", 1L));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getCustomer()).isSameAs(customer);
        assertThat(result.id()).isEqualTo(10001L);
        assertThat(result.description()).isEqualTo("Analytical Engine");
        assertThat(result.customer().id()).isEqualTo(1L);
        assertThat(result.customer().name()).isEqualTo("Ada Lovelace");
    }

    @Test
    void createRejectsUnknownCustomer() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateOrderRequest("Analytical Engine", 99L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer")
                .hasMessageContaining("99");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void findAllMapsOrdersWithTheirCustomer() {
        Order order = new Order();
        order.setId(5L);
        order.setDescription("Analytical Engine");
        order.setCustomer(customer);
        when(orderRepository.findAll()).thenReturn(List.of(order));

        List<OrderDTO> result = service.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(5L);
        assertThat(result.get(0).customer().name()).isEqualTo("Ada Lovelace");
    }
}
