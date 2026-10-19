package com.example.store.service;

import java.util.List;

public record OrderCreatedEvent(Long orderId, Long customerId, List<Long> productIds) {}
