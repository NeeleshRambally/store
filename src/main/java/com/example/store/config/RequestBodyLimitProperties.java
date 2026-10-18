package com.example.store.config;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("store.request")
public record RequestBodyLimitProperties(@Min(1024) long maxBodyBytes) {}
