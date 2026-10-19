package com.example.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

public record CreateOrderRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
                String description,
        @NotNull Long customerId,
        @NotEmpty @Size(max = 100) @UniqueElements List<@NotNull Long> productIds) {}
