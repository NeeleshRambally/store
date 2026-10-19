package com.example.store.dto;

import java.util.List;

public record CustomerDTO(Long id, String name, List<CustomerOrderDTO> orders, long orderCount) {

    public CustomerDTO {
        orders = List.copyOf(orders);
    }
}
