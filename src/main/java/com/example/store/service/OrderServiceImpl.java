package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderDTO;
import com.example.store.entity.Customer;
import com.example.store.entity.Order;
import com.example.store.entity.Product;
import com.example.store.exception.ResourceNotFoundException;
import com.example.store.mapper.OrderMapper;
import com.example.store.repository.CustomerRepository;
import com.example.store.repository.OrderRepository;
import com.example.store.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final OrderMapper orderMapper;

    @Override
    @Transactional
    public OrderDTO create(CreateOrderRequest request) {
        Customer customer = customerRepository
                .findById(request.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", request.customerId()));
        Order order = orderMapper.toEntity(request);
        order.setCustomer(customer);
        order.getProducts().addAll(loadProducts(request.productIds()));
        return orderMapper.orderToOrderDTO(orderRepository.save(order));
    }

    @Override
    public OrderDTO findById(Long id) {
        return orderRepository
                .findById(id)
                .map(orderMapper::orderToOrderDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    @Override
    public Page<OrderDTO> findAll(Pageable pageable) {
        Page<Order> page = orderRepository.findAll(pageable);
        if (page.hasContent()) {
            orderRepository.findAllWithProductsByIdIn(
                    page.getContent().stream().map(Order::getId).toList());
        }
        return page.map(orderMapper::orderToOrderDTO);
    }

    private List<Product> loadProducts(List<Long> productIds) {
        Set<Long> requested = new HashSet<>(productIds);
        List<Product> products = productRepository.findAllById(productIds);
        if (products.size() != requested.size()) {
            Set<Long> found =
                    new HashSet<>(products.stream().map(Product::getId).toList());
            Long missing = requested.stream()
                    .filter(id -> !found.contains(id))
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            throw new ResourceNotFoundException("Product", missing);
        }
        return products.stream().sorted(Comparator.comparing(Product::getId)).toList();
    }
}
