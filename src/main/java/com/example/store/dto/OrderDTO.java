package com.example.store.dto;

public record OrderDTO(Long id, String description, OrderCustomerDTO customer) {}
