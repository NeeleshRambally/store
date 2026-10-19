package com.example.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProductRequest(
        @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
                String description) {}
