package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.CursorPageResponse;
import com.example.store.dto.CursorRequest;
import com.example.store.dto.OrderCustomerDTO;
import com.example.store.dto.OrderDTO;
import com.example.store.dto.OrderFilter;
import com.example.store.dto.OrderProductDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.entity.Product;
import com.example.store.exception.InvalidOrderFilterException;
import com.example.store.exception.InvalidSortPropertyException;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.OrderMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;
import com.example.store.repository.ProductRepository;
import com.example.store.repository.view.OrderProductRow;
import com.example.store.repository.view.OrderRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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

    private static final OrderFilter NO_FILTER = new OrderFilter(null, null);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private OrderServiceImpl service;

    private Customer customer;
    private Product chair;
    private Product table;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(
                orderRepository,
                customerRepository,
                productRepository,
                Mappers.getMapper(OrderMapper.class),
                eventPublisher);
        customer = new Customer();
        customer.setId(1L);
        customer.setName("Ada Lovelace");
        chair = new Product();
        chair.setId(2L);
        chair.setDescription("Rustic Chair");
        table = new Product();
        table.setId(8L);
        table.setDescription("Oak Table");
    }

    @Test
    void createLinksOrderToExistingCustomerAndProducts() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findAllById(List.of(8L, 2L))).thenReturn(List.of(chair, table));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            assertThat(saved.getId()).isNull();
            saved.setId(10001L);
            return saved;
        });

        OrderDTO result = service.create(new CreateOrderRequest("Analytical Engine", 1L, List.of(8L, 2L)));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getCustomer()).isSameAs(customer);
        assertThat(captor.getValue().getProducts()).containsExactlyInAnyOrder(chair, table);
        assertThat(result.id()).isEqualTo(10001L);
        assertThat(result.description()).isEqualTo("Analytical Engine");
        assertThat(result.customer().name()).isEqualTo("Ada Lovelace");
        assertThat(result.products()).extracting(p -> p.id()).containsExactly(2L, 8L);
        assertThat(result.products().get(0).description()).isEqualTo("Rustic Chair");
    }

    @Test
    void createPublishesTheProductIdsOfTheNewOrderAfterSaving() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findAllById(List.of(8L, 2L))).thenReturn(List.of(chair, table));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreateOrderRequest("Analytical Engine", 1L, List.of(8L, 2L)));

        verify(eventPublisher).publishEvent(new OrderCreatedEvent(List.of(2L, 8L)));
    }

    @Test
    void createRejectsUnknownProduct() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(productRepository.findAllById(List.of(2L, 99L))).thenReturn(List.of(chair));

        assertThatThrownBy(() -> service.create(new CreateOrderRequest("Analytical Engine", 1L, List.of(2L, 99L))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product with id 99 was not found");
        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void createRejectsUnknownCustomer() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateOrderRequest("Analytical Engine", 99L, List.of(2L))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer")
                .hasMessageContaining("99");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void findByIdReturnsOrderWithCustomer() {
        Order order = new Order();
        order.setId(5L);
        order.setDescription("Analytical Engine");
        order.setCustomer(customer);
        order.getProducts().add(table);
        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));

        OrderDTO result = service.findById(5L);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.description()).isEqualTo("Analytical Engine");
        assertThat(result.customer().name()).isEqualTo("Ada Lovelace");
        assertThat(result.products()).extracting(p -> p.description()).containsExactly("Oak Table");
    }

    @Test
    void findByIdRejectsUnknownOrder() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Order with id 99 was not found");
    }

    @Test
    void findAllBuildsOrdersFromRowsAndGroupsProductsByOrder() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "description"));
        Pageable tieBroken = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "description", "id"));
        when(orderRepository.findRows(tieBroken))
                .thenReturn(new PageImpl<>(
                        List.of(
                                new OrderRow(5L, "Analytical Engine", 1L, "Ada Lovelace"),
                                new OrderRow(6L, "Difference Engine", 1L, "Ada Lovelace")),
                        tieBroken,
                        10000));
        when(orderRepository.findProductRows(List.of(5L, 6L)))
                .thenReturn(List.of(
                        new OrderProductRow(5L, 2L, "Rustic Chair"),
                        new OrderProductRow(5L, 8L, "Oak Table"),
                        new OrderProductRow(6L, 8L, "Oak Table")));

        Page<OrderDTO> result = service.findAll(NO_FILTER, pageable);

        assertThat(result.getTotalElements()).isEqualTo(10000);
        assertThat(result.getContent()).extracting(OrderDTO::id).containsExactly(5L, 6L);
        OrderDTO first = result.getContent().get(0);
        assertThat(first.description()).isEqualTo("Analytical Engine");
        assertThat(first.customer()).isEqualTo(new OrderCustomerDTO(1L, "Ada Lovelace"));
        assertThat(first.products())
                .containsExactly(new OrderProductDTO(2L, "Rustic Chair"), new OrderProductDTO(8L, "Oak Table"));
        assertThat(result.getContent().get(1).products()).containsExactly(new OrderProductDTO(8L, "Oak Table"));
    }

    @Test
    void findAllLeavesProductsEmptyForOrdersWithoutProductRows() {
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findRows(pageable))
                .thenReturn(new PageImpl<>(List.of(new OrderRow(5L, "Analytical Engine", 1L, "Ada Lovelace"))));
        when(orderRepository.findProductRows(List.of(5L))).thenReturn(List.of());

        Page<OrderDTO> result = service.findAll(NO_FILTER, pageable);

        assertThat(result.getContent().get(0).products()).isEmpty();
    }

    @Test
    void findAllIssuesNoProductsQueryForAnEmptyPage() {
        Pageable pageable = PageRequest.of(50, 20);
        when(orderRepository.findRows(pageable)).thenReturn(Page.empty(pageable));

        Page<OrderDTO> result = service.findAll(NO_FILTER, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(orderRepository, never()).findProductRows(any());
    }

    @Test
    void findAfterReturnsNoCursorWhenFewerRowsThanSizeRemain() {
        when(orderRepository.findRowsAfter(10L, Limit.of(6)))
                .thenReturn(List.of(
                        new OrderRow(11L, "Analytical Engine", 1L, "Ada Lovelace"),
                        new OrderRow(12L, "Difference Engine", 1L, "Ada Lovelace")));
        when(orderRepository.findProductRows(List.of(11L, 12L)))
                .thenReturn(List.of(new OrderProductRow(11L, 2L, "Rustic Chair")));

        CursorPageResponse<OrderDTO> result = service.findAfter(NO_FILTER, new CursorRequest(10L, 5));

        assertThat(result.size()).isEqualTo(5);
        assertThat(result.nextCursor()).isNull();
        assertThat(result.content()).extracting(OrderDTO::id).containsExactly(11L, 12L);
        OrderDTO first = result.content().get(0);
        assertThat(first.customer()).isEqualTo(new OrderCustomerDTO(1L, "Ada Lovelace"));
        assertThat(first.products()).containsExactly(new OrderProductDTO(2L, "Rustic Chair"));
        assertThat(result.content().get(1).products()).isEmpty();
    }

    @Test
    void findAfterReturnsNoCursorWhenExactlySizeRowsRemain() {
        when(orderRepository.findRowsAfter(10L, Limit.of(3)))
                .thenReturn(List.of(
                        new OrderRow(11L, "Analytical Engine", 1L, "Ada Lovelace"),
                        new OrderRow(12L, "Difference Engine", 1L, "Ada Lovelace")));
        when(orderRepository.findProductRows(List.of(11L, 12L))).thenReturn(List.of());

        CursorPageResponse<OrderDTO> result = service.findAfter(NO_FILTER, new CursorRequest(10L, 2));

        assertThat(result.content()).extracting(OrderDTO::id).containsExactly(11L, 12L);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void findAfterTrimsTheExtraRowAndPointsTheCursorAtTheLastReturnedId() {
        when(orderRepository.findRowsAfter(10L, Limit.of(3)))
                .thenReturn(List.of(
                        new OrderRow(11L, "Analytical Engine", 1L, "Ada Lovelace"),
                        new OrderRow(12L, "Difference Engine", 1L, "Ada Lovelace"),
                        new OrderRow(13L, "Mill", 1L, "Ada Lovelace")));
        when(orderRepository.findProductRows(List.of(11L, 12L))).thenReturn(List.of());

        CursorPageResponse<OrderDTO> result = service.findAfter(NO_FILTER, new CursorRequest(10L, 2));

        assertThat(result.content()).extracting(OrderDTO::id).containsExactly(11L, 12L);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.nextCursor()).isEqualTo(12L);
        verify(orderRepository).findProductRows(List.of(11L, 12L));
    }

    @Test
    void findAfterIssuesNoProductsQueryPastTheEnd() {
        when(orderRepository.findRowsAfter(10000L, Limit.of(21))).thenReturn(List.of());

        CursorPageResponse<OrderDTO> result = service.findAfter(NO_FILTER, new CursorRequest(10000L, 20));

        assertThat(result.content()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        verify(orderRepository, never()).findProductRows(any());
    }

    @Test
    void findAllFiltersByCustomer() {
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findRowsByCustomer(1L, pageable))
                .thenReturn(new PageImpl<>(List.of(new OrderRow(5L, "Analytical Engine", 1L, "Ada Lovelace"))));
        when(orderRepository.findProductRows(List.of(5L)))
                .thenReturn(List.of(new OrderProductRow(5L, 2L, "Rustic Chair")));

        Page<OrderDTO> result = service.findAll(new OrderFilter(1L, null), pageable);

        assertThat(result.getContent()).extracting(OrderDTO::id).containsExactly(5L);
        assertThat(result.getContent().get(0).products()).containsExactly(new OrderProductDTO(2L, "Rustic Chair"));
        verify(orderRepository, never()).findRows(any());
    }

    @Test
    void findAllFiltersByProduct() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("description"));
        when(orderRepository.findRowsByProduct(2L, PageRequest.of(0, 20, Sort.by("description", "id"))))
                .thenReturn(new PageImpl<>(List.of(new OrderRow(5L, "Analytical Engine", 1L, "Ada Lovelace"))));
        when(orderRepository.findProductRows(List.of(5L))).thenReturn(List.of());

        Page<OrderDTO> result = service.findAll(new OrderFilter(null, 2L), pageable);

        assertThat(result.getContent()).extracting(OrderDTO::id).containsExactly(5L);
        verify(orderRepository, never()).findRows(any());
    }

    @Test
    void findAllRejectsBothFiltersBeforeAnyQuery() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.findAll(new OrderFilter(1L, 2L), pageable))
                .isInstanceOf(InvalidOrderFilterException.class)
                .hasMessage("customerId and productId cannot be combined");
        verify(orderRepository, never()).findRows(any());
        verify(orderRepository, never()).findRowsByCustomer(any(Long.class), any());
        verify(orderRepository, never()).findRowsByProduct(any(Long.class), any());
    }

    @Test
    void findAfterFiltersByCustomer() {
        when(orderRepository.findRowsByCustomerAfter(1L, 10L, Limit.of(3)))
                .thenReturn(List.of(
                        new OrderRow(11L, "Analytical Engine", 1L, "Ada Lovelace"),
                        new OrderRow(12L, "Difference Engine", 1L, "Ada Lovelace"),
                        new OrderRow(13L, "Mill", 1L, "Ada Lovelace")));
        when(orderRepository.findProductRows(List.of(11L, 12L))).thenReturn(List.of());

        CursorPageResponse<OrderDTO> result = service.findAfter(new OrderFilter(1L, null), new CursorRequest(10L, 2));

        assertThat(result.content()).extracting(OrderDTO::id).containsExactly(11L, 12L);
        assertThat(result.nextCursor()).isEqualTo(12L);
        verify(orderRepository, never()).findRowsAfter(any(Long.class), any());
    }

    @Test
    void findAfterFiltersByProduct() {
        when(orderRepository.findRowsByProductAfter(2L, 0L, Limit.of(21)))
                .thenReturn(List.of(new OrderRow(11L, "Analytical Engine", 1L, "Ada Lovelace")));
        when(orderRepository.findProductRows(List.of(11L)))
                .thenReturn(List.of(new OrderProductRow(11L, 2L, "Rustic Chair")));

        CursorPageResponse<OrderDTO> result = service.findAfter(new OrderFilter(null, 2L), new CursorRequest(0L, 20));

        assertThat(result.content()).extracting(OrderDTO::id).containsExactly(11L);
        assertThat(result.content().get(0).products()).containsExactly(new OrderProductDTO(2L, "Rustic Chair"));
        assertThat(result.nextCursor()).isNull();
        verify(orderRepository, never()).findRowsAfter(any(Long.class), any());
    }

    @Test
    void findAfterRejectsBothFilters() {
        CursorRequest request = new CursorRequest(0L, 20);

        assertThatThrownBy(() -> service.findAfter(new OrderFilter(1L, 2L), request))
                .isInstanceOf(InvalidOrderFilterException.class)
                .hasMessage("customerId and productId cannot be combined");
        verify(orderRepository, never()).findRowsAfter(any(Long.class), any());
    }

    @Test
    void findAllRejectsSortPropertiesOutsideTheWhitelist() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("id", "customer.name"));

        assertThatThrownBy(() -> service.findAll(NO_FILTER, pageable))
                .isInstanceOf(InvalidSortPropertyException.class)
                .hasMessage("Unknown sort property: customer.name");
        verify(orderRepository, never()).findRows(any());
    }
}
