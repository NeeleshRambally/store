package com.example.store.dto;

import java.util.List;

public record OrderDTO(Long id, String description, OrderCustomerDTO customer, List<OrderProductDTO> products) {}
