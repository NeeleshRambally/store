package com.example.store.dto;

import lombok.Data;

@Data
public class OrderDTO {
    private Long id;
    private String description;
    private OrderCustomerDTO customer;
}
