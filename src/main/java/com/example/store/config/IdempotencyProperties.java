package com.example.store.config;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// The stale window must cover the transaction timeout, so a request still running is never taken over.
@Validated
@ConfigurationProperties("store.idempotency")
public record IdempotencyProperties(
        @DurationMin(nanos = 1) Duration ttl,
        @DurationMin(seconds = 15) Duration staleAfter,
        @DurationMin(nanos = 1) Duration purgeInterval) {}
