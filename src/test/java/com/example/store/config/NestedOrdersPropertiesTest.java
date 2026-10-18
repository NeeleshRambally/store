package com.example.store.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class NestedOrdersPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(Binding.class);

    @EnableConfigurationProperties(NestedOrdersProperties.class)
    static class Binding {}

    @Test
    void bindsTheConfiguredLimit() {
        runner.withPropertyValues("store.nested-orders.limit=7").run(context -> assertThat(
                        context.getBean(NestedOrdersProperties.class).limit())
                .isEqualTo(7));
    }

    @Test
    void rejectsALimitBelowOne() {
        runner.withPropertyValues("store.nested-orders.limit=0").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void rejectsALimitAboveOneHundred() {
        runner.withPropertyValues("store.nested-orders.limit=101").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void defaultsToTwentyInTheBaseConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getProperty("store.nested-orders.limit", Integer.class))
                .isEqualTo(20);
    }

    @Test
    void readsTheLimitFromNestedOrdersLimit() {
        System.setProperty("NESTED_ORDERS_LIMIT", "5");
        try {
            StandardEnvironment environment = new StandardEnvironment();
            ConfigDataEnvironmentPostProcessor.applyTo(environment);

            assertThat(environment.getProperty("store.nested-orders.limit", Integer.class))
                    .isEqualTo(5);
        } finally {
            System.clearProperty("NESTED_ORDERS_LIMIT");
        }
    }
}
