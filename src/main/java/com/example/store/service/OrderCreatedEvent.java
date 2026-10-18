package com.example.store.service;

import java.util.List;

public record OrderCreatedEvent(List<Long> productIds) {}
