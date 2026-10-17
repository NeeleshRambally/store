package com.example.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(@NotBlank @Size(max = 255) String name) {}
