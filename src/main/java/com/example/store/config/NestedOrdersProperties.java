package com.example.store.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("store.nested-orders")
public record NestedOrdersProperties(@Min(1) @Max(100) int limit) {}
