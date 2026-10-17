package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderService {
    OrderDTO create(CreateOrderRequest request);

    OrderDTO findById(Long id);

    Page<OrderDTO> findAll(Pageable pageable);
}
