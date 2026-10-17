package com.example.store.service;

import com.example.store.dto.CreateOrderRequest;
import com.example.store.dto.OrderDTO;

import java.util.List;

public interface OrderService {
    OrderDTO create(CreateOrderRequest request);

    List<OrderDTO> findAll();
}
