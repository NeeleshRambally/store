package com.example.store.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

// Refuses a blank Liquibase context list, which would otherwise apply every changeset, seed included.
public class LiquibaseContextsGuard implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("spring.liquibase.enabled", Boolean.class, true)) {
            return;
        }
        if (environment.getProperty("spring.liquibase.contexts", "").isBlank()) {
            throw new IllegalStateException("spring.liquibase.contexts is blank: set LIQUIBASE_CONTEXTS"
                    + " to a context such as prod or seed, or leave it unset");
        }
    }
}
