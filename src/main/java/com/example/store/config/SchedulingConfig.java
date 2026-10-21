package com.example.store.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Switches on @Scheduled methods in the serving process only: the migration job runs the same image without a web
// server and must exit once its changesets are applied, which a scheduler's thread would prevent.
@Configuration
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SchedulingConfig {}
