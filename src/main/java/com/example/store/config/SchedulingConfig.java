package com.example.store.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Switches on @Scheduled methods; the only one sweeps expired idempotency keys.
@Configuration
@EnableScheduling
public class SchedulingConfig {}
