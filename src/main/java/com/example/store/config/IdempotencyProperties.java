package com.example.store.config;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// The ttl, stale window and purge interval of the key store; IdempotencyStaleWindowGuard checks the window against
// the timeouts at start-up.
@Validated
@ConfigurationProperties("store.idempotency")
public record IdempotencyProperties(
        @DurationMin(nanos = 1) Duration ttl,
        @DurationMin(nanos = 1) Duration staleAfter,
        @DurationMin(nanos = 1) Duration purgeInterval) {}
