package com.example.store.dto;

import java.util.List;

public record ProductDTO(Long id, String description, List<Long> orders, long orderCount) {}
