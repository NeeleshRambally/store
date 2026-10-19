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
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.OrderMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;
import com.example.store.repository.ProductRepository;
import com.example.store.repository.view.OrderProductRow;
import com.example.store.repository.view.OrderRow;

import lombok.RequiredArgsConstructor;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private static final SortPolicy SORT_POLICY = SortPolicy.of("id", "description");

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final OrderMapper orderMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public OrderDTO create(CreateOrderRequest request) {
        Customer customer = customerRepository
                .findById(request.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", request.customerId()));
        Order order = new Order(request.description(), customer);
        List<Product> products = loadProducts(request.productIds());
        order.getProducts().addAll(products);
        OrderDTO created = orderMapper.orderToOrderDTO(orderRepository.save(order));
        eventPublisher.publishEvent(new OrderCreatedEvent(
                created.id(),
                customer.getId(),
                products.stream().map(Product::getId).toList()));
        return created;
    }

    @Override
    @Cacheable("orders")
    public OrderDTO findById(Long id) {
        return orderRepository
                .findById(id)
                .map(orderMapper::orderToOrderDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    @Override
    public Page<OrderDTO> findAll(OrderFilter filter, Pageable pageable) {
        validate(filter);
        Page<OrderRow> page = findRows(filter, SORT_POLICY.validate(pageable));
        Map<Long, List<OrderProductDTO>> products = productsByOrder(page.getContent());
        return page.map(row -> toOrder(row, products));
    }

    @Override
    public CursorPageResponse<OrderDTO> findAfter(OrderFilter filter, CursorRequest request) {
        validate(filter);
        List<OrderRow> rows = findRowsAfter(filter, request.after(), Limit.of(request.size() + 1));
        return CursorPageResponse.from(rows, request.size(), OrderRow::id, this::toOrders);
    }

    private static void validate(OrderFilter filter) {
        if (filter.customerId() != null && filter.productId() != null) {
            throw new InvalidOrderFilterException();
        }
    }

    private Page<OrderRow> findRows(OrderFilter filter, Pageable pageable) {
        if (filter.customerId() != null) {
            return orderRepository.findRowsByCustomer(filter.customerId(), pageable);
        }
        if (filter.productId() != null) {
            return orderRepository.findRowsByProduct(filter.productId(), pageable);
        }
        return orderRepository.findRows(pageable);
    }

    private List<OrderRow> findRowsAfter(OrderFilter filter, long after, Limit limit) {
        if (filter.customerId() != null) {
            return orderRepository.findRowsByCustomerAfter(filter.customerId(), after, limit);
        }
        if (filter.productId() != null) {
            return orderRepository.findRowsByProductAfter(filter.productId(), after, limit);
        }
        return orderRepository.findRowsAfter(after, limit);
    }

    private List<OrderDTO> toOrders(List<OrderRow> rows) {
        Map<Long, List<OrderProductDTO>> products = productsByOrder(rows);
        return rows.stream().map(row -> toOrder(row, products)).toList();
    }

    private static OrderDTO toOrder(OrderRow row, Map<Long, List<OrderProductDTO>> products) {
        return new OrderDTO(
                row.id(),
                row.description(),
                new OrderCustomerDTO(row.customerId(), row.customerName()),
                products.getOrDefault(row.id(), List.of()));
    }

    private Map<Long, List<OrderProductDTO>> productsByOrder(List<OrderRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<OrderProductDTO>> products = new LinkedHashMap<>();
        for (OrderProductRow row :
                orderRepository.findProductRows(rows.stream().map(OrderRow::id).toList())) {
            products.computeIfAbsent(row.orderId(), id -> new ArrayList<>())
                    .add(new OrderProductDTO(row.productId(), row.description()));
        }
        return products;
    }

    private List<Product> loadProducts(List<Long> productIds) {
        List<Product> products = productRepository.findAllById(productIds);
        if (products.size() != productIds.size()) {
            Set<Long> found =
                    new HashSet<>(products.stream().map(Product::getId).toList());
            Long missing = productIds.stream()
                    .filter(id -> !found.contains(id))
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            throw new ResourceNotFoundException("Product", missing);
        }
        return products.stream().sorted(Comparator.comparing(Product::getId)).toList();
    }
}
